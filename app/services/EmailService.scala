package services

import javax.inject._
import scala.concurrent.Future
import play.api.Logger

@Singleton
class EmailService @Inject() () {

  private val logger     = Logger(this.getClass)
  private var lastTokens = Map.empty[String, String]

  def getLastToken(email: String): Option[String] = lastTokens.get(email)

  def sendPasswordResetEmail(email: String, token: String): Future[Unit] = {
    // TODO: Implement actual email sending (SendGrid, AWS SES, etc.)
    logger.info(s"[EMAIL STUB] Password reset email would be sent to: $email")
    logger.info(s"[EMAIL STUB] Reset link: http://localhost:9001/password-reset?token=$token")
    lastTokens = lastTokens + (email -> token)
    Future.successful(())
  }

  def sendVerificationEmail(email: String, token: String): Future[Unit] = {
    // TODO: Implement actual email sending
    logger.info(s"[EMAIL STUB] Verification email would be sent to: $email")
    logger.info(s"[EMAIL STUB] Verification link: http://localhost:9001/verify-email?token=$token")
    Future.successful(())
  }
}
