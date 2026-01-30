package controllers

import infra.db.AuthRepository
import play.api.mvc._
import play.api.libs.json._
import utils.JwtHelper
import domain.models.UserId

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserController @Inject() (
  val controllerComponents: ControllerComponents,
  authRepository: AuthRepository,
  jwtHelper: JwtHelper
)(implicit ec: ExecutionContext)
    extends BaseController {

  def getUserInfo(): Action[AnyContent] = Action.async { request =>
    request.headers.get("Authorization") match {
      case Some(authHeader) if authHeader.startsWith("Bearer ") =>
        val token = authHeader.substring(7)

        if (!jwtHelper.validateToken(token)) {
          Future.successful(Unauthorized(Json.obj("message" -> "Invalid or expired token")))
        } else {
          jwtHelper.getUserIdFromToken(token) match {
            case Some(userId) =>
              authRepository.findUserById(UserId(userId)).map {
                case Some(user) =>
                  Ok(
                    Json.obj(
                      "id"        -> user.id.get.value,
                      "email"     -> user.email,
                      "status"    -> user.status,
                      "createdAt" -> user.createdAt.toString,
                      "updatedAt" -> user.updatedAt.toString
                    )
                  )
                case None =>
                  NotFound(Json.obj("message" -> "User not found"))
              }
            case None =>
              Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
          }
        }

      case _ =>
        Future.successful(Unauthorized(Json.obj("message" -> "Missing authorization header")))
    }
  }
}
