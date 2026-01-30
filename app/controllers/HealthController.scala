package controllers

import play.api.mvc._
import play.api.libs.json._

import javax.inject._

@Singleton
class HealthController @Inject() (val controllerComponents: ControllerComponents) extends BaseController {

  def health(): Action[AnyContent] = Action {
    Ok(
      Json.obj(
        "status"    -> "healthy",
        "service"   -> "auth-platform",
        "timestamp" -> System.currentTimeMillis()
      )
    )
  }

  def readiness(): Action[AnyContent] = Action {
    // TODO: Add database connectivity check
    Ok(
      Json.obj(
        "status"   -> "ready",
        "database" -> "connected"
      )
    )
  }
}
