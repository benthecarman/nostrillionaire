package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.api.declarative.Commands
import com.bot4s.telegram.clients.FutureSttpClient
import com.bot4s.telegram.future.{Polling, TelegramBot}
import com.bot4s.telegram.methods.SetMyCommands
import com.bot4s.telegram.models.{BotCommand, Message}
import config.NostrillionaireAppConfig
import models._
import org.bitcoins.core.currency._
import org.bitcoins.core.util.StartStopAsync
import org.scalastr.core.NostrPublicKey
import sttp.capabilities.akka.AkkaStreams
import sttp.client3.SttpBackend
import sttp.client3.akkahttp.AkkaHttpBackend

import java.net.URLEncoder
import java.text.NumberFormat
import scala.concurrent.Future

class TelegramHandler(controller: Controller)(implicit
    config: NostrillionaireAppConfig,
    system: ActorSystem)
    extends TelegramBot
    with Polling
    with Commands[Future]
    with StartStopAsync[Unit] {

  private val intFormatter: NumberFormat =
    java.text.NumberFormat.getIntegerInstance

  val roundDAO: RoundDAO = RoundDAO()
  val zapDAO: ZapDAO = ZapDAO()

  private val myTelegramId = config.telegramId
  private val telegramCreds = config.telegramCreds

  implicit private val backend: SttpBackend[Future, AkkaStreams] =
    AkkaHttpBackend.usingActorSystem(system)

  override val client: RequestHandler[Future] = new FutureSttpClient(
    telegramCreds)

  override def start(): Future[Unit] = {
    val commands = List(
      BotCommand("report", "Generate report of all events"),
      BotCommand("processunhandled", "Forces processing of invoices")
    )

    for {
      _ <- run()
      _ <- request(SetMyCommands(commands))
      _ <- sendTelegramMessage("Connected!", myTelegramId)
    } yield ()
  }

  override def stop(): Future[Unit] = Future.unit

  private def checkAdminMessage(msg: Message): Boolean = {
    msg.from match {
      case Some(user) => user.id.toString == myTelegramId
      case None       => false
    }
  }

  onCommand("report") { implicit msg =>
    if (checkAdminMessage(msg)) {
      createReport().flatMap { report =>
        reply(report).map(_ => ())
      }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  onCommand("processunhandled") { implicit msg =>
    if (checkAdminMessage(msg)) {
      controller.invoiceMonitor.processUnhandledZaps().flatMap { dbs =>
        reply(s"Updated ${dbs.size} invoices").map(_ => ())
      }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  private val http = Http()

  def sendTelegramMessage(
      message: String,
      telegramId: String = myTelegramId): Future[Unit] = {
    val url = s"https://api.telegram.org/bot$telegramCreds/sendMessage" +
      s"?chat_id=${URLEncoder.encode(telegramId, "UTF-8")}" +
      s"&text=${URLEncoder.encode(message.trim, "UTF-8")}"

    http.singleRequest(Get(url)).map(_ => ())
  }

  def notifyRoundComplete(
      roundDb: RoundDb,
      amountPaidOpt: Option[Satoshis]): Future[Unit] = {
    require(roundDb.profit.isDefined, "Round is not complete!")

    val telegramMsg =
      s"""
         |🔔 🔔 Round Completed! 🔔 🔔
         |
         |Winning Number: ${roundDb.number}
         |Winner: ${roundDb.winner
          .map(NostrPublicKey(_).toString)
          .getOrElse("None")}
         |Guess: ${amountPaidOpt.map(printAmount).getOrElse("None")}
         |
         |Prize: ${printAmount(roundDb.prize.get)}
         |Num Zaps: ${intFormatter.format(roundDb.numZaps.get)}
         |Total Zapped: ${printAmount(roundDb.totalZapped.get)}
         |Profit: ${printAmount(roundDb.profit.get)}
         |""".stripMargin

    sendTelegramMessage(telegramMsg, myTelegramId)
  }

  def handleZap(zapDb: ZapDb): Future[Unit] = {
    val requestEvent = zapDb.requestEvent

    val comment =
      if (requestEvent.content.nonEmpty)
        s"Comment: ${requestEvent.content}"
      else ""
    val telegramMsg =
      s"""
         |⚡ ⚡ Zapped! ⚡ ⚡
         |
         |Amount: ${printAmount(zapDb.amount.toSatoshis)}
         |From: ${NostrPublicKey(requestEvent.pubkey)}
         |Note: ${zapDb.noteIdOpt.map(_.toString).getOrElse("Failed")}
         |$comment
         |""".stripMargin.trim

    sendTelegramMessage(telegramMsg, myTelegramId)
  }

  def sendFailedPaymentNotification(
      roundId: Long,
      winner: NostrPublicKey,
      amount: CurrencyUnit,
      reason: String): Future[Unit] = {
    val telegramMsg =
      s"""
         |❌ ❌ Failed Payment ❌ ❌
         |
         |Failed to do payout for round $roundId for $winner
         |
         |Prize: ${printAmount(amount)}
         |Reason: $reason
         |""".stripMargin.trim

    sendTelegramMessage(telegramMsg, myTelegramId)
  }

  private def createReport(): Future[String] = {
    roundDAO.getCompleted().map { completed =>
      val profit = completed.flatMap(_.profit).sum
      val noWinnerRounds = completed.count(_.winner.isEmpty)
      val totalZapped = completed.flatMap(_.totalZapped).sum
      val numZaps = completed.flatMap(_.numZaps).sum

      s"""
         |Total Rounds: ${intFormatter.format(completed.size)}
         |Rounds w/o Winner: ${intFormatter.format(noWinnerRounds)}
         |
         |Num Zaps: ${intFormatter.format(numZaps)}
         |Total Zapped: ${printAmount(totalZapped)}
         |Total profit: ${printAmount(profit)}
         |""".stripMargin
    }
  }

  private def printAmount(amount: CurrencyUnit): String = {
    intFormatter.format(amount.satoshis.toLong) + " sats"
  }
}
