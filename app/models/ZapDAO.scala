package models

import config.NostrillionaireAppConfig
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import org.scalastr.core.{NostrEvent, NostrNoteId}
import play.api.libs.json.Json
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class ZapDb(
    rHash: Sha256Digest,
    round: Long,
    invoice: LnInvoice,
    payer: SchnorrPublicKey,
    amount: MilliSatoshis,
    request: String,
    date: Long,
    noteId: Option[Sha256Digest]) {
  def satoshis: Satoshis = amount.toSatoshis
  def requestEvent: NostrEvent = Json.parse(request).as[NostrEvent]
  def noteIdOpt: Option[NostrNoteId] = noteId.map(NostrNoteId(_))
}

case class ZapDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: NostrillionaireAppConfig)
    extends CRUD[ZapDb, Sha256Digest]
    with SlickUtil[ZapDb, Sha256Digest] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[ZabTable] = TableQuery[ZabTable]

  override def createAll(ts: Vector[ZapDb]): Future[Vector[ZapDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[ZabTable, ZapDb, Seq] =
    table.filter(_.rHash.inSet(ids))

  override protected def findAll(
      ts: Vector[ZapDb]): Query[ZabTable, ZapDb, Seq] =
    findByPrimaryKeys(ts.map(_.rHash))

  def findPaidByRoundAction(
      round: Long): DBIOAction[Vector[ZapDb], NoStream, Effect.Read] = {
    table
      .filter(_.round === round)
      .filter(_.noteIdOpt.isDefined)
      .result
      .map(_.toVector)
  }

  def findPaidByRound(round: Long): Future[Vector[ZapDb]] = {
    safeDatabase.run(findPaidByRoundAction(round))
  }

  def findUnpaid(): Future[Vector[ZapDb]] = {
    val query = table.filter(_.noteIdOpt.isEmpty)
    safeDatabase.runVec(query.result)
  }

  class ZabTable(tag: Tag) extends Table[ZapDb](tag, schemaName, "zaps") {

    def rHash: Rep[Sha256Digest] = column("r_hash", O.PrimaryKey)

    def round: Rep[Long] = column("round")

    def invoice: Rep[LnInvoice] = column("invoice", O.Unique)

    def payer: Rep[SchnorrPublicKey] = column("payer")

    def amount: Rep[MilliSatoshis] = column("amount")

    def request: Rep[String] = column("request")

    def date: Rep[Long] = column("date")

    def noteIdOpt: Rep[Option[Sha256Digest]] = column("note_id", O.Unique)

    def * : ProvenShape[ZapDb] =
      (rHash, round, invoice, payer, amount, request, date, noteIdOpt).<>(
        ZapDb.tupled,
        ZapDb.unapply)
  }
}
