package controllers

import play.api.mvc._
import javax.inject._

@Singleton
class SwaggerUIController @Inject() (
  assets: Assets,
  val controllerComponents: ControllerComponents
) extends BaseController {

  def index: Action[AnyContent] = Action {
    Ok(swaggerUIHtml).as("text/html; charset=utf-8")
  }

  def swaggerUIAssets(file: String): Action[AnyContent] =
    assets.at("/META-INF/resources/webjars/swagger-ui/5.31.0", file)

  private def swaggerUIHtml: String = """
<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <title>Auth Platform API - Swagger UI</title>
    <link rel="stylesheet" type="text/css" href="/swagger-ui/swagger-ui.css">
    <link rel="icon" type="image/png" href="/swagger-ui/favicon-32x32.png" sizes="32x32">
    <style>
        html {
            box-sizing: border-box;
            overflow: -moz-scrollbars-vertical;
            overflow-y: scroll;
        }
        *, *:before, *:after {
            box-sizing: inherit;
        }
        body {
            margin: 0;
            padding: 0;
        }
        .topbar {
            display: none;
        }
    </style>
</head>
<body>
    <div id="swagger-ui"></div>
    <script src="/swagger-ui/swagger-ui-bundle.js" charset="UTF-8"></script>
    <script src="/swagger-ui/swagger-ui-standalone-preset.js" charset="UTF-8"></script>
    <script>
        window.onload = function() {
            const ui = SwaggerUIBundle({
                url: "/api-docs/swagger.json",
                dom_id: '#swagger-ui',
                deepLinking: true,
                presets: [
                    SwaggerUIBundle.presets.apis,
                    SwaggerUIStandalonePreset
                ],
                plugins: [
                    SwaggerUIBundle.plugins.DownloadUrl
                ],
                layout: "StandaloneLayout",
                validatorUrl: null
            });
            window.ui = ui;
        };
    </script>
</body>
</html>
"""
}
