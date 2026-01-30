package infra.db

import domain.models._
import slick.jdbc.JdbcProfile
import java.time.LocalDateTime

class AuthTable(val profile: JdbcProfile) {
  import profile.api._

  // Custom Mappings
  implicit val authTypeColumnType: BaseColumnType[AuthType] = MappedColumnType.base[AuthType, String](
    AuthType.toString,
    AuthType.fromString(_).getOrElse(throw new IllegalArgumentException("Invalid AuthType"))
  )
  implicit val userIdColumnType: BaseColumnType[UserId] = MappedColumnType.base[UserId, Long](
    _.value,
    UserId.apply
  )

  // Users Table
  class Users(tag: Tag) extends Table[User](tag, "users") {
    def id        = column[UserId]("id", O.PrimaryKey, O.AutoInc)
    def email     = column[String]("email", O.Unique)
    def status    = column[String]("status")
    def createdAt = column[LocalDateTime]("created_at", O.Default(LocalDateTime.now()))
    def updatedAt = column[LocalDateTime]("updated_at", O.Default(LocalDateTime.now()))

    def * = (id.?, email, status, createdAt.?, updatedAt.?) <> ((User.apply _).tupled, User.unapply)
  }
  lazy val users = TableQuery[Users]

  // Credentials Table
  class Credentials(tag: Tag) extends Table[Credential](tag, "credentials") {
    def id          = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def userId      = column[UserId]("user_id")
    def `type`      = column[AuthType]("type")       // `type` is a reserved keyword in Scala, so use backticks
    def identifier  = column[Option[String]]("identifier")
    def secret      = column[Option[String]]("secret")
    def meta        = column[Option[String]]("meta") // Store JSONB as String
    def enabled     = column[Boolean]("enabled", O.Default(true))
    def priority    = column[Int]("priority", O.Default(0))
    def failedCount = column[Int]("failed_count", O.Default(0))
    def lockedUntil = column[Option[LocalDateTime]]("locked_until")
    def createdAt   = column[LocalDateTime]("created_at", O.Default(LocalDateTime.now()))
    def updatedAt   = column[LocalDateTime]("updated_at", O.Default(LocalDateTime.now()))

    def userFk = foreignKey("credentials_user_fk", userId, users)(_.id)

    def * = (
      id.?,
      userId,
      `type`,
      identifier,
      secret,
      meta,
      enabled,
      priority,
      failedCount,
      lockedUntil,
      createdAt.?,
      updatedAt.?
    ) <> ((Credential.apply _).tupled, Credential.unapply)
  }
  lazy val credentials = TableQuery[Credentials]

  // RefreshTokens Table
  class RefreshTokens(tag: Tag) extends Table[RefreshToken](tag, "refresh_tokens") {
    def id        = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def userId    = column[UserId]("user_id")
    def tokenHash = column[String]("token_hash")
    def expiresAt = column[LocalDateTime]("expires_at")
    def createdAt = column[LocalDateTime]("created_at", O.Default(LocalDateTime.now()))

    def userFk = foreignKey("refresh_tokens_user_fk", userId, users)(_.id)

    def * = (id.?, userId, tokenHash, expiresAt, createdAt.?) <> ((RefreshToken.apply _).tupled, RefreshToken.unapply)
  }
  lazy val refreshTokens = TableQuery[RefreshTokens]

  // LoginHistory Table
  class LoginHistorys(tag: Tag) extends Table[LoginHistory](tag, "login_history") {
    def id        = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def userId    = column[UserId]("user_id")
    def ipAddress = column[Option[String]]("ip_address")
    def userAgent = column[Option[String]]("user_agent")
    def success   = column[Boolean]("success")
    def action    = column[Option[String]]("action")
    def createdAt = column[LocalDateTime]("created_at", O.Default(LocalDateTime.now()))

    def userFk = foreignKey("login_history_user_fk", userId, users)(_.id)

    def * = (id.?, userId, ipAddress, userAgent, success, action, createdAt.?) <> (
      (LoginHistory.apply _).tupled,
      LoginHistory.unapply
    )
  }
  lazy val loginHistorys = TableQuery[LoginHistorys]

  // RecoveryTokens Table
  class RecoveryTokens(tag: Tag) extends Table[RecoveryToken](tag, "recovery_tokens") {
    def userId    = column[UserId]("user_id")
    def tokenHash = column[String]("token_hash")
    def expiresAt = column[LocalDateTime]("expires_at")
    def tokenType = column[String]("token_type")

    def userFk = foreignKey("recovery_tokens_user_fk", userId, users)(_.id)

    def *  = (userId, tokenHash, expiresAt, tokenType) <> ((RecoveryToken.apply _).tupled, RecoveryToken.unapply)
    def pk = primaryKey("recovery_tokens_pk", (userId, tokenHash)) // Composite primary key
  }
  lazy val recoveryTokens = TableQuery[RecoveryTokens]

}
