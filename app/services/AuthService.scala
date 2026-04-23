package services

import domain.models._
import infra.db.AuthRepository
import com.yubico.webauthn.CredentialRepository

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import org.mindrot.jbcrypt.BCrypt // For password hashing

import javax.inject.Inject
import scala.concurrent.duration._
import scala.concurrent.Await

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

  // Verify and refresh token (only handles refresh token logic)
  def refreshAccessToken(userId: UserId, oldRefreshToken: String): Future[Either[String, String]] = {
    authRepository.findRefreshTokensByUserId(userId).flatMap { tokens =>
      val validTokenOpt = tokens.find { storedToken =>
        PasswordHasher.checkPassword(oldRefreshToken, storedToken.tokenHash) &&
        storedToken.expiresAt.isAfter(LocalDateTime.now())
      }

      validTokenOpt match {
        case Some(storedToken) =>
          // Invalidate old token and issue new one
          authRepository.deleteRefreshToken(storedToken.id.get).flatMap { _ =>
            generateRefreshToken(userId).map(newRefreshToken => Right(newRefreshToken))
          }
        case None => Future.successful(Left("Invalid or expired refresh token"))
      }
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

  // WebAuthn specific logic
  class WebAuthnService @Inject() (authRepository: AuthRepository)(implicit ec: ExecutionContext)
      extends CredentialRepository {
    import com.yubico.webauthn.data._
    import com.yubico.webauthn.{CredentialRepository, RegisteredCredential}
    import java.util.{Optional, Set => JSet}
    import scala.jdk.CollectionConverters._
    import scala.jdk.OptionConverters._

    override def getCredentialIdsForUsername(username: String): JSet[PublicKeyCredentialDescriptor] = {
      // Lookup user by email (username), then get their passkey credentials
      val result = for {
        userOpt <- authRepository.findUserByEmail(username)
        creds <- userOpt match {
          case Some(user) => authRepository.findCredentialsByUserId(user.id.get)
          case None       => Future.successful(Seq.empty)
        }
      } yield creds
        .filter(_.authType == AuthType.Passkey)
        .flatMap { c =>
          c.identifier.map { id =>
            PublicKeyCredentialDescriptor
              .builder()
              .id(ByteArray.fromBase64(id))
              .build()
          }
        }
        .toSet
        .asJava

      Await.result(result, 5.seconds)
    }

    override def getUserHandleForUsername(username: String): Optional[ByteArray] = {
      val result = authRepository.findUserByEmail(username).map {
        case Some(user) => Optional.of(ByteArray.fromBase64(user.id.get.value.toString))
        case None       => Optional.empty[ByteArray]()
      }
      Await.result(result, 5.seconds)
    }

    override def getUsernameForUserHandle(userHandle: ByteArray): Optional[String] = {
      val userId = UserId(userHandle.getBase64.toLong)
      val result = authRepository.findUserById(userId).map {
        case Some(user) => Optional.of(user.email)
        case None       => Optional.empty[String]()
      }
      Await.result(result, 5.seconds)
    }

    override def lookup(credentialId: ByteArray, userHandle: ByteArray): Optional[RegisteredCredential] =
      Optional.empty()

    override def lookupAll(credentialId: ByteArray): JSet[RegisteredCredential] =
      Set.empty[RegisteredCredential].asJava
  }
}
