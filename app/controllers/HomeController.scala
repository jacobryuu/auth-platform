package controllers

import javax.inject._
import play.api.mvc._

@Singleton
class HomeController @Inject() (val controllerComponents: ControllerComponents) extends BaseController {

  def index: Action[AnyContent] = Action(implicit request => Ok("Auth Platform is running!"))
}
