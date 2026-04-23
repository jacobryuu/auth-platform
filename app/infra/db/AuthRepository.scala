package infra.db

import domain.models._

import javax.inject.{Inject, Singleton}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuthRepository @Inject() (
  protected val dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
)(implicit ec: ExecutionContext) {

  private val dbConfig = dbConfigProvider.get[JdbcProfile]
  private val db       = dbConfig.db
  private val profile  = dbConfig.profile

  import profile.api._

  private val authTable = new AuthTable(profile)
  import authTable._

  // --- User operations ---
  def createUser(user: User): Future[User] =
    db.run(users returning users.map(_.id) into ((user, id) => user.copy(id = Some(id))) += user)

  def findUserById(id: UserId): Future[Option[User]] =
    db.run(users.filter(_.id === id).result.headOption)

  def findUserByEmail(email: String): Future[Option[User]] =
    db.run(users.filter(_.email === email).result.headOption)

  def updateUser(user: User): Future[Int] =
    db.run(users.filter(_.id === user.id).update(user))

  // --- Credential operations ---
  def createCredential(credential: Credential): Future[Credential] =
    db.run(credentials returning credentials.map(_.id) into ((cred, id) => cred.copy(id = Some(id))) += credential)

  def findCredentialById(id: Long): Future[Option[Credential]] =
    db.run(credentials.filter(_.id === id).result.headOption)

  def findCredentialsByUserId(userId: UserId): Future[Seq[Credential]] =
    db.run(credentials.filter(_.userId === userId).sortBy(_.priority.asc).result)

  def findCredentialByUserIdAndType(userId: UserId, authType: AuthType): Future[Option[Credential]] =
    db.run(credentials.filter(c => c.userId === userId && c.`type` === authType).result.headOption)

  def updateCredential(credential: Credential): Future[Int] =
    db.run(credentials.filter(_.id === credential.id).update(credential))

  def deleteCredential(id: Long): Future[Int] =
    db.run(credentials.filter(_.id === id).delete)

  // --- RefreshToken operations ---
  def createRefreshToken(refreshToken: RefreshToken): Future[RefreshToken] =
    db.run(
      refreshTokens returning refreshTokens.map(_.id) into ((token, id) => token.copy(id = Some(id))) += refreshToken
    )

  def findRefreshTokensByUserId(userId: UserId): Future[Seq[RefreshToken]] =
    db.run(refreshTokens.filter(_.userId === userId).result)

  def findRefreshToken(userId: UserId, tokenHash: String): Future[Option[RefreshToken]] =
    db.run(refreshTokens.filter(t => t.userId === userId && t.tokenHash === tokenHash).result.headOption)

  def deleteRefreshToken(id: Long): Future[Int] =
    db.run(refreshTokens.filter(_.id === id).delete)

  def deleteRefreshTokensByUserId(userId: UserId): Future[Int] =
    db.run(refreshTokens.filter(_.userId === userId).delete)

  // --- LoginHistory operations ---
  def createLoginHistory(loginHistory: LoginHistory): Future[LoginHistory] =
    db.run(
      loginHistorys returning loginHistorys.map(_.id) into ((history, id) =>
        history.copy(id = Some(id))
      ) += loginHistory
    )

  def findLoginHistoryByUserId(userId: UserId, limit: Int = 10): Future[Seq[LoginHistory]] =
    db.run(loginHistorys.filter(_.userId === userId).sortBy(_.createdAt.desc).take(limit).result)

  // --- RecoveryToken operations ---
  def createRecoveryToken(recoveryToken: RecoveryToken): Future[Int] =
    db.run(recoveryTokens += recoveryToken)

  def findRecoveryTokensByUserId(userId: UserId): Future[Seq[RecoveryToken]] =
    db.run(recoveryTokens.filter(_.userId === userId).result)

  def findRecoveryToken(userId: UserId, tokenHash: String): Future[Option[RecoveryToken]] =
    db.run(recoveryTokens.filter(t => t.userId === userId && t.tokenHash === tokenHash).result.headOption)

  def deleteRecoveryToken(userId: UserId, tokenHash: String): Future[Int] =
    db.run(recoveryTokens.filter(t => t.userId === userId && t.tokenHash === tokenHash).delete)
}
