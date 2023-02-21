package models

import config.NostrillionaireAppConfig
import org.bitcoins.core.api.db.DbRowAutoInc
import org.bitcoins.core.currency._
import org.bitcoins.crypto.{SchnorrPublicKey, Sha256Digest}
import org.bitcoins.db.{CRUDAutoInc, DbCommonsColumnMappers}
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class RoundDb(
    id: Option[Long],
    number: Long,
    startDate: Long,
    endDate: Long,
    carryOver: Option[CurrencyUnit],
    noteId: Option[Sha256Digest],
    fiveMinWarning: Boolean,
    numZaps: Option[Int],
    totalZapped: Option[CurrencyUnit],
    prize: Option[CurrencyUnit],
    profit: Option[CurrencyUnit],
    winner: Option[SchnorrPublicKey])
    extends DbRowAutoInc[RoundDb] {
  require(startDate <= endDate, "Start date must be before end date")
  require(number > 0, "Round number must be greater than 0")

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

  def getCurrentRoundId(): Future[Long] = {
    val action = table
      .filter(_.profit.isEmpty)
      .sortBy(_.endDate.desc)
      .map(_.id)
      .result
      .map(_.head)

    safeDatabase.run(action)
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

    def carryOver: Rep[Option[CurrencyUnit]] = column("carry_over")

    def noteId: Rep[Option[Sha256Digest]] = column("note_id")

    def fiveMinWarning: Rep[Boolean] = column("five_min_warning")

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
       carryOver,
       noteId,
       fiveMinWarning,
       numZaps,
       totalZapped,
       prize,
       profit,
       winner).<>(RoundDb.tupled, RoundDb.unapply)
  }
}
