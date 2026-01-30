package actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import domain.models.UserId
import pdi.jwt.{JwtAlgorithm, JwtClaim}
import pdi.jwt.JwtJson
import play.api.libs.json.{JsValue, Json}
import play.api.Configuration

import java.time.Clock
import scala.util.{Failure, Success, Try}

object TokenActor {

  // Actor Protocol
  sealed trait Command

  case class IssueAccessToken(userId: UserId, replyTo: ActorRef[IssueAccessTokenResponse])      extends Command
  case class ValidateAccessToken(token: String, replyTo: ActorRef[ValidateAccessTokenResponse]) extends Command

  // Responses
  sealed trait IssueAccessTokenResponse
  case class IssueAccessTokenSuccess(accessToken: String) extends IssueAccessTokenResponse
  case class IssueAccessTokenFailure(reason: String)      extends IssueAccessTokenResponse

  sealed trait ValidateAccessTokenResponse
  case class ValidateAccessTokenSuccess(userId: UserId) extends ValidateAccessTokenResponse
  case class ValidateAccessTokenFailure(reason: String) extends ValidateAccessTokenResponse

  def apply(configuration: Configuration)(implicit clock: Clock): Behavior[Command] = Behaviors.receive {
    (context, message) =>
      val secretKey = configuration.get[String]("play.http.secret.key")

      message match {
        case IssueAccessToken(userId, replyTo) =>
          context.log.info(s"TokenActor: Issuing access token for user ID: ${userId.value}")
          val claimsJson = Json.obj("userId" -> userId.value)
          val claim      = JwtClaim(content = Json.stringify(claimsJson))
          val token      = JwtJson.encode(claim, secretKey, JwtAlgorithm.HS256)
          replyTo ! IssueAccessTokenSuccess(token)
          Behaviors.same

        case ValidateAccessToken(token, replyTo) =>
          context.log.info("TokenActor: Validating access token.")
          JwtJson.decodeJson(token, secretKey, Seq(JwtAlgorithm.HS256)) match {
            case Success(decoded) =>
              // `\` は JsLookupResult を返すので、そのまま asOpt する
              (decoded \ "userId").asOpt[Long] match {
                case Some(userIdValue) =>
                  replyTo ! ValidateAccessTokenSuccess(UserId(userIdValue))
                case None =>
                  replyTo ! ValidateAccessTokenFailure("Invalid claims in token: userId missing or invalid.")
              }

            case Failure(ex) =>
              context.log.warn(s"TokenActor: Token validation failed: ${ex.getMessage}")
              replyTo ! ValidateAccessTokenFailure("Invalid or expired token.")
          }
          Behaviors.same
      }
  }
}
