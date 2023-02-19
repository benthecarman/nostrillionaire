package models

import config.NostrillionaireAppConfig
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class PayoutDb(
    round: Long,
    invoice: LnInvoice,
    amount: CurrencyUnit,
    fee: CurrencyUnit,
    preimage: String,
    date: Long)

case class PayoutDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: NostrillionaireAppConfig)
    extends CRUD[PayoutDb, Long]
    with SlickUtil[PayoutDb, Long] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[PayoutTable] = TableQuery[PayoutTable]

  override def createAll(ts: Vector[PayoutDb]): Future[Vector[PayoutDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Long]): Query[PayoutTable, PayoutDb, Seq] =
    table.filter(_.round.inSet(ids))

  override protected def findAll(
      ts: Vector[PayoutDb]): Query[PayoutTable, PayoutDb, Seq] =
    findByPrimaryKeys(ts.map(_.round))

  class PayoutTable(tag: Tag)
      extends Table[PayoutDb](tag, schemaName, "payouts") {

    def round: Rep[Long] = column("round", O.PrimaryKey)

    def invoice: Rep[LnInvoice] = column("invoice", O.Unique)

    def amount: Rep[CurrencyUnit] = column("amount")

    def fee: Rep[CurrencyUnit] = column("fee")

    def preimage: Rep[String] = column("preimage", O.Unique)

    def date: Rep[Long] = column("date")

    def * : ProvenShape[PayoutDb] =
      (round, invoice, amount, fee, preimage, date).<>(PayoutDb.tupled,
                                                       PayoutDb.unapply)
  }
}
