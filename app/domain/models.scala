package domain

import java.time.LocalDateTime

object models {

  // 7.1 認証方式設計 - 抽象モデル
  sealed trait AuthType
  object AuthType {
    case object Password   extends AuthType
    case object Passkey    extends AuthType
    case object Totp       extends AuthType
    case object Sms        extends AuthType
    case object EmailOtp   extends AuthType
    case object BackupCode extends AuthType

    def fromString(s: String): Option[AuthType] = s match {
      case "PASSWORD"    => Some(Password)
      case "PASSKEY"     => Some(Passkey)
      case "TOTP"        => Some(Totp)
      case "SMS"         => Some(Sms)
      case "EMAIL_OTP"   => Some(EmailOtp)
      case "BACKUP_CODE" => Some(BackupCode)
      case _             => None
    }

    def toString(authType: AuthType): String = authType match {
      case Password   => "PASSWORD"
      case Passkey    => "PASSKEY"
      case Totp       => "TOTP"
      case Sms        => "SMS"
      case EmailOtp   => "EMAIL_OTP"
      case BackupCode => "BACKUP_CODE"
    }
  }

  case class UserId(value: Long) extends AnyVal

  // 6.3 credentials（中核テーブル）
  case class Credential(
    id: Option[Long] = None,
    userId: UserId,
    authType: AuthType,
    identifier: Option[String],
    secret: Option[String],
    meta: Option[String], // JSONB will be stored as String here
    enabled: Boolean = true,
    priority: Int = 0,
    failedCount: Int = 0,
    lockedUntil: Option[LocalDateTime] = None,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None
  )

  // 6.2 users
  case class User(
    id: Option[UserId] = None,
    email: String,
    status: String,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None
  )

  // 6.4 refresh_tokens
  case class RefreshToken(
    id: Option[Long] = None,
    userId: UserId,
    tokenHash: String,
    expiresAt: LocalDateTime,
    createdAt: Option[LocalDateTime] = None
  )

  // 6.5 login_history
  case class LoginHistory(
    id: Option[Long] = None,
    userId: UserId,
    ipAddress: Option[String],
    userAgent: Option[String],
    success: Boolean,
    action: Option[String] = None,
    createdAt: Option[LocalDateTime] = None
  )

  // 6.6 recovery_tokens
  case class RecoveryToken(
    userId: UserId,
    tokenHash: String,
    expiresAt: LocalDateTime,
    tokenType: String = "password_reset"
  )
}
