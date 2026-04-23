package modules

import actors.*
import com.google.inject.{AbstractModule, TypeLiteral}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.ActorSystem as ClassicActorSystem
import play.api.Configuration
import play.api.libs.concurrent.PekkoGuiceSupport
import services.AuthService
import infra.db.AuthRepository

import java.time.Clock
import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.ExecutionContext

/**
 * アプリ固有の Guice バインド。
 */
class Module extends AbstractModule with PekkoGuiceSupport {
  override def configure(): Unit = {
    bind(classOf[Clock]).toInstance(Clock.systemDefaultZone())

    // Repository / Service
    bind(classOf[AuthRepository]).asEagerSingleton()
    bind(classOf[AuthService]).asEagerSingleton()

    // Bind the typed ActorSystem
    bind(new TypeLiteral[ActorSystem[?]]() {}).toProvider(classOf[TypedActorSystemProvider])

    // Bind typed actor references
    bind(new TypeLiteral[ActorRef[AuthActor.Command]]() {})
      .toProvider(classOf[AuthActorProvider])
      .in(classOf[Singleton])
    bind(new TypeLiteral[ActorRef[AuditActor.Command]]() {})
      .toProvider(classOf[AuditActorProvider])
      .in(classOf[Singleton])
    bind(new TypeLiteral[ActorRef[RateLimitActor.Command]]() {})
      .toProvider(classOf[RateLimitActorProvider])
      .in(classOf[Singleton])
    bind(new TypeLiteral[ActorRef[TokenActor.Command]]() {})
      .toProvider(classOf[TokenActorProvider])
      .in(classOf[Singleton])
  }
}

@Singleton
class TypedActorSystemProvider @Inject() (classicSystem: ClassicActorSystem) extends Provider[ActorSystem[?]] {
  import org.apache.pekko.actor.typed.scaladsl.adapter._
  override def get(): ActorSystem[?] = classicSystem.toTyped
}

@Singleton
class AuthActorProvider @Inject() (
  actorSystem: ActorSystem[?],
  authService: AuthService,
  tokenActor: ActorRef[TokenActor.Command],
  ec: ExecutionContext
) extends Provider[ActorRef[AuthActor.Command]] {
  override def get(): ActorRef[AuthActor.Command] =
    actorSystem.systemActorOf(AuthActor(authService, tokenActor)(ec), "AuthActor")
}

@Singleton
class AuditActorProvider @Inject() (
  actorSystem: ActorSystem[?],
  authRepository: AuthRepository,
  ec: ExecutionContext
) extends Provider[ActorRef[AuditActor.Command]] {
  override def get(): ActorRef[AuditActor.Command] =
    actorSystem.systemActorOf(AuditActor(authRepository)(ec), "AuditActor")
}

@Singleton
class RateLimitActorProvider @Inject() (
  actorSystem: ActorSystem[?]
) extends Provider[ActorRef[RateLimitActor.Command]] {
  override def get(): ActorRef[RateLimitActor.Command] = {
    val maxAttempts                = 5
    val period: java.time.Duration = java.time.Duration.ofMinutes(1)
    actorSystem.systemActorOf(RateLimitActor(maxAttempts, period), "RateLimitActor")
  }
}

@Singleton
class TokenActorProvider @Inject() (
  actorSystem: ActorSystem[?],
  configuration: Configuration,
  clock: Clock
) extends Provider[ActorRef[TokenActor.Command]] {
  override def get(): ActorRef[TokenActor.Command] =
    actorSystem.systemActorOf(TokenActor(configuration)(clock), "TokenActor")
}
