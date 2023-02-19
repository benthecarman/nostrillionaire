package models

import config.NostrillionaireAppConfig
import org.bitcoins.core.api.db.DbRowAutoInc
import org.bitcoins.core.currency._
import org.bitcoins.crypto.SchnorrPublicKey
import org.bitcoins.db.{CRUDAutoInc, DbCommonsColumnMappers}
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class RoundDb(
    id: Option[Long],
    number: Long,
    startDate: Long,
    endDate: Long,
    numZaps: Option[Int],
    totalZapped: Option[CurrencyUnit],
    prize: Option[CurrencyUnit],
    profit: Option[CurrencyUnit],
    winner: Option[SchnorrPublicKey])
    extends DbRowAutoInc[RoundDb] {
  override def copyWithId(id: Long): RoundDb = this.copy(id = Some(id))
}

case class RoundDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: NostrillionaireAppConfig)
    extends CRUDAutoInc[RoundDb] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[RoundTable] = TableQuery[RoundTable]

  def findCurrentAction(): DBIOAction[Option[RoundDb],
                                      NoStream,
                                      Effect.Read] = {
    table
      .filter(_.profit.isEmpty)
      .sortBy(_.endDate.desc)
      .result
      .map(_.headOption)
  }

  def findCurrent(): Future[Option[RoundDb]] = {
    safeDatabase.run(findCurrentAction())
  }

  def getCompleted(): Future[Seq[RoundDb]] = {
    val action = table
      .filter(_.profit.isDefined)
      .sortBy(_.endDate.desc)
      .result

    safeDatabase.run(action)
  }

  class RoundTable(tag: Tag)
      extends TableAutoInc[RoundDb](tag, schemaName, "rounds") {

    def number: Rep[Long] = column("number")

    def startDate: Rep[Long] = column("start_date")

    def endDate: Rep[Long] = column("end_date")

    def numZaps: Rep[Option[Int]] = column("num_zaps")

    def totalZapped: Rep[Option[CurrencyUnit]] = column("total_zapped")

    def prize: Rep[Option[CurrencyUnit]] = column("prize")

    def profit: Rep[Option[CurrencyUnit]] = column("profit")

    def winner: Rep[Option[SchnorrPublicKey]] = column("winner")

    def * : ProvenShape[RoundDb] =
      (id.?,
       number,
       startDate,
       endDate,
       numZaps,
       totalZapped,
       prize,
       profit,
       winner).<>(RoundDb.tupled, RoundDb.unapply)
  }
}
