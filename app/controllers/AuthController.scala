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
        (rateLimitActor ? (RateLimitActor.CheckRateLimit(registerReq.email, _))).flatMap {
          case RateLimitActor.RateLimitExceeded =>
            Future.successful(
              TooManyRequests(Json.obj("message" -> "Too many registration attempts. Please try again later."))
            )

          case RateLimitActor.RateLimitAllowed =>
            (authActor ? (Register(registerReq.email, registerReq.password, _))).map {
              case RegisterSuccess(user) =>
                auditActor ! AuditActor.LogEvent(
                  user.id.get,
                  action = "register",
                  ipAddress = Some(request.remoteAddress),
                  userAgent = request.headers.get("User-Agent")
                )
                Created(Json.obj("message" -> "User registered successfully", "userId" -> user.id.get.value))
              case RegisterFailure(reason) =>
                Conflict(Json.obj("message" -> reason))
            }
        }

      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj("message" -> JsError.toJson(errors))))
    }
  }

  // Refresh token endpoint
  def refresh: Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.body.validate[RefreshTokenRequest] match {
      case JsSuccess(refreshReq, _) =>
        // Need to extract userId from token in Authorization header, but here it's refresh-token
        // We'll assume the refresh token request might need a user ID or we should extract it from the old refresh token
        // In this implementation, we need the userId to be passed to AuthActor.
        // Let's assume for simplicity we can get it from somewhere or we need to change the API.
        // For now, I'll use a placeholder or assume the refresh token is used with a valid access token.
        val userIdOpt = request.headers.get("Authorization").flatMap { authHeader =>
          if (authHeader.startsWith("Bearer ")) {
            jwtHelper.getUserIdFromToken(authHeader.substring(7)).map(UserId(_))
          } else None
        }

        userIdOpt match {
          case Some(userId) =>
            (authActor ? (RefreshAccessToken(userId, refreshReq.refreshToken, _))).map {
              case RefreshAccessTokenSuccess(newAccessToken, newRefreshToken) =>
                auditActor ! AuditActor.LogEvent(
                  userId,
                  action = "refresh_token",
                  ipAddress = Some(request.remoteAddress),
                  userAgent = request.headers.get("User-Agent")
                )
                Ok(Json.obj("accessToken" -> newAccessToken, "refreshToken" -> newRefreshToken))
              case RefreshAccessTokenFailure(reason) =>
                Unauthorized(Json.obj("message" -> reason))
            }
          case None =>
            Future.successful(Unauthorized(Json.obj("message" -> "Missing or invalid authorization token")))
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
                case Some(userIdValue) =>
                  val userId = UserId(userIdValue)
                  authActor
                    .ask[AuthActor.Response](replyTo => Logout(userId, logoutReq.refreshToken, replyTo))
                    .map {
                      case LogoutSuccess =>
                        auditActor ! AuditActor.LogEvent(
                          userId,
                          action = "logout",
                          ipAddress = Some(request.remoteAddress),
                          userAgent = request.headers.get("User-Agent")
                        )
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
