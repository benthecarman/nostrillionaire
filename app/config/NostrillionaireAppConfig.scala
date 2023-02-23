package config

import akka.actor.ActorSystem
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import org.bitcoins.commons.config._
import org.bitcoins.commons.util.NativeProcessFactory
import org.bitcoins.core.hd.HDPurposes
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto.AesPassword
import org.bitcoins.db.{DbAppConfig, DbManagement, JdbcProfileComponent}
import models._
import org.bitcoins.keymanager.WalletStorage
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.keymanager.config.KeyManagerAppConfig
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config._
import scodec.bits.ByteVector

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.concurrent._
import scala.jdk.CollectionConverters._
import scala.util.{Properties, Try}

/** Configuration for Nostrillionaire
  *
  * @param directory
  *   The data directory of the wallet
  * @param configOverrides
  *   Optional sequence of configuration overrides
  */
case class NostrillionaireAppConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit system: ActorSystem)
    extends DbAppConfig
    with JdbcProfileComponent[NostrillionaireAppConfig]
    with DbManagement
    with Logging {
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  override val moduleName: String = NostrillionaireAppConfig.moduleName
  override type ConfigType = NostrillionaireAppConfig

  override val appConfig: NostrillionaireAppConfig = this

  import profile.api._

  override def newConfigOfType(
      configs: Vector[Config]): NostrillionaireAppConfig =
    NostrillionaireAppConfig(directory, configs)

  val baseDatadir: Path = directory

  private lazy val lndDataDir: Path = {
    config.getStringOrNone(s"bitcoin-s.lnd.datadir") match {
      case Some(str) => Paths.get(str.replace("~", Properties.userHome))
      case None      => LndInstanceLocal.DEFAULT_DATADIR
    }
  }

  private lazy val lndRpcUri: Option[URI] = {
    config.getStringOrNone(s"bitcoin-s.lnd.rpcUri").map { str =>
      if (str.startsWith("http") || str.startsWith("https")) {
        new URI(str)
      } else {
        new URI(s"http://$str")
      }
    }
  }

  private lazy val lndMacaroonOpt: Option[String] = {
    config.getStringOrNone(s"bitcoin-s.lnd.macaroonFile").map { pathStr =>
      val path = Paths.get(pathStr.replace("~", Properties.userHome))
      val bytes = Files.readAllBytes(path)

      ByteVector(bytes).toHex
    }
  }

  private lazy val lndTlsCertOpt: Option[File] = {
    config.getStringOrNone(s"bitcoin-s.lnd.tlsCert").map { pathStr =>
      val path = Paths.get(pathStr.replace("~", Properties.userHome))
      path.toFile
    }
  }

  private lazy val lndBinary: File = {
    config.getStringOrNone(s"bitcoin-s.lnd.binary") match {
      case Some(str) => new File(str.replace("~", Properties.userHome))
      case None =>
        NativeProcessFactory
          .findExecutableOnPath("lnd")
          .getOrElse(sys.error("Could not find lnd binary"))
    }
  }

  private lazy val lndInstance: LndInstance = {
    lndMacaroonOpt match {
      case Some(value) =>
        LndInstanceRemote(
          rpcUri = lndRpcUri.getOrElse(new URI("http://127.0.0.1:10009")),
          macaroon = value,
          certFileOpt = lndTlsCertOpt,
          certificateOpt = None)
      case None =>
        val dir = lndDataDir.toFile
        require(dir.exists, s"$lndDataDir does not exist!")
        require(dir.isDirectory, s"$lndDataDir is not a directory!")

        val confFile = lndDataDir.resolve("lnd.conf").toFile
        val config = LndConfig(confFile, dir)

        val remoteConfig = config.lndInstanceRemote

        lndRpcUri match {
          case Some(uri) => remoteConfig.copy(rpcUri = uri)
          case None      => remoteConfig
        }
    }
  }

  lazy val lndRpcClient: LndRpcClient =
    new LndRpcClient(lndInstance, Try(lndBinary).toOption)

  lazy val telegramCreds: String =
    config.getStringOrElse(s"bitcoin-s.$moduleName.telegramCreds", "")

  lazy val telegramId: String =
    config.getStringOrElse(s"bitcoin-s.$moduleName.telegramId", "")

  lazy val kmConf: KeyManagerAppConfig =
    KeyManagerAppConfig(baseDatadir, configOverrides)

  lazy val seedPath: Path = {
    kmConf.seedFolder.resolve("encrypted-bitcoin-s-seed.json")
  }

  lazy val kmParams: KeyManagerParams =
    KeyManagerParams(seedPath, HDPurposes.SegWit, network)

  lazy val aesPasswordOpt: Option[AesPassword] = kmConf.aesPasswordOpt
  lazy val bip39PasswordOpt: Option[String] = kmConf.bip39PasswordOpt

  lazy val nostrRelays: Vector[String] = {
    if (config.hasPath("nostr.relays")) {
      config.getStringList(s"nostr.relays").asScala.toVector
    } else Vector.empty
  }

  lazy val min: Int = {
    if (config.hasPath(s"$moduleName.min")) {
      config.getInt(s"$moduleName.min")
    } else 1
  }

  lazy val max: Int = {
    if (config.hasPath(s"$moduleName.max")) {
      config.getInt(s"$moduleName.max")
    } else 10_000
  }

  lazy val roundTimeSecs = {
    if (config.hasPath(s"$moduleName.roundTimeSecs")) {
      config.getInt(s"$moduleName.roundTimeSecs")
    } else 60 * 60 // 1 hour
  }

  def seedExists(): Boolean = {
    WalletStorage.seedExists(seedPath)
  }

  def initialize(): Unit = {
    // initialize seed
    if (!seedExists()) {
      BIP39KeyManager.initialize(aesPasswordOpt = aesPasswordOpt,
                                 kmParams = kmParams,
                                 bip39PasswordOpt = bip39PasswordOpt) match {
        case Left(err) => sys.error(err.toString)
        case Right(_) =>
          logger.info("Successfully generated a seed and key manager")
      }
    }

    ()
  }

  override def start(): Future[Unit] = {
    logger.info(s"Initializing setup")

    if (Files.notExists(baseDatadir)) {
      Files.createDirectories(baseDatadir)
    }

    val numMigrations = migrate().migrationsExecuted
    logger.info(s"Applied $numMigrations")

    initialize()
    Future.unit
  }

  override def stop(): Future[Unit] = Future.unit

  override lazy val dbPath: Path = baseDatadir

  override val allTables: List[TableQuery[Table[_]]] = {
    val roundTable: TableQuery[Table[_]] = models.RoundDAO()(ec, this).table
    val zapTable: TableQuery[Table[_]] = ZapDAO()(ec, this).table
    val payoutTable: TableQuery[Table[_]] = PayoutDAO()(ec, this).table

    List(roundTable, zapTable, payoutTable)
  }
}

object NostrillionaireAppConfig
    extends AppConfigFactoryBase[NostrillionaireAppConfig, ActorSystem] {

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".nostrillionaire")

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ActorSystem): NostrillionaireAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ActorSystem): NostrillionaireAppConfig =
    NostrillionaireAppConfig(datadir, confs)

  override val moduleName: String = "nostrillionaire"
}
