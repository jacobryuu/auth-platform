package controllers

import play.api.mvc._
import play.api.libs.json._

import javax.inject._
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import actors.AuthActor._
import actors.{AuthActor, AuditActor, RateLimitActor, TokenActor}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import domain.models.UserId
import play.api.Configuration
import java.time.Clock
import utils.JwtHelper

case class LoginRequest(email: String, password: String)
case class RegisterRequest(email: String, password: String)
case class RefreshTokenRequest(refreshToken: String)
case class LogoutRequest(refreshToken: String)

object LoginRequest {
  implicit val reads: Reads[LoginRequest] = Json.reads[LoginRequest]
}

object RegisterRequest {
  implicit val reads: Reads[RegisterRequest] = Json.reads[RegisterRequest]
}

object RefreshTokenRequest {
  implicit val reads: Reads[RefreshTokenRequest] = Json.reads[RefreshTokenRequest]
}

object LogoutRequest {
  implicit val reads: Reads[LogoutRequest] = Json.reads[LogoutRequest]
}

@Singleton
class AuthController @Inject() (
  val controllerComponents: ControllerComponents,
  authActor: ActorRef[AuthActor.Command],
  auditActor: ActorRef[AuditActor.Command],
  rateLimitActor: ActorRef[RateLimitActor.Command],
  tokenActor: ActorRef[TokenActor.Command],
  configuration: Configuration,
  jwtHelper: JwtHelper
)(implicit ec: ExecutionContext, scheduler: Scheduler, clock: Clock)
    extends BaseController {

  implicit val timeout: Timeout = 5.seconds

  // Login endpoint
  def login: Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.body.validate[LoginRequest] match {
      case JsSuccess(loginReq, _) =>
        (rateLimitActor ? (RateLimitActor.CheckRateLimit(loginReq.email, _))).flatMap {
          case RateLimitActor.RateLimitExceeded =>
            Future.successful(
              TooManyRequests(Json.obj("message" -> "Too many login attempts. Please try again later."))
            )

          case RateLimitActor.RateLimitAllowed =>
            (authActor ? (Login(loginReq.email, loginReq.password, _))).flatMap {
              case LoginSuccess(user, accessToken, refreshToken) =>
                auditActor ! AuditActor.LogLoginAttempt(
                  user.id.get,
                  ipAddress = Some(request.remoteAddress),
                  userAgent = request.headers.get("User-Agent"),
                  success = true
                )
                Future.successful(Ok(Json.obj("accessToken" -> accessToken, "refreshToken" -> refreshToken)))

              case LoginFailure(reason) =>
                Future.successful(Unauthorized(Json.obj("message" -> reason)))
            }
        }

      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj("message" -> JsError.toJson(errors))))
    }
  }

  // Register endpoint
  def register: Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.body.validate[RegisterRequest] match {
      case JsSuccess(registerReq, _) =>
        (authActor ? (Register(registerReq.email, registerReq.password, _))).map {
          case RegisterSuccess(user) =>
            Created(Json.obj("message" -> "User registered successfully", "userId" -> user.id.get.value))
          case RegisterFailure(reason) =>
            Conflict(Json.obj("message" -> reason))
        }

      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj("message" -> JsError.toJson(errors))))
    }
  }

  // Refresh token endpoint
  def refresh: Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.body.validate[RefreshTokenRequest] match {
      case JsSuccess(refreshReq, _) =>
        val dummyUserId = UserId(1) // Placeholder - Replace with actual user ID extraction

        (authActor ? (RefreshAccessToken(dummyUserId, refreshReq.refreshToken, _))).map {
          case RefreshAccessTokenSuccess(newAccessToken, newRefreshToken) =>
            Ok(Json.obj("accessToken" -> newAccessToken, "refreshToken" -> newRefreshToken))
          case RefreshAccessTokenFailure(reason) =>
            Unauthorized(Json.obj("message" -> reason))
        }

      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj("message" -> JsError.toJson(errors))))
    }
  }

  // Logout endpoint
  def logout: Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.headers.get("Authorization") match {
      case Some(authHeader) if authHeader.startsWith("Bearer ") =>
        val token = authHeader.substring(7)

        if (!jwtHelper.validateToken(token)) {
          Future.successful(Unauthorized(Json.obj("message" -> "Invalid or expired token")))
        } else {
          request.body.validate[LogoutRequest] match {
            case JsSuccess(logoutReq, _) =>
              jwtHelper.getUserIdFromToken(token) match {
                case Some(userId) =>
                  authActor
                    .ask[AuthActor.Response](replyTo => Logout(UserId(userId), logoutReq.refreshToken, replyTo))
                    .map {
                      case LogoutSuccess =>
                        Ok(Json.obj("message" -> "Logged out successfully"))
                      case LogoutFailure(reason) =>
                        BadRequest(Json.obj("message" -> reason))
                      case _ =>
                        InternalServerError(Json.obj("message" -> "Unknown error"))
                    }
                case None =>
                  Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
              }
            case JsError(_) =>
              Future.successful(BadRequest(Json.obj("message" -> "Invalid JSON")))
          }
        }

      case _ =>
        Future.successful(Unauthorized(Json.obj("message" -> "Missing authorization header")))
    }
  }
}
