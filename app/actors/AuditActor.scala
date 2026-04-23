package actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import domain.models.{LoginHistory, UserId}
import infra.db.AuthRepository

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object AuditActor {

  // Actor Protocol
  sealed trait Command

  case class LogLoginAttempt(
    userId: UserId,
    ipAddress: Option[String],
    userAgent: Option[String],
    success: Boolean
  ) extends Command

  case class LogEvent(
    userId: UserId,
    action: String,
    details: Option[String] = None,
    ipAddress: Option[String] = None,
    userAgent: Option[String] = None
  ) extends Command

  // Internal message for pipeToSelf completion
  private case class AuditLogCompleted(message: String) extends Command

  def apply(authRepository: AuthRepository)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case LogLoginAttempt(userId, ipAddress, userAgent, success) =>
          context.log.info(s"AuditActor: Logging login attempt for user ID: ${userId.value}, success: $success")
          val loginHistory = LoginHistory(
            userId = userId,
            ipAddress = ipAddress,
            userAgent = userAgent,
            success = success,
            action = Some("login")
          )
          context.pipeToSelf(authRepository.createLoginHistory(loginHistory)) {
            case Success(_)  => AuditLogCompleted("Login history recorded successfully.")
            case Failure(ex) => AuditLogCompleted(s"Failed to record login history: ${ex.getMessage}")
          }
          Behaviors.same

        case LogEvent(userId, action, details, ipAddress, userAgent) =>
          context.log.info(s"AuditActor: Logging event '$action' for user ID: ${userId.value}")
          val loginHistory = LoginHistory(
            userId = userId,
            ipAddress = ipAddress,
            userAgent = userAgent,
            success = true,
            action = Some(action)
          )
          context.pipeToSelf(authRepository.createLoginHistory(loginHistory)) {
            case Success(_)  => AuditLogCompleted(s"Event '$action' recorded successfully.")
            case Failure(ex) => AuditLogCompleted(s"Failed to record event '$action': ${ex.getMessage}")
          }
          Behaviors.same

        case AuditLogCompleted(msg) =>
          context.log.debug(s"AuditActor: $msg")
          Behaviors.same
      }
    }
}
