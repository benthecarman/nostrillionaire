package controllers

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import config.NostrillionaireAppConfig
import grizzled.slf4j.Logging
import lnrpc.Invoice
import models._
import org.bitcoins.core.protocol.ln.LnTag.PaymentHashTag
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.{LndRpcClient, LndUtils}
import org.scalastr.core.{NostrEvent, NostrKind}
import play.api.libs.json.Json
import scodec.bits.ByteVector

import scala.concurrent._

class InvoiceMonitor(
    val lnd: LndRpcClient,
    val telegramHandlerOpt: Option[TelegramHandler])(implicit
    val system: ActorSystem,
    val config: NostrillionaireAppConfig)
    extends Logging
    with LndUtils
    with RoundHandler
    with NostrHandler {
  import system.dispatcher

  val roundDAO: RoundDAO = RoundDAO()
  val zapDAO: ZapDAO = ZapDAO()
  val payoutDAO: PayoutDAO = PayoutDAO()

  def startInvoiceSubscription(): Unit = {
    val parallelism = Runtime.getRuntime.availableProcessors()

    lnd
      .subscribeInvoices()
      .mapAsyncUnordered(parallelism) { invoice =>
        invoice.state match {
          case lnrpc.Invoice.InvoiceState.OPEN |
              lnrpc.Invoice.InvoiceState.ACCEPTED |
              _: lnrpc.Invoice.InvoiceState.Unrecognized =>
            Future.unit
          case lnrpc.Invoice.InvoiceState.CANCELED => Future.unit
          case lnrpc.Invoice.InvoiceState.SETTLED =>
            val rHash = Sha256Digest(invoice.rHash)

            zapDAO.read(rHash).flatMap {
              case Some(zapDb) =>
                zapDb.noteId match {
                  case Some(_) =>
                    logger.warn(
                      s"Processed zap that already has a note associated with it, rHash: ${invoice.rHash.toBase16}")
                    Future.unit
                  case None =>
                    require(invoice.amtPaidMsat >= invoice.valueMsat,
                            "User did not pay invoice in full")
                    onZapPaid(zapDb, invoice.rPreimage).map(_ => ())
                }
              case None =>
                logger.debug(
                  s"Could not find zap for invoice with rHash: ${invoice.rHash.toBase16}")
                Future.unit
            }
        }
      }
      .runWith(Sink.ignore)
    ()
  }

  def processUnhandledZaps(): Future[Vector[ZapDb]] = {
    zapDAO.findUnpaid().flatMap { unpaid =>
      if (unpaid.nonEmpty) {
        val time = System.currentTimeMillis()
        logger.info(s"Processing ${unpaid.size} unhandled zaps")

        val updateFs = unpaid.map { db =>
          lnd
            .lookupInvoice(PaymentHashTag(db.rHash))
            .flatMap { inv =>
              inv.state match {
                case Invoice.InvoiceState.OPEN |
                    Invoice.InvoiceState.ACCEPTED =>
                  Future.successful(None)
                case Invoice.InvoiceState.SETTLED =>
                  if (inv.amtPaidMsat >= inv.valueMsat) {
                    onZapPaid(db, inv.rPreimage).map(Some(_))
                  } else Future.successful(None)
                case Invoice.InvoiceState.CANCELED =>
                  Future.successful(None)
                case Invoice.InvoiceState.Unrecognized(_) =>
                  Future.successful(None)
              }
            }
            .recover { case _: Throwable => None }
        }

        val f = for {
          updates <- Future.sequence(updateFs).map(_.flatten)
          dbs <- zapDAO.updateAll(updates)
          took = System.currentTimeMillis() - time
          _ = logger.info(
            s"Processed ${dbs.size} unhandled zaps, took $took ms")
        } yield dbs

        f.failed.map(logger.error("Error processing zaps invoices", _))

        f
      } else Future.successful(Vector.empty)
    }
  }

  def onZapPaid(zapDb: ZapDb, preimage: ByteVector): Future[ZapDb] = {
    val requestEvent = zapDb.requestEvent

    val eTag =
      requestEvent.tags.filter(_.value.head.asOpt[String].contains("e"))

    val pTag =
      requestEvent.tags.filter(_.value.head.asOpt[String].contains("p"))

    val tags = Vector(
      Json.arr("bolt11", zapDb.invoice.toString),
      Json.arr("preimage", preimage.toBase16),
      Json.arr("description", zapDb.request)
    ) ++ eTag ++ pTag

    val zapEvent =
      NostrEvent.build(nostrPrivateKey,
                       TimeUtil.currentEpochSecond,
                       NostrKind.Zap,
                       tags,
                       "")

    logger.info(s"Zap event created: ${Json.toJson(zapEvent).toString}")

    val relays = (requestEvent.taggedRelays ++ config.nostrRelays).distinct

    for {
      _ <- sendNostrEvents(Vector(requestEvent, zapEvent), relays)
      updatedZap = zapDb.copy(noteId = Some(zapEvent.id))
      res <- zapDAO.update(updatedZap)
      _ <- telegramHandlerOpt.map(_.handleZap(res)).getOrElse(Future.unit)
    } yield res
  }
}
