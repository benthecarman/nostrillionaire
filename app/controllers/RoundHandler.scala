package controllers

import grizzled.slf4j.Logging
import models._
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.currency._
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.lnurl.json.LnURLJsonModels._
import org.bitcoins.lnurl.{LnURL, LnURLClient}
import org.scalastr.core._
import play.api.libs.json._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

trait RoundHandler extends Logging { self: InvoiceMonitor =>
  import system.dispatcher

  private val random = new scala.util.Random(TimeUtil.currentEpochMs)

  private val lnurlClient = new LnURLClient(None)

  def startRoundScheduler(): Unit = {
    logger.info("Starting round scheduler")
    val _ = system.scheduler.scheduleWithFixedDelay(5.seconds, 1.minute) { () =>
      val f = for {
        current <- roundDAO.findCurrent()
        _ <- current match {
          case Some(round) =>
            val now = TimeUtil.currentEpochSecond
            if (round.endDate < now) {
              logger.info("Completing round")
              completeRound(round).flatMap(createNewRound)
            } else if (round.endDate - 360 < now && !round.fiveMinWarning) {
              for {
                zaps <- zapDAO.findPaidByRound(round.id.get)

                totalZapped = zaps.map(_.amount.toLong).sum
                totalZappedSats = MilliSatoshis(totalZapped).toSatoshis
                noteIdOpt <- fiveMinuteWarning(round, totalZappedSats)

                _ <- roundDAO.update(
                  round.copy(fiveMinWarning = noteIdOpt.isDefined))
              } yield logger.info(
                s"Sent 5 minute warning: ${noteIdOpt.getOrElse("None")}")
            } else {
              logger.debug("Round is still active")
              Future.unit
            }
          case None => createNewRound(None)
        }
      } yield ()

      f.failed.foreach(err => logger.error("Error in round scheduler", err))

      ()
    }
  }

  private def createNewRound(
      carryOver: Option[CurrencyUnit]): Future[RoundDb] = {
    val carryOverOpt =
      if (carryOver.exists(_ <= Satoshis.zero)) None
      else carryOver
    logger.info("Creating new round")
    val now = TimeUtil.currentEpochSecond
    val end = now + config.roundTimeSecs

    val number = config.min + random.nextLong((config.max - config.min) + 1)

    val round = RoundDb(
      id = None,
      number = number,
      startDate = now,
      endDate = end,
      carryOver = carryOverOpt,
      noteId = None,
      fiveMinWarning = false,
      completed = false,
      numZaps = None,
      totalZapped = None,
      prize = None,
      profit = None,
      winner = None
    )

    for {
      created <- roundDAO.create(round)
      noteId <- announceNewRound(min = Satoshis(config.min),
                                 max = Satoshis(config.max),
                                 carryOver = carryOverOpt)
      updated <- roundDAO.update(created.copy(noteId = noteId))

      _ <- telegramHandlerOpt
        .map(_.sendTelegramMessage(s"New round created!"))
        .getOrElse(Future.unit)
      _ = logger.info(s"Created new round: $updated")
    } yield updated
  }

  private def completeRound(roundDb: RoundDb): Future[Option[CurrencyUnit]] = {
    zapDAO.findPaidByRound(roundDb.id.get).flatMap { zaps =>
      val totalZapped = zaps.map(_.amount.toLong).sum
      val totalZappedSats = MilliSatoshis(totalZapped).toSatoshis
      val numZaps = zaps.size

      val prizePool =
        roundDb.carryOver.getOrElse(Satoshis.zero) + totalZappedSats

      val winnerOpt = RoundHandler.calculateWinner(zaps, roundDb.number)

      winnerOpt match {
        case Some(winner) =>
          val prize = {
            if (numZaps > 1) {
              val double = prizePool.satoshis.toLong * 0.9
              if (double < winner.amount.toSatoshis.toLong) {
                prizePool.satoshis
              } else Satoshis(double.toLong)
            } else prizePool.satoshis
          }
          val profit = prizePool - prize

          val updatedRound = roundDb.copy(
            numZaps = Some(numZaps),
            totalZapped = Some(totalZappedSats),
            prize = Some(prize),
            profit = Some(profit),
            winner = Some(winner.payer)
          )

          val announceF = announceWinner(updatedRound, winner.satoshis)

          val telegramF = telegramHandlerOpt
            .map(_.notifyRoundComplete(updatedRound, Some(winner.satoshis)))
            .getOrElse(Future.unit)

          for {
            _ <- roundDAO.update(updatedRound)
            _ = logger.info(s"Round saved to database")
            _ <- payWinner(updatedRound)
              .map(_ => logger.info(s"Winner paid!"))
              .recover(ex => logger.error(s"Could not pay winner: $winner", ex))
            _ <- announceF
            _ <- telegramF
            _ = logger.info(s"Completed round: $updatedRound")
          } yield None
        case None =>
          val updatedRound = roundDb.copy(
            numZaps = Some(numZaps),
            totalZapped = Some(totalZappedSats),
            prize = Some(Satoshis.zero),
            profit = Some(Satoshis.zero),
            winner = None
          )

          val announceF = announceNoWinner(updatedRound, prizePool)

          val telegramF = telegramHandlerOpt
            .map(_.notifyRoundComplete(updatedRound, None))
            .getOrElse(Future.unit)

          for {
            _ <- roundDAO.update(updatedRound)
            _ = logger.info(s"Round saved to database")
            _ <- announceF
            _ <- telegramF
            _ = logger.info(s"Completed round: $updatedRound")
          } yield Some(prizePool)
      }
    }
  }

  private def warnPaymentFailure(
      roundDb: RoundDb,
      reason: String): Future[Unit] = {
    val winner = NostrPublicKey(roundDb.winner.get)

    val telegramF = telegramHandlerOpt
      .map(
        _.sendFailedPaymentNotification(roundDb.id.get,
                                        winner,
                                        roundDb.prize.get,
                                        reason))
      .getOrElse(Future.unit)

    for {
      _ <- sendFailedPaymentDM(winner)
        .map(_ => ())
        .recover(ex =>
          logger.error(s"Could not send payment failure DM to $winner", ex))
      _ <- telegramF
    } yield logger.error(reason)
  }

  private def payWinner(roundDb: RoundDb): Future[Unit] = {
    require(roundDb.winner.isDefined, "Cannot pay winner if there is no winner")
    val winner = NostrPublicKey(roundDb.winner.get)

    getMetadata(winner).recover(_ => None).flatMap {
      case None =>
        warnPaymentFailure(roundDb, s"Could not find metadata for $winner")
      case Some(metadata) =>
        val urlOpt = metadata.lud06 match {
          case Some(lnurl) =>
            val url = LnURL.fromString(lnurl).url
            Some((url, lnurl))
          case None =>
            metadata.lud16 match {
              case Some(lnAddr) =>
                val url = LightningAddress(lnAddr).lnurlp
                val lnurl = LnURL.fromURL(url.toString).toString
                Some((url, lnurl))
              case None => None
            }
        }

        urlOpt match {
          case Some((url, bech32)) =>
            lnurlClient.makeRequest(url).flatMap {
              case pay: LnURLPayResponse =>
                val paymentAmount = roundDb.prize.get
                val paymentMsats =
                  MilliSatoshis.fromSatoshis(paymentAmount.satoshis)

                val tags = Vector(
                  Json.arr("p", winner.hex),
                  Json.arr("amount", paymentMsats.toLong),
                  Json.arr("lnurl", bech32)
                )

                val zapRequest = NostrEvent.build(
                  nostrPrivateKey,
                  TimeUtil.currentEpochSecond,
                  NostrKind.ZapRequest,
                  tags,
                  "Winnings from Nostrillionaire!"
                )

                val f = for {
                  invoice <- lnurlClient.getInvoice(
                    pay = pay,
                    amount = paymentAmount,
                    extraParams =
                      Map("nostr" -> Json.toJson(zapRequest).toString))

                  // todo verify invoice description hash

                  payment <- lnd.sendPayment(invoice, 20.seconds)

                  payoutDbOpt <- {
                    if (payment.failureReason.isFailureReasonNone) {
                      Future.successful(Some(PayoutDb(
                        round = roundDb.id.get,
                        invoice = invoice,
                        amount = paymentAmount,
                        fee = Satoshis(payment.feeSat),
                        preimage = payment.paymentPreimage,
                        date = TimeUtil.currentEpochSecond
                      )))
                    } else {
                      warnPaymentFailure(
                        roundDb,
                        s"Failed to pay $winner: ${payment.failureReason}").map(
                        _ => None)
                    }
                  }

                  _ <- payoutDbOpt match {
                    case Some(payoutDb) => payoutDAO.create(payoutDb)
                    case None           => Future.unit
                  }
                } yield ()

                f.recoverWith { ex: Throwable =>
                  warnPaymentFailure(roundDb,
                                     s"Failed to pay $winner: ${ex.getMessage}")
                }
              case _: LnURLWithdrawResponse =>
                warnPaymentFailure(roundDb,
                                   s"Got a lnurl withdraw response for $winner")
            }
          case None =>
            warnPaymentFailure(roundDb, s"Could not find LNURL for $winner")
        }
    }
  }
}

object RoundHandler {

  def calculateWinner(zaps: Vector[ZapDb], number: Long): Option[ZapDb] = {
    zaps.find(_.amount.toSatoshis.toLong == number)
  }
}
