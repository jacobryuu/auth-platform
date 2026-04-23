package controllers

import play.api.mvc._
import play.api.libs.json._
import infra.db.AuthRepository
import services.{AuthService, EmailService}
import domain.models._

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import java.time.LocalDateTime

case class PasswordResetRequestRequest(email: String)
case class PasswordResetConfirmRequest(email: String, token: String, newPassword: String)

object PasswordResetRequestRequest {
  implicit val reads: Reads[PasswordResetRequestRequest] = Json.reads[PasswordResetRequestRequest]
}

object PasswordResetConfirmRequest {
  implicit val reads: Reads[PasswordResetConfirmRequest] = Json.reads[PasswordResetConfirmRequest]
}

import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import actors.{AuditActor, RateLimitActor}
import scala.concurrent.duration._

@Singleton
class PasswordController @Inject() (
  val controllerComponents: ControllerComponents,
  authRepository: AuthRepository,
  emailService: EmailService,
  rateLimitActor: ActorRef[RateLimitActor.Command],
  auditActor: ActorRef[AuditActor.Command]
)(implicit ec: ExecutionContext, scheduler: Scheduler)
    extends BaseController {

  implicit val timeout: Timeout = 5.seconds

  // Password reset request
  def resetRequest: Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.body.validate[PasswordResetRequestRequest] match {
      case JsSuccess(resetReq, _) =>
        (rateLimitActor ? (RateLimitActor.CheckRateLimit(resetReq.email, _))).flatMap {
          case RateLimitActor.RateLimitExceeded =>
            Future.successful(
              TooManyRequests(Json.obj("message" -> "Too many password reset attempts. Please try again later."))
            )

          case RateLimitActor.RateLimitAllowed =>
            authRepository.findUserByEmail(resetReq.email).flatMap {
              case Some(user) =>
                // Generate reset token
                val token     = Random.alphanumeric.take(64).mkString
                val tokenHash = services.PasswordHasher.hashPassword(token)
                val expiresAt = LocalDateTime.now().plusHours(1) // Valid for 1 hour

                val recoveryToken = RecoveryToken(
                  userId = user.id.get,
                  tokenHash = tokenHash,
                  expiresAt = expiresAt,
                  tokenType = "password_reset"
                )

                authRepository.createRecoveryToken(recoveryToken).flatMap { _ =>
                  // Send email (stub for now)
                  emailService.sendPasswordResetEmail(resetReq.email, token)
                  auditActor ! AuditActor.LogEvent(
                    user.id.get,
                    action = "password_reset_request",
                    ipAddress = Some(request.remoteAddress)
                  )
                  Future.successful(Ok(Json.obj("message" -> "Password reset email sent")))
                }

              case None =>
                // Return success even if user not found (security best practice)
                Future.successful(Ok(Json.obj("message" -> "Password reset email sent")))
            }
        }

      case JsError(_) =>
        Future.successful(BadRequest(Json.obj("message" -> "Invalid JSON")))
    }
  }

  // Password reset confirm
  def resetConfirm: Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.body.validate[PasswordResetConfirmRequest] match {
      case JsSuccess(confirmReq, _) =>
        (rateLimitActor ? (RateLimitActor.CheckRateLimit(confirmReq.email, _))).flatMap {
          case RateLimitActor.RateLimitExceeded =>
            Future.successful(
              TooManyRequests(Json.obj("message" -> "Too many password reset attempts. Please try again later."))
            )

          case RateLimitActor.RateLimitAllowed =>
            authRepository.findUserByEmail(confirmReq.email).flatMap {
              case Some(user) =>
                authRepository.findRecoveryTokensByUserId(user.id.get).flatMap { tokens =>
                  val validTokenOpt = tokens.find { storedToken =>
                    storedToken.tokenType == "password_reset" &&
                    services.PasswordHasher.checkPassword(confirmReq.token, storedToken.tokenHash) &&
                    storedToken.expiresAt.isAfter(LocalDateTime.now())
                  }

                  validTokenOpt match {
                    case Some(recoveryToken) =>
                      // Update password
                      val newPasswordHash = services.PasswordHasher.hashPassword(confirmReq.newPassword)

                      authRepository.findCredentialByUserIdAndType(user.id.get, AuthType.Password).flatMap {
                        case Some(credential) =>
                          val updatedCredential = credential.copy(secret = Some(newPasswordHash))
                          authRepository.updateCredential(updatedCredential).flatMap { _ =>
                            // Delete all refresh tokens (force re-login)
                            authRepository.deleteRefreshTokensByUserId(user.id.get).flatMap { _ =>
                              // Delete recovery token
                              authRepository.deleteRecoveryToken(user.id.get, recoveryToken.tokenHash).map { _ =>
                                auditActor ! AuditActor.LogEvent(
                                  user.id.get,
                                  action = "password_reset_success",
                                  ipAddress = Some(request.remoteAddress)
                                )
                                Ok(Json.obj("message" -> "Password reset successfully"))
                              }
                            }
                          }
                        case None =>
                          Future.successful(BadRequest(Json.obj("message" -> "Password credential not found")))
                      }

                    case _ =>
                      Future.successful(BadRequest(Json.obj("message" -> "Invalid or expired token")))
                  }
                }

              case None =>
                Future.successful(BadRequest(Json.obj("message" -> "Invalid or expired token")))
            }
        }

      case JsError(_) =>
        Future.successful(BadRequest(Json.obj("message" -> "Invalid JSON")))
    }
  }
}
