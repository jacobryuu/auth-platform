ThisBuild / scalaVersion := "3.3.1"
ThisBuild / version      := "1.0.0"
ThisBuild / organization := "com.example"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := """auth-platform""",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test,
      // Play Framework
      "org.playframework" %% "play" % "3.0.10",
      // Pekko
      "org.apache.pekko" %% "pekko-actor-typed"            % "1.4.0",
      "org.apache.pekko" %% "pekko-stream"                 % "1.4.0",
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % "1.4.0",
      // Prevent mixed Pekko versions at runtime (Play dev server checks this)
      "org.apache.pekko" %% "pekko-serialization-jackson" % "1.4.0",
      // Slick for DB access
      "org.playframework"  %% "play-slick"     % "6.2.0",
      "com.typesafe.slick" %% "slick-hikaricp" % "3.6.1",
      "org.postgresql"      % "postgresql"     % "42.7.9",
      // Auth
      "com.github.jwt-scala" %% "jwt-play" % "11.0.3",
      // Did not find a specific webauthn-scala library for Play 3, using a generic one
      "com.yubico" % "webauthn-server-core" % "2.8.1",
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.5.26",
      // Password Hashing
      "org.mindrot" % "jbcrypt" % "0.4",
      // Swagger/OpenAPI
      "io.swagger.core.v3"   % "swagger-annotations" % "2.2.42",
      "io.swagger.core.v3"   % "swagger-models"      % "2.2.42",
      "io.swagger.core.v3"   % "swagger-core"        % "2.2.42",
      "io.swagger.parser.v3" % "swagger-parser"      % "2.1.37",
      "org.webjars"          % "swagger-ui"          % "5.31.0"
    )
  )

// Scalafmt settings
ThisBuild / scalafmtOnCompile := true
