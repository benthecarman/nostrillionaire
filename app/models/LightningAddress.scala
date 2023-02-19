package models

import java.net.URL

case class LightningAddress(value: String) {

  val (mailbox, domain): (LightningAddress.Mailbox, LightningAddress.Domain) =
    value match {
      case LightningAddress.validLnAddr(m, d) =>
        (LightningAddress.Mailbox(m), LightningAddress.Domain(d))
      case invalid =>
        throw new IllegalArgumentException(
          s"'$invalid' is not a valid lightning address")
    }

  lazy val lnurlp: URL = new URL(
    s"https://${domain.value}/.well-known/lnurlp/${mailbox.value}")

  override def equals(lnAddr: Any): Boolean = {
    lnAddr match {
      case p: LightningAddress => p.value.toLowerCase == this.value.toLowerCase
      case p: String           => p.toLowerCase == this.value.toLowerCase
      case _                   => false
    }
  }
}

object LightningAddress {

  import play.api.libs.json._

  implicit val LightningAddressReads: Reads[LightningAddress] = (js: JsValue) =>
    js.validate[String].flatMap {
      case s if LightningAddress.isValid(s) => JsSuccess(LightningAddress(s))
      case _ => JsError("not a valid lightning address")
    }

  implicit val LightningAddressWrites: Writes[LightningAddress] =
    (e: LightningAddress) => JsString(e.value)

  final private val validDomain =
    """^([a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*)$""".r

  final private[LightningAddress] val validLnAddr =
    """^([a-zA-Z0-9.!#$%&’'*+/=?^_`{|}~-]+)@([a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*)$""".r

  def isValid(lnAddr: String): Boolean = lnAddr match {
    case validLnAddr(_, _) => true
    case _                 => false
  }

  case class Mailbox private[LightningAddress] (value: String) {

    override def equals(lnAddr: Any): Boolean = {
      lnAddr match {
        case p: Mailbox => p.value.toLowerCase == this.value.toLowerCase
        case p: String  => p.toLowerCase == this.value.toLowerCase
        case _          => false
      }
    }
  }

  case class Domain(value: String) {

    value match {
      case LightningAddress.validDomain(_) => ()
      case invalidDomain =>
        throw new IllegalArgumentException(
          s"'$invalidDomain' is not a valid lightning address domain")
    }

    override def equals(lnAddr: Any): Boolean = {
      lnAddr match {
        case p: Domain => p.value.toLowerCase == this.value.toLowerCase
        case p: String => p.toLowerCase == this.value.toLowerCase
        case _         => false
      }
    }
  }
}
