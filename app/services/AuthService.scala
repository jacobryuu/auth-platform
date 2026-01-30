package services

import domain.models._
import infra.db.AuthRepository
import com.yubico.webauthn.CredentialRepository

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import org.mindrot.jbcrypt.BCrypt // For password hashing

import javax.inject.Inject

// Define a simple password hashing utility for now
object PasswordHasher {
  def hashPassword(password: String): String                            = BCrypt.hashpw(password, BCrypt.gensalt())
  def checkPassword(plaintext: String, hashedPassword: String): Boolean = BCrypt.checkpw(plaintext, hashedPassword)
}

class AuthService @Inject() (authRepository: AuthRepository)(implicit ec: ExecutionContext) {

  // User registration
  def registerUser(email: String, password: String): Future[Either[String, User]] = {
    authRepository.findUserByEmail(email).flatMap {
      case Some(_) => Future.successful(Left("User with this email already exists"))
      case None =>
        val hashedPassword = PasswordHasher.hashPassword(password)
        val newUser        = User(email = email, status = "active")
        authRepository.createUser(newUser).flatMap { createdUser =>
          createdUser.id match {
            case Some(userId) =>
              val credential = Credential(
                userId = userId,
                authType = AuthType.Password,
                identifier = Some(email), // For password, identifier can be email
                secret = Some(hashedPassword),
                meta = None
              )
              authRepository.createCredential(credential).map(_ => Right(createdUser))
            case None => Future.successful(Left("Failed to create user"))
          }
        }
    }
  }

  // User authentication
  def authenticateUser(email: String, password: String): Future[Either[String, User]] = {
    authRepository.findUserByEmail(email).flatMap {
      case None => Future.successful(Left("Invalid credentials"))
      case Some(user) =>
        authRepository.findCredentialByUserIdAndType(user.id.get, AuthType.Password).flatMap {
          case None => Future.successful(Left("Invalid credentials"))
          case Some(credential) =>
            credential.secret match {
              case Some(hashedPassword) if PasswordHasher.checkPassword(password, hashedPassword) =>
                // Authentication successful, log history
                authRepository.createLoginHistory(
                  LoginHistory(userId = user.id.get, ipAddress = None, userAgent = None, success = true)
                )
                Future.successful(Right(user))
              case _ =>
                // Authentication failed, log history and handle failed attempts
                authRepository.createLoginHistory(
                  LoginHistory(userId = user.id.get, ipAddress = None, userAgent = None, success = false)
                )
                Future.successful(Left("Invalid credentials"))
            }
        }
    }
  }

  // Placeholder for JWT token generation (will be implemented later)
  def generateAccessToken(userId: UserId): String =
    "dummy_access_token_for_" + userId.value

  def generateRefreshToken(userId: UserId): Future[String] = {
    val token            = Random.alphanumeric.take(64).mkString
    val expiresAt        = LocalDateTime.now().plusDays(30)   // Refresh token valid for 30 days
    val refreshTokenHash = PasswordHasher.hashPassword(token) // Hash refresh token for storage
    val newRefreshToken  = RefreshToken(userId = userId, tokenHash = refreshTokenHash, expiresAt = expiresAt)
    authRepository.createRefreshToken(newRefreshToken).map(_ => token) // Return original token
  }

  // Verify and refresh token (simplified)
  def refreshAccessToken(userId: UserId, oldRefreshToken: String): Future[Either[String, (String, String)]] = {
    authRepository.findRefreshToken(userId, PasswordHasher.hashPassword(oldRefreshToken)).flatMap {
      case Some(storedToken) if storedToken.expiresAt.isAfter(LocalDateTime.now()) =>
        // Invalidate old token and issue new ones
        authRepository.deleteRefreshToken(storedToken.id.get).flatMap { _ =>
          val newAccessToken = generateAccessToken(userId)
          generateRefreshToken(userId).map(newRefreshToken => Right((newAccessToken, newRefreshToken)))
        }
      case _ => Future.successful(Left("Invalid or expired refresh token"))
    }
  }

  // Logout - invalidate refresh token
  def logout(userId: UserId, refreshToken: String): Future[Either[String, Unit]] = {
    authRepository.deleteRefreshTokensByUserId(userId).flatMap { deleted =>
      if (deleted > 0) {
        // Log logout in login_history
        authRepository
          .createLoginHistory(
            LoginHistory(userId = userId, ipAddress = None, userAgent = None, success = true, action = Some("logout"))
          )
          .map(_ => Right(()))
      } else {
        Future.successful(Left("No active session found"))
      }
    }
  }

  // WebAuthn specific logic will be added here
  // For now, this is a placeholder
  abstract class WebAuthnService extends CredentialRepository {
    // TODO: implement methods in CredentialRepository
  }
}
