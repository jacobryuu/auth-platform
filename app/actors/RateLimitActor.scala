package actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import java.time.{LocalDateTime, Duration}
import scala.concurrent.duration._

object RateLimitActor {

  // Actor Protocol
  sealed trait Command

  case class CheckRateLimit(identifier: String, replyTo: ActorRef[RateLimitResponse]) extends Command
  case object CleanUpTimers extends Command // Internal message for cleaning up expired entries

  // Responses
  sealed trait RateLimitResponse
  case object RateLimitExceeded extends RateLimitResponse
  case object RateLimitAllowed  extends RateLimitResponse

  // Internal state
  case class RateLimitState(attempts: Map[String, Vector[LocalDateTime]])

  private case object CleanUpKey

  def apply(maxAttempts: Int, period: Duration, cleanupInterval: FiniteDuration = 5.minutes): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      timers.startTimerWithFixedDelay(CleanUpKey, CleanUpTimers, cleanupInterval)
      rateLimiter(timers, maxAttempts, period, RateLimitState(Map.empty))
    }
  }

  private def rateLimiter(
    timers: TimerScheduler[Command],
    maxAttempts: Int,
    period: Duration,
    state: RateLimitState
  ): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case CheckRateLimit(identifier, replyTo) =>
          val now = LocalDateTime.now()
          val recentAttempts = state.attempts
            .getOrElse(identifier, Vector.empty)
            .filter(time => Duration.between(time, now).compareTo(period) < 0)

          if (recentAttempts.size >= maxAttempts) {
            context.log.warn(s"RateLimitExceeded for identifier: $identifier")
            replyTo ! RateLimitExceeded
            Behaviors.same
          } else {
            context.log.debug(s"RateLimitAllowed for identifier: $identifier")
            replyTo ! RateLimitAllowed
            val newState = state.copy(attempts = state.attempts + (identifier -> (recentAttempts :+ now)))
            rateLimiter(timers, maxAttempts, period, newState)
          }

        case CleanUpTimers =>
          context.log.debug("Cleaning up expired rate limit entries.")
          val now = LocalDateTime.now()
          val cleanedAttempts = state.attempts
            .map {
              case (id, times) =>
                id -> times.filter(time => Duration.between(time, now).compareTo(period) < 0)
            }
            .filter(_._2.nonEmpty) // Remove identifiers with no recent attempts
          rateLimiter(timers, maxAttempts, period, state.copy(attempts = cleanedAttempts))
      }
    }
  }
}
