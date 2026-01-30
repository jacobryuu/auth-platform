package utils

import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson}
import play.api.Configuration
import play.api.libs.json.{JsValue, Json}

import javax.inject._
import scala.util.{Failure, Success, Try}

@Singleton
class JwtHelper @Inject() (config: Configuration) {

  private val secretKey = config.get[String]("play.http.secret.key")
  private val algorithm = JwtAlgorithm.HS256

  def decodeToken(token: String): Try[JsValue] = {
    JwtJson.decode(token, secretKey, Seq(algorithm)) match {
      case Success(claim) =>
        Try(Json.parse(claim.content))
      case Failure(ex) =>
        Failure(ex)
    }
  }

  def getUserIdFromToken(token: String): Option[Long] =
    decodeToken(token).toOption.flatMap(json => (json \ "userId").asOpt[Long])

  def validateToken(token: String): Boolean =
    JwtJson.isValid(token, secretKey, Seq(algorithm))
}
