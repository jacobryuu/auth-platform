package controllers

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Injecting}
import domain.models._
import infra.db.AuthRepository
import services.EmailService
import scala.concurrent.ExecutionContext
import scala.concurrent.Await
import scala.concurrent.duration._

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import org.scalatest.BeforeAndAfterAll
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile
import infra.db.AuthTable

class PasswordControllerSpec extends PlaySpec with GuiceOneAppPerSuite with Injecting with BeforeAndAfterAll {

  override def fakeApplication(): Application = {
    new GuiceApplicationBuilder()
      .configure(
        "slick.dbs.default.profile"     -> "slick.jdbc.H2Profile$",
        "slick.dbs.default.db.driver"   -> "org.h2.Driver",
        "slick.dbs.default.db.url"      -> "jdbc:h2:mem:auth_controller_test;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE",
        "slick.dbs.default.db.user"     -> "sa",
        "slick.dbs.default.db.password" -> "",
        "play.evolutions.enabled"       -> "false"
      )
      .build()
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    val dbConfigProvider = app.injector.instanceOf[DatabaseConfigProvider]
    val profile          = dbConfigProvider.get[JdbcProfile].profile
    val db               = dbConfigProvider.get[JdbcProfile].db
    import profile.api._

    val authTable = new AuthTable(profile)
    import authTable._

    Await.result(
      db.run(
        DBIO.seq(
          users.schema.createIfNotExists,
          credentials.schema.createIfNotExists,
          refreshTokens.schema.createIfNotExists,
          loginHistorys.schema.createIfNotExists,
          recoveryTokens.schema.createIfNotExists
        )
      ),
      10.seconds
    )
  }

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val authRepository                = app.injector.instanceOf[AuthRepository]
  val emailService                  = app.injector.instanceOf[EmailService]

  "PasswordController" should {

    "successfully reset password" in {
      val email = "test@example.com"
      // 1. Create a user
      val user        = User(email = email, status = "active")
      val createdUser = Await.result(authRepository.createUser(user), 5.seconds)

      // 2. Create a credential
      val credential = Credential(
        userId = createdUser.id.get,
        authType = AuthType.Password,
        identifier = Some(email),
        secret = Some(services.PasswordHasher.hashPassword("oldpassword"))
      )
      Await.result(authRepository.createCredential(credential), 5.seconds)

      // 3. Request password reset
      val resetRequest = FakeRequest(POST, "/api/password/reset-request")
        .withJsonBody(Json.obj("email" -> email))
      val resetResponse = route(app, resetRequest).get
      status(resetResponse) mustBe OK

      // 4. Get the token from EmailService (we'll update EmailService to store it for testing)
      val token = emailService.getLastToken(email).getOrElse(fail("Token not found in EmailService"))

      // 5. Confirm password reset
      val confirmRequest = FakeRequest(POST, "/api/password/reset-confirm")
        .withJsonBody(
          Json.obj(
            "email"       -> email,
            "token"       -> token,
            "newPassword" -> "newpassword"
          )
        )
      val confirmResponse = route(app, confirmRequest).get
      status(confirmResponse) mustBe OK
      contentAsJson(confirmResponse) mustEqual Json.obj("message" -> "Password reset successfully")

      // 6. Verify password is changed
      val updatedCred =
        Await.result(authRepository.findCredentialByUserIdAndType(createdUser.id.get, AuthType.Password), 5.seconds).get
      services.PasswordHasher.checkPassword("newpassword", updatedCred.secret.get) mustBe true
    }
  }
}
