package controllers

import grizzled.slf4j.Logging
import models.RoundDb
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto._
import org.bitcoins.keymanager.WalletStorage
import org.scalastr.client.NostrClient
import org.scalastr.core._
import play.api.libs.json.Json

import java.net.URL
import java.text.NumberFormat
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

trait NostrHandler extends Logging { self: InvoiceMonitor =>
  import system.dispatcher

  def setNostrMetadata(): Future[Vector[Sha256Digest]] = {
    val metadata = Metadata.create(
      displayName = Some("Nostrillionaire"),
      name = Some("nostrillionaire"),
      about = Some(
        "Guess today's random number and win a prize!\nParticipate by zapping your guess.\nIf you win, you'll win everyone's zaps!"),
      nip05 = Some("_@nostrillionaire.com"),
      lud16 = Some("me@nostrillionaire.com"),
      website = Some(new URL("https://nostrillionaire.com")),
      picture =
        Some(new URL("https://nostrillionaire.com/assets/images/logo.jpeg"))
    )

    val event = NostrEvent.build(
      privateKey = nostrPrivateKey,
      created_at = 1676765686L, // change me when updates are made
      tags = Vector.empty,
      metadata = metadata)

    val contacts = NostrEvent.build(
      privateKey = nostrPrivateKey,
      created_at = 1676765686L, // change me when updates are made
      kind = NostrKind.Contacts,
      tags = Vector(
        Json.arr("p", nostrPubKey.hex),
        Json.arr(
          "p",
          "e1ff3bfdd4e40315959b08b4fcc8245eaa514637e1d4ec2ae166b743341be1af")),
      content = ""
    )

    sendNostrEvents(Vector(event, contacts), config.nostrRelays)
  }

  lazy val nostrPrivateKey: ECPrivateKey =
    WalletStorage
      .getPrivateKeyFromDisk(config.seedPath,
                             SegWitMainNetPriv,
                             config.aesPasswordOpt,
                             config.bip39PasswordOpt)
      .key

  lazy val nostrPubKey: SchnorrPublicKey = nostrPrivateKey.schnorrPublicKey

  private val intFormatter: NumberFormat =
    java.text.NumberFormat.getIntegerInstance

  protected def announceNewRound(
      min: Satoshis,
      max: Satoshis): Future[Option[Sha256Digest]] = {
    val content =
      s"""
         |Guess today's random number and win a prize!
         |
         |Zap this note between ${printAmount(min)} and ${printAmount(
          max)} with your guess for a chance to win!
         |
         |The closest guess without going over wins!
         |""".stripMargin.trim

    val event = NostrEvent.build(
      privateKey = nostrPrivateKey,
      created_at = TimeUtil.currentEpochSecond,
      kind = NostrKind.TextNote,
      tags = Vector.empty,
      content = content
    )

    sendNostrEvents(Vector(event), config.nostrRelays).map(_.headOption)
  }

  protected def announceWinner(
      roundDb: RoundDb,
      amountPaid: Satoshis): Future[Option[Sha256Digest]] = {
    require(roundDb.winner.isDefined, "Round must have a winner to announce")
    val winner = roundDb.winner.get

    val content =
      s"""
         |The winning number for today was ${intFormatter.format(
          roundDb.number)}!
         |
         |#[0] has won ${printAmount(
          roundDb.prize.get)} with a guess of ${intFormatter.format(
          amountPaid.toLong)}!
         |
         |Congratulations!
         |""".stripMargin.trim

    val event = NostrEvent.build(
      privateKey = nostrPrivateKey,
      created_at = TimeUtil.currentEpochSecond,
      kind = NostrKind.TextNote,
      tags = Vector(Json.arr("p", winner.hex)),
      content = content
    )

    sendNostrEvents(Vector(event), config.nostrRelays).map(_.headOption)
  }

  protected def announceNoWinner(
      roundDb: RoundDb): Future[Option[Sha256Digest]] = {
    require(roundDb.profit.isDefined, "Round must have a completed to announce")
    val content =
      s"""
         |The winning number for today was ${intFormatter.format(
          roundDb.number)}!
         |
         |Unfortunately, everyone lost their zaps.
         |
         |Better luck next time!
         |""".stripMargin.trim

    val event = NostrEvent.build(
      privateKey = nostrPrivateKey,
      created_at = TimeUtil.currentEpochSecond,
      kind = NostrKind.TextNote,
      tags = Vector.empty,
      content = content
    )

    sendNostrEvents(Vector(event), config.nostrRelays).map(_.headOption)
  }

  private def sendingClients(relays: Vector[String]): Vector[NostrClient] = {
    relays.map { relay =>
      new NostrClient(relay, None) {

        override def processEvent(
            subscriptionId: String,
            event: NostrEvent): Future[Unit] = {
          Future.unit
        }

        override def processNotice(notice: String): Future[Unit] = Future.unit
      }
    }
  }

  def sendNostrEvents(
      event: Vector[NostrEvent],
      relays: Vector[String]): Future[Vector[Sha256Digest]] = {
    val fs = sendingClients(relays).map { client =>
      Try(Await.result(client.start(), 5.seconds)) match {
        case Failure(_) =>
          logger.warn(s"Failed to connect to nostr relay: ${client.url}")
          Future.successful(None)
        case Success(_) =>
          val sendFs = event.map { event =>
            client
              .publishEvent(event)
              .map(_ => Some(event.id))
              .recover(_ => None)
          }

          val f = for {
            ids <- Future.sequence(sendFs)
            _ <- client.stop()
          } yield ids.flatten

          f.recover(_ => None)
      }
    }

    Future.sequence(fs).map(_.flatten)
  }

  protected def sendFailedPaymentDM(
      nostrPublicKey: NostrPublicKey): Future[Option[Sha256Digest]] = {
    val content =
      s"""
         |You won a round of Nostrillionaire, but we were unable to pay you!
         |
         |Please reach out to @npub1u8lnhlw5usp3t9vmpz60ejpyt649z33hu82wc2hpv6m5xdqmuxhs46turz to claim your prize!
         |
         |You can also find him as @benthecarman on Twitter or Telegram.
         |""".stripMargin.trim

    val event =
      NostrEvent.encryptedDM(content,
                             nostrPrivateKey,
                             TimeUtil.currentEpochSecond,
                             Vector.empty,
                             nostrPublicKey.key)

    sendNostrEvents(Vector(event), config.nostrRelays).map(_.headOption)
  }

  def getMetadata(nostrPublicKey: NostrPublicKey): Future[Option[Metadata]] = {
    val metadata: mutable.Buffer[NostrEvent] = mutable.Buffer.empty[NostrEvent]

    val clients: Vector[NostrClient] = {
      config.nostrRelays.filter(_ != "wss://nostr.mutinywallet.com").map {
        relay =>
          new NostrClient(relay, None) {

            override def processEvent(
                subscriptionId: String,
                event: NostrEvent): Future[Unit] = {
              if (
                event.kind == NostrKind.Metadata &&
                event.pubkey == nostrPublicKey.key
              ) {
                metadata += event
              }

              Future.unit
            }

            override def processNotice(notice: String): Future[Unit] =
              Future.unit
          }
      }
    }

    val filter: NostrFilter = NostrFilter(
      ids = None,
      authors = Some(Vector(nostrPublicKey.key)),
      kinds = Some(Vector(NostrKind.Metadata)),
      `#e` = None,
      `#p` = None,
      since = None,
      until = None,
      limit = Some(1)
    )

    val fs = clients.map { client =>
      Try(Await.result(client.start(), 5.seconds)) match {
        case Failure(_) =>
          logger.warn(s"Failed to connect to nostr relay: ${client.url}")
          Future.successful(None)
        case Success(_) =>
          for {
            _ <- client.subscribe(filter)
            _ <- AsyncUtil.awaitCondition(() => client.isStarted(),
                                          maxTries = 100)
          } yield ()
      }
    }

    Future.sequence(fs).map { _ =>
      metadata.toVector.sortBy(_.created_at).lastOption.flatMap { event =>
        Try(Json.parse(event.content).as[Metadata]).toOption
      }
    }
  }

  private def printAmount(amount: CurrencyUnit): String = {
    intFormatter.format(amount.satoshis.toLong) + " sats"
  }
}
