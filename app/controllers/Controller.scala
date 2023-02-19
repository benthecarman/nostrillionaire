package controllers

import akka.actor.ActorSystem
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import config.NostrillionaireAppConfig
import grizzled.slf4j.Logging
import models._
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnurl.json.LnURLJsonModels._
import org.scalastr.core.{NostrEvent, NostrPublicKey}
import play.api.libs.json._
import play.api.mvc._
import scodec.bits.ByteVector

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.{URL, URLDecoder}
import javax.imageio.ImageIO
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class Controller @Inject() (cc: MessagesControllerComponents)
    extends MessagesAbstractController(cc)
    with Logging {

  implicit lazy val system: ActorSystem = {
    val system = ActorSystem("nostrillionaire")
    system.log.info("Akka logger started")
    system
  }
  implicit lazy val ec: ExecutionContext = system.dispatcher

  implicit lazy val config: NostrillionaireAppConfig =
    NostrillionaireAppConfig.fromDefaultDatadir()

  lazy val lnd: LndRpcClient = config.lndRpcClient

  val startF: Future[Unit] = config.start()

  val roundDAO: RoundDAO = RoundDAO()
  val zapDAO: ZapDAO = ZapDAO()

  private val telegramHandler = new TelegramHandler(this)

  lazy val invoiceMonitor =
    new InvoiceMonitor(lnd, Some(telegramHandler))

  startF.map { _ =>
    telegramHandler.start()
    invoiceMonitor.startInvoiceSubscription()
    invoiceMonitor.setNostrMetadata()
    invoiceMonitor.startRoundScheduler()
  }

  def notFound(route: String): Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      NotFound(views.html.notFound())
    }
  }

  def index: Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      Ok(views.html.index(NostrPublicKey(invoiceMonitor.nostrPubKey)))
    }
  }

  private val defaultNip5: JsObject = Json.obj(
    "names" -> Json.obj(
      "_" -> invoiceMonitor.nostrPubKey.hex,
      "me" -> invoiceMonitor.nostrPubKey.hex,
      "nostrillionaire" -> invoiceMonitor.nostrPubKey.hex
    ))

  def nip5(): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      Future.successful(
        Ok(defaultNip5)
          .withHeaders(
            "Access-Control-Allow-Origin" -> "*",
            "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
            "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
            "Access-Control-Allow-Credentials" -> "true"
          ))
    }
  }

  def getLnurlPay(user: String): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      val metadata =
        s"[[\"text/plain\",\"Nostrillionaire!\"],[\"text/identifier\",\"$user@${request.host}\"]]"
      val hash = CryptoUtil.sha256(ByteVector(metadata.getBytes("UTF-8"))).hex

      val url =
        new URL(s"https://${request.host}/lnurlp/$hash")

      val response =
        LnURLPayResponse(
          callback = url,
          maxSendable = MilliSatoshis(Bitcoins.one),
          minSendable = MilliSatoshis(Satoshis.one),
          metadata = metadata,
          nostrPubkey = Some(invoiceMonitor.nostrPubKey),
          allowsNostr = Some(true)
        )

      val result = Ok(Json.toJson(response)).withHeaders(
        "Access-Control-Allow-Origin" -> "*",
        "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
        "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
        "Access-Control-Allow-Credentials" -> "true"
      )

      Future.successful(result)
    }
  }

  def lnurlPay(meta: String): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      request.getQueryString("amount") match {
        case Some(amountStr) =>
          val amount = MilliSatoshis(amountStr.toLong)

          request.getQueryString("nostr") match {
            case Some(eventStr) =>
              logger.info("Receiving zap request!")
              // nostr zap
              val decoded = URLDecoder.decode(eventStr, "UTF-8")
              val event = Json.parse(decoded).as[NostrEvent]
              require(NostrEvent.isValidZapRequest(event),
                      "not valid zap request")

              val hash = CryptoUtil.sha256(decoded)

              for {
                invoice <- lnd.addInvoice(hash, amount, 360)
                db = ZapDb(
                  rHash = invoice.rHash.hash,
                  round = 1, // todo
                  invoice = invoice.invoice,
                  payer = event.pubkey,
                  amount = amount,
                  request = decoded,
                  date = TimeUtil.currentEpochSecond,
                  noteId = None
                )
                _ <- zapDAO.create(db)
              } yield {
                val response = LnURLPayInvoice(invoice.invoice, None)
                Ok(Json.toJson(response)).withHeaders(
                  "Access-Control-Allow-Origin" -> "*",
                  "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
                  "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
                  "Access-Control-Allow-Credentials" -> "true"
                )
              }
            case None =>
              // normal lnurl-pay
              val hash = Sha256Digest(meta)

              lnd.addInvoice(hash, amount, 360).map { invoice =>
                val response = LnURLPayInvoice(invoice.invoice, None)
                Ok(Json.toJson(response)).withHeaders(
                  "Access-Control-Allow-Origin" -> "*",
                  "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
                  "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
                  "Access-Control-Allow-Credentials" -> "true"
                )
              }
          }
        case None =>
          val error =
            Json.obj("status" -> "ERROR", "reason" -> "no amount given")
          Future.successful(BadRequest(error))
      }
    }
  }

  def qrCode(
      string: String,
      widthStr: String,
      heightStr: String): Action[AnyContent] = {
    Action.async { _ =>
      val width = widthStr.toInt
      val height = heightStr.toInt

      val qrCodeWriter = new QRCodeWriter()
      val bitMatrix =
        qrCodeWriter.encode(string, BarcodeFormat.QR_CODE, width, height)
      val qrCodeImage =
        new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
      val graphics = qrCodeImage.createGraphics()
      graphics.setColor(Color.WHITE)
      graphics.fillRect(0, 0, width, height)
      graphics.setColor(Color.BLACK)
      for (x <- 0 until width) {
        for (y <- 0 until height) {
          if (bitMatrix.get(x, y)) {
            graphics.fillRect(x, y, 1, 1)
          }
        }
      }

      val byteArrayOutputStream = new ByteArrayOutputStream()
      ImageIO.write(qrCodeImage, "png", byteArrayOutputStream)
      val qrCodeByteArray = byteArrayOutputStream.toByteArray

      Future.successful(Ok(qrCodeByteArray).as("image/png"))
    }
  }
}
