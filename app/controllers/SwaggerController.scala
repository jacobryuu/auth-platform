package controllers

import play.api.mvc._
import play.api.libs.json._
import javax.inject._
import scala.io.Source
import play.api.Environment
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.core.util.Json as SwaggerJson
import scala.jdk.CollectionConverters._

@Singleton
class SwaggerController @Inject() (
  val controllerComponents: ControllerComponents,
  environment: Environment
) extends BaseController {

  // キャッシュされたOpenAPI仕様
  private lazy val openApiSpec: Option[String] = {
    environment.resourceAsStream("openapi.yaml").map { stream =>
      try {
        val yamlContent = Source.fromInputStream(stream, "UTF-8").mkString

        // Swagger Parserを使ってYAMLをパース
        val parseResult = new OpenAPIV3Parser().readContents(yamlContent)
        val openAPI     = parseResult.getOpenAPI

        if (openAPI != null) {
          // OpenAPIオブジェクトをJSONに変換
          SwaggerJson.pretty(openAPI)
        } else {
          // パースエラーがある場合
          val errors = Option(parseResult.getMessages).map(_.asScala.mkString(", ")).getOrElse("Unknown error")
          s"""{"error": "Failed to parse OpenAPI spec", "details": "$errors"}"""
        }
      } catch {
        case e: Exception =>
          s"""{"error": "Failed to load OpenAPI spec", "message": "${e.getMessage}"}"""
      } finally stream.close()
    }
  }

  def specs: Action[AnyContent] = Action {
    openApiSpec match {
      case Some(json) => Ok(json).as("application/json")
      case None       => NotFound(Json.obj("error" -> "OpenAPI specification not found"))
    }
  }
}
