package infra.db

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.db.slick.DatabaseConfigProvider
import play.api.test.{DefaultAwaitTimeout, Injecting}
import slick.jdbc.JdbcProfile
import slick.jdbc.H2Profile.api._
import domain.models._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import java.time.LocalDateTime
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

class AuthRepositorySpec
    extends PlaySpec
    with GuiceOneAppPerSuite
    with Injecting
    with DefaultAwaitTimeout
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  override def fakeApplication(): Application = {
    new GuiceApplicationBuilder()
      .configure(
        "slick.dbs.default.profile"     -> "slick.jdbc.H2Profile$",
        "slick.dbs.default.db.driver"   -> "org.h2.Driver",
        "slick.dbs.default.db.url"      -> "jdbc:h2:mem:auth_test;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE",
        "slick.dbs.default.db.user"     -> "sa",
        "slick.dbs.default.db.password" -> "",
        "play.evolutions.enabled"       -> "false"
      )
      .build()
  }

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val dbConfigProvider              = app.injector.instanceOf[DatabaseConfigProvider]
  val authRepository                = app.injector.instanceOf[AuthRepository]
  val profile                       = dbConfigProvider.get[JdbcProfile].profile
  import profile.api._
  val db = dbConfigProvider.get[JdbcProfile].db

  val authTable = new AuthTable(profile)
  import authTable.*

  // Helper to run Future
  def await[T](f: Future[T]): T =
    Await.result(f, 10.seconds)

  // Helper to run DB actions
  def awaitDB[T](action: DBIOAction[T, NoStream, Effect.All]): T =
    Await.result(db.run(action), 10.seconds)

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Create schema
    awaitDB(
      DBIO.seq(
        users.schema.createIfNotExists,
        credentials.schema.createIfNotExists,
        refreshTokens.schema.createIfNotExists,
        loginHistorys.schema.createIfNotExists,
        recoveryTokens.schema.createIfNotExists
      )
    )
  }

  // Clear tables before each test
  override def beforeEach(): Unit = {
    super.beforeEach()
    awaitDB(
      DBIO.seq(
        loginHistorys.delete,
        refreshTokens.delete,
        recoveryTokens.delete,
        credentials.delete,
        users.delete
      )
    )
  }

  "AuthRepository" should {
    "create and find a user" in {
      val user        = User(email = "test@example.com", status = "active")
      val createdUser = await(authRepository.createUser(user))
      createdUser.id must not be None
      createdUser.email mustEqual "test@example.com"

      val foundUser = await(authRepository.findUserById(createdUser.id.get))
      foundUser must not be None
      foundUser.get.email mustEqual "test@example.com"
    }

    "create and find a credential" in {
      val user        = User(email = "test2@example.com", status = "active")
      val createdUser = await(authRepository.createUser(user))

      val credential = Credential(
        userId = createdUser.id.get,
        authType = AuthType.Password,
        identifier = Some("test2@example.com"),
        secret = Some("hashed_password"),
        meta = None
      )
      val createdCredential = await(authRepository.createCredential(credential))
      createdCredential.id must not be None
      createdCredential.userId mustEqual createdUser.id.get

      val foundCredential = await(authRepository.findCredentialById(createdCredential.id.get))
      foundCredential must not be None
      foundCredential.get.authType mustEqual AuthType.Password
    }

    "find credentials by user ID" in {
      val user        = User(email = "test3@example.com", status = "active")
      val createdUser = await(authRepository.createUser(user))

      val cred1 = Credential(
        userId = createdUser.id.get,
        authType = AuthType.Password,
        identifier = Some("email"),
        secret = Some("secret1")
      )
      val cred2 = Credential(
        userId = createdUser.id.get,
        authType = AuthType.Passkey,
        identifier = Some("passkey"),
        secret = Some("secret2")
      )
      await(authRepository.createCredential(cred1))
      await(authRepository.createCredential(cred2))

      val credentials = await(authRepository.findCredentialsByUserId(createdUser.id.get))
      credentials.size mustEqual 2
      credentials.map(_.authType) must contain allOf (AuthType.Password, AuthType.Passkey)
    }

    "create and find a refresh token" in {
      val user        = User(email = "test4@example.com", status = "active")
      val createdUser = await(authRepository.createUser(user))

      val token = RefreshToken(
        userId = createdUser.id.get,
        tokenHash = "some_hash",
        expiresAt = LocalDateTime.now().plusDays(1)
      )
      val createdToken = await(authRepository.createRefreshToken(token))
      createdToken.id must not be None

      val foundToken = await(authRepository.findRefreshToken(createdUser.id.get, "some_hash"))
      foundToken must not be None
      foundToken.get.tokenHash mustEqual "some_hash"
    }

    "create and find login history" in {
      val user        = User(email = "test5@example.com", status = "active")
      val createdUser = await(authRepository.createUser(user))

      val history = LoginHistory(
        userId = createdUser.id.get,
        ipAddress = Some("127.0.0.1"),
        userAgent = Some("Mozilla"),
        success = true
      )
      val createdHistory = await(authRepository.createLoginHistory(history))
      createdHistory.id must not be None

      val histories = await(authRepository.findLoginHistoryByUserId(createdUser.id.get))
      histories.size mustEqual 1
      histories.head.success mustEqual true
    }

    "create and find a recovery token" in {
      val user        = User(email = "test6@example.com", status = "active")
      val createdUser = await(authRepository.createUser(user))

      val recoveryToken = RecoveryToken(
        userId = createdUser.id.get,
        tokenHash = "recovery_hash",
        expiresAt = LocalDateTime.now().plusHours(1)
      )
      val createdCount = await(authRepository.createRecoveryToken(recoveryToken))
      createdCount mustEqual 1

      val foundRecoveryToken = await(authRepository.findRecoveryToken(createdUser.id.get, "recovery_hash"))
      foundRecoveryToken must not be None
      foundRecoveryToken.get.tokenHash mustEqual "recovery_hash"
    }
  }
}
