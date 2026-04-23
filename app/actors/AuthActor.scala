package actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import services.AuthService
import domain.models.{User, UserId}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object AuthActor {

  // Actor Protocol
  sealed trait Command

  case class Login(email: String, password: String, replyTo: ActorRef[LoginResponse])       extends Command
  case class Register(email: String, password: String, replyTo: ActorRef[RegisterResponse]) extends Command
  case class RefreshAccessToken(userId: UserId, refreshToken: String, replyTo: ActorRef[RefreshAccessTokenResponse])
      extends Command
  case class Logout(userId: UserId, refreshToken: String, replyTo: ActorRef[Response]) extends Command

  // Responses
  sealed trait Response
  sealed trait LoginResponse                                                     extends Response
  case class LoginSuccess(user: User, accessToken: String, refreshToken: String) extends LoginResponse
  case class LoginFailure(reason: String)                                        extends LoginResponse

  sealed trait RegisterResponse              extends Response
  case class RegisterSuccess(user: User)     extends RegisterResponse
  case class RegisterFailure(reason: String) extends RegisterResponse

  sealed trait RefreshAccessTokenResponse                                         extends Response
  case class RefreshAccessTokenSuccess(accessToken: String, refreshToken: String) extends RefreshAccessTokenResponse
  case class RefreshAccessTokenFailure(reason: String)                            extends RefreshAccessTokenResponse

  case object LogoutSuccess                extends Response
  case class LogoutFailure(reason: String) extends Response

  // Internal messages for async operations
  private final case class AuthenticateUserCompleted(
    result: Either[String, User],
    replyTo: ActorRef[LoginResponse],
    userId: UserId
  ) extends Command
  private final case class RegisterUserCompleted(result: Either[String, User], replyTo: ActorRef[RegisterResponse])
      extends Command
  private final case class IssueAccessTokenCompleted(
    result: TokenActor.IssueAccessTokenResponse,
    replyTo: ActorRef[LoginResponse],
    user: User,
    userId: UserId
  ) extends Command
  private final case class GenerateRefreshTokenCompleted(
    result: String,
    replyTo: ActorRef[LoginResponse],
    user: User,
    accessToken: String
  ) extends Command
  private final case class RefreshAccessTokenCompleted(
    result: Either[String, String],
    userId: UserId,
    replyTo: ActorRef[RefreshAccessTokenResponse]
  ) extends Command
  private final case class RefreshTokenIssued(
    result: TokenActor.IssueAccessTokenResponse,
    userId: UserId,
    newRefreshToken: String,
    replyTo: ActorRef[RefreshAccessTokenResponse]
  ) extends Command
  private final case class LogoutCompleted(result: Either[String, Unit], replyTo: ActorRef[Response]) extends Command

  def apply(authService: AuthService, tokenActor: ActorRef[TokenActor.Command])(implicit
    ec: ExecutionContext
  ): Behavior[Command] = Behaviors.receive { (context, message) =>
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import org.apache.pekko.util.Timeout
    import scala.concurrent.duration._

    implicit val timeout: Timeout = 3.seconds
    implicit val scheduler        = context.system.scheduler
    message match {
      case Login(email, password, replyTo) =>
        context.log.info(s"AuthActor: Received Login request for email: $email")
        context.pipeToSelf(authService.authenticateUser(email, password)) {
          case Success(result) =>
            AuthenticateUserCompleted(
              result,
              replyTo,
              result
                .map(_.id.getOrElse(throw new Exception("User ID not found after authentication")))
                .getOrElse(UserId(0))
            )
          case Failure(ex) => AuthenticateUserCompleted(Left(ex.getMessage), replyTo, UserId(0))
        }
        Behaviors.same

      case AuthenticateUserCompleted(Left(reason), replyTo, _) =>
        replyTo ! LoginFailure(reason)
        Behaviors.same

      case AuthenticateUserCompleted(Right(user), replyTo, userId) =>
        context.log.info(s"AuthActor: User authenticated successfully: ${user.email}")
        context.pipeToSelf(
          tokenActor.ask[TokenActor.IssueAccessTokenResponse](TokenActor.IssueAccessToken(userId, _))
        ) {
          case Success(res) => IssueAccessTokenCompleted(res, replyTo, user, userId)
          case Failure(ex) =>
            IssueAccessTokenCompleted(TokenActor.IssueAccessTokenFailure(ex.getMessage), replyTo, user, userId)
        }
        Behaviors.same

      case IssueAccessTokenCompleted(TokenActor.IssueAccessTokenSuccess(accessToken), replyTo, user, userId) =>
        context.pipeToSelf(authService.generateRefreshToken(userId)) {
          case Success(refreshToken) => GenerateRefreshTokenCompleted(refreshToken, replyTo, user, accessToken)
          case Failure(ex) =>
            GenerateRefreshTokenCompleted(ex.getMessage, replyTo, user, accessToken) // Simplified error handling
        }
        Behaviors.same

      case IssueAccessTokenCompleted(TokenActor.IssueAccessTokenFailure(reason), replyTo, _, _) =>
        replyTo ! LoginFailure(s"Token issuance failed: $reason")
        Behaviors.same

      case GenerateRefreshTokenCompleted(refreshToken, replyTo, user, accessToken) =>
        replyTo ! LoginSuccess(user, accessToken, refreshToken)
        Behaviors.same

      case Register(email, password, replyTo) =>
        context.log.info(s"AuthActor: Received Register request for email: $email")
        context.pipeToSelf(authService.registerUser(email, password)) {
          case Success(result) => RegisterUserCompleted(result, replyTo)
          case Failure(ex)     => RegisterUserCompleted(Left(ex.getMessage), replyTo)
        }
        Behaviors.same

      case RegisterUserCompleted(Left(reason), replyTo) =>
        replyTo ! RegisterFailure(reason)
        Behaviors.same

      case RegisterUserCompleted(Right(user), replyTo) =>
        context.log.info(s"AuthActor: User registered successfully: ${user.email}")
        replyTo ! RegisterSuccess(user)
        Behaviors.same

      case RefreshAccessToken(userId, refreshToken, replyTo) =>
        context.log.info(s"AuthActor: Received RefreshAccessToken request for user ID: ${userId.value}")
        context.pipeToSelf(authService.refreshAccessToken(userId, refreshToken)) {
          case Success(result) => RefreshAccessTokenCompleted(result, userId, replyTo)
          case Failure(ex)     => RefreshAccessTokenCompleted(Left(ex.getMessage), userId, replyTo)
        }
        Behaviors.same

      case RefreshAccessTokenCompleted(Left(reason), _, replyTo) =>
        replyTo ! RefreshAccessTokenFailure(reason)
        Behaviors.same

      case RefreshAccessTokenCompleted(Right(newRefreshToken), userId, replyTo) =>
        context.pipeToSelf(
          tokenActor.ask[TokenActor.IssueAccessTokenResponse](TokenActor.IssueAccessToken(userId, _))
        ) {
          case Success(res) => RefreshTokenIssued(res, userId, newRefreshToken, replyTo)
          case Failure(ex) =>
            RefreshTokenIssued(TokenActor.IssueAccessTokenFailure(ex.getMessage), userId, newRefreshToken, replyTo)
        }
        Behaviors.same

      case RefreshTokenIssued(TokenActor.IssueAccessTokenSuccess(accessToken), _, newRefreshToken, replyTo) =>
        replyTo ! RefreshAccessTokenSuccess(accessToken, newRefreshToken)
        Behaviors.same

      case RefreshTokenIssued(TokenActor.IssueAccessTokenFailure(reason), _, _, replyTo) =>
        replyTo ! RefreshAccessTokenFailure(s"Token issuance failed: $reason")
        Behaviors.same

      case Logout(userId, refreshToken, replyTo) =>
        context.log.info(s"AuthActor: Received Logout request for user ID: ${userId.value}")
        context.pipeToSelf(authService.logout(userId, refreshToken)) {
          case Success(result) => LogoutCompleted(result, replyTo)
          case Failure(ex)     => LogoutCompleted(Left(ex.getMessage), replyTo)
        }
        Behaviors.same

      case LogoutCompleted(Left(reason), replyTo) =>
        replyTo ! LogoutFailure(reason)
        Behaviors.same

      case LogoutCompleted(Right(_), replyTo) =>
        context.log.info("AuthActor: User logged out successfully")
        replyTo ! LogoutSuccess
        Behaviors.same
    }
  }
}
