import org.apache.commons.io.FileUtils

import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import scala.collection.Seq

lazy val dist = TaskKey[File]("dist")
lazy val debugDist = TaskKey[File]("debugDist")

enablePlugins(
  GitVersioning
)

lazy val SCALA = "3.7.4"
Global / onChangedBuildSource := ReloadOnSourceChanges
scalaVersion                  := SCALA
Global / scalaVersion         := SCALA

import scala.concurrent.duration.*

Global / watchAntiEntropy := 1.second

val zioConfigVersion = "4.0.6"
val zioHttpVersion = "3.7.0"
val zioJsonVersion = "0.7.45"
val zioVersion = "2.1.23"
val scalajsReactVersion = "2.1.3"
val reactVersion = "^18.3.0"
val sttpVersion = "4.0.13"

lazy val scala3Opts = Seq(
  "-deprecation", // Emit warning and location for usages of deprecated APIs.
  "-no-indent", // scala3
  "-old-syntax", // I hate space sensitive languages!
  "-encoding",
  "utf-8", // Specify character encoding used by source files.
  "-feature", // Emit warning and location for usages of features that should be imported explicitly.
  "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
  "-language:implicitConversions",
  "-language:higherKinds", // Allow higher-kinded types
  //  "-language:strictEquality", //This is cool, but super noisy
  "-unchecked", // Enable additional warnings where generated code depends on assumptions.
  //  "-Wsafe-init", //Great idea, breaks compile though.
  "-Xfatal-warnings", // Fail the compilation if there are any warnings.
  "-Xmax-inlines",
  "128",
  //  "-explain-types", // Explain type errors in more detail.
  //  "-explain",
  "-Yexplicit-nulls", // Make reference types non-nullable. Nullable types can be expressed with unions: e.g. String|Null.
  "-Yretain-trees" // Retain trees for debugging.,
)

lazy val commonSettings = Seq(
  organization     := "net.leibman",
  startYear        := Some(2025),
  organizationName := "Roberto Leibman",
  headerLicense    := Some(HeaderLicense.MIT("2025", "Roberto Leibman", HeaderLicenseStyle.Detailed)),
  resolvers += Resolver.mavenLocal,
  scalacOptions ++= scala3Opts
)

//React app that manages the login workflow
lazy val auth = crossProject(JSPlatform, JVMPlatform)
  .enablePlugins(
    AutomateHeaderPlugin,
    GitVersioning
  )
  .in(file("auth"))
  .settings(commonSettings)
  .jvmEnablePlugins(GitVersioning)
  .jsEnablePlugins(GitVersioning)
  .jvmSettings(
    name         := "zio-auth",
    scalaVersion := SCALA,
    libraryDependencies ++= Seq(
      // Log
      "ch.qos.logback" % "logback-classic" % "1.5.21" withSources (),
      // ZIO
      "dev.zio"                       %% "zio"                   % zioVersion withSources (),
      "dev.zio"                       %% "zio-nio"               % "2.0.2" withSources (),
      "dev.zio"                       %% "zio-cache"             % "0.2.7" withSources (),
      "dev.zio"                       %% "zio-config"            % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-config-derivation" % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-config-magnolia"   % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-config-typesafe"   % zioConfigVersion withSources (),
      "dev.zio"                       %% "zio-logging-slf4j2"    % "2.5.2" withSources (),
      "dev.zio"                       %% "zio-http"              % zioHttpVersion withSources (),
      "com.github.jwt-scala"          %% "jwt-circe"             % "11.0.3" withSources (),
      "dev.zio"                       %% "zio-json"              % zioJsonVersion withSources (),
      "org.scala-lang.modules"        %% "scala-xml"             % "2.3.0" withSources (),
      // HTTP client for OAuth providers
      "com.softwaremill.sttp.client4" %% "core"                  % sttpVersion withSources (),
      "com.softwaremill.sttp.client4" %% "zio"                   % sttpVersion withSources (),
      "com.softwaremill.sttp.client4" %% "zio-json"              % sttpVersion withSources (),
      // Other random utilities
      "com.github.daddykotex"         %% "courier"               % "4.0.0-RC1" withSources (),
      // Testing
      "dev.zio" %% "zio-test"     % zioVersion % "test" withSources (),
      "dev.zio" %% "zio-test-sbt" % zioVersion % "test" withSources ()
    )
  )
  .jsEnablePlugins(ScalaJSBundlerPlugin)
  .jsSettings(
    name              := "zio-auth",
    scalaVersion      := SCALA,
    webpack / version := "5.96.1",
    Compile / fastOptJS / artifactPath := ((Compile / fastOptJS / crossTarget).value /
      ((fastOptJS / moduleName).value + "-opt.js")),
    Compile / fullOptJS / artifactPath := ((Compile / fullOptJS / crossTarget).value /
      ((fullOptJS / moduleName).value + "-opt.js")),
    webpackEmitSourceMaps := false,
    scalaJSLinkerConfig ~= {
      _.withSourceMap(false) // .withRelativizeSourceMapBase(None)
    },
    useYarn                                   := true,
    run / fork                                := true,
    Global / scalaJSStage                     := FastOptStage,
    Compile / scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %%% "core"      % sttpVersion withSources (),
      "com.softwaremill.sttp.client4" %%% "zio-json"  % sttpVersion withSources (),
      "org.scala-js" %%% "scalajs-dom"                % "2.8.1" withSources (),
      "com.github.japgolly.scalajs-react" %%% "core"  % scalajsReactVersion withSources (),
      "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion withSources (),
      "com.lihaoyi" %%% "scalatags"                   % "0.13.1" withSources (),
      "dev.zio" %%% "zio-json"                        % zioJsonVersion withSources (),
      "org.typelevel" %%% "cats-core"                 % "2.13.0" withSources ()
    ),
    Compile / npmDependencies ++= Seq(
      "@types/react"     -> reactVersion,
      "@types/react-dom" -> reactVersion,
      "react"            -> reactVersion,
      "react-dom"        -> reactVersion
    ),
    debugDist := {

      val assets = (ThisBuild / baseDirectory).value / "auth" / "js" / "src" / "main" / "web"

      val artifacts = (Compile / fastOptJS / webpack).value
      val artifactFolder = (Compile / fastOptJS / crossTarget).value
      val debugFolder = (ThisBuild / baseDirectory).value / "debugDist"

      debugFolder.mkdirs()
      FileUtils.copyDirectory(assets, debugFolder, true)
      artifacts.foreach { artifact =>
        val target = artifact.data.relativeTo(artifactFolder) match {
          case None          => debugFolder / artifact.data.name
          case Some(relFile) => debugFolder / relFile.toString
        }

        Files.copy(artifact.data.toPath, target.toPath, REPLACE_EXISTING)
      }

      debugFolder
    },
    dist := {
      val assets = (ThisBuild / baseDirectory).value / "auth" / "js" / "src" / "main" / "web"

      val artifacts = (Compile / fullOptJS / webpack).value
      val artifactFolder = (Compile / fullOptJS / crossTarget).value
      val distFolder = (ThisBuild / baseDirectory).value / "dist"

      distFolder.mkdirs()
      FileUtils.copyDirectory(assets, distFolder, true)
      artifacts.foreach { artifact =>
        val target = artifact.data.relativeTo(artifactFolder) match {
          case None          => distFolder / artifact.data.name
          case Some(relFile) => distFolder / relFile.toString
        }

        Files.copy(artifact.data.toPath, target.toPath, REPLACE_EXISTING)
      }

      distFolder
    }
  )

lazy val server = crossProject(JSPlatform, JVMPlatform)
  .enablePlugins(
    AutomateHeaderPlugin,
    GitVersioning
  )
  .dependsOn(auth)
  .in(file("server"))
  .settings(commonSettings)
  .jvmEnablePlugins(GitVersioning)
  .jsEnablePlugins(GitVersioning)
  .jvmSettings(
    publish / skip := true,
    libraryDependencies ++= Seq(
      // Log
      "ch.qos.logback" % "logback-classic" % "1.5.21" withSources (),
      // ZIO
      "dev.zio"                %% "zio"                   % zioVersion withSources (),
      "dev.zio"                %% "zio-nio"               % "2.0.2" withSources (),
      "dev.zio"                %% "zio-cache"             % "0.2.7" withSources (),
      "dev.zio"                %% "zio-config"            % zioConfigVersion withSources (),
      "dev.zio"                %% "zio-config-derivation" % zioConfigVersion withSources (),
      "dev.zio"                %% "zio-config-magnolia"   % zioConfigVersion withSources (),
      "dev.zio"                %% "zio-config-typesafe"   % zioConfigVersion withSources (),
      "dev.zio"                %% "zio-logging-slf4j2"    % "2.5.2" withSources (),
      "dev.zio"                %% "zio-http"              % zioHttpVersion withSources (),
      "com.github.jwt-scala"   %% "jwt-circe"             % "11.0.3" withSources (),
      "dev.zio"                %% "zio-json"              % zioJsonVersion withSources (),
      "org.scala-lang.modules" %% "scala-xml"             % "2.3.0" withSources (),
      // Other random utilities
      "com.github.daddykotex" %% "courier" % "4.0.0-RC1" withSources (),
      // Testing
      "dev.zio" %% "zio-test"     % zioVersion % "test" withSources (),
      "dev.zio" %% "zio-test-sbt" % zioVersion % "test" withSources ()
    )
  )
  .jsEnablePlugins(ScalaJSBundlerPlugin)
  .jsSettings(
    publish / skip    := true,
    webpack / version := "5.96.1",
    Compile / fastOptJS / artifactPath := ((Compile / fastOptJS / crossTarget).value /
      ((fastOptJS / moduleName).value + "-opt.js")),
    Compile / fullOptJS / artifactPath := ((Compile / fullOptJS / crossTarget).value /
      ((fullOptJS / moduleName).value + "-opt.js")),
    webpackEmitSourceMaps := false,
    scalaJSLinkerConfig ~= {
      _.withSourceMap(false) // .withRelativizeSourceMapBase(None)
    },
    useYarn                                   := true,
    run / fork                                := true,
    Global / scalaJSStage                     := FastOptStage,
    Compile / scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom"                % "2.8.1" withSources (),
      "com.github.japgolly.scalajs-react" %%% "core"  % scalajsReactVersion withSources (),
      "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion withSources (),
      "com.lihaoyi" %%% "scalatags"                   % "0.13.1" withSources (),
      "dev.zio" %%% "zio-json"                        % zioJsonVersion withSources ()
    ),
    Compile / npmDependencies ++= Seq(
      "@types/react"     -> reactVersion,
      "@types/react-dom" -> reactVersion,
      "react"            -> reactVersion,
      "react-dom"        -> reactVersion
    ),
    debugDist := {

      val assets = (ThisBuild / baseDirectory).value / "server" / "js" / "src" / "main" / "web"

      val artifacts = (Compile / fastOptJS / webpack).value
      val artifactFolder = (Compile / fastOptJS / crossTarget).value
      val debugFolder = (ThisBuild / baseDirectory).value / "debugDist"

      debugFolder.mkdirs()
      FileUtils.copyDirectory(assets, debugFolder, true)
      artifacts.foreach { artifact =>
        val target = artifact.data.relativeTo(artifactFolder) match {
          case None          => debugFolder / artifact.data.name
          case Some(relFile) => debugFolder / relFile.toString
        }

        Files.copy(artifact.data.toPath, target.toPath, REPLACE_EXISTING)
      }

      debugFolder
    },
    dist := {
      val assets = (ThisBuild / baseDirectory).value / "server" / "js" / "src" / "main" / "web"

      val artifacts = (Compile / fullOptJS / webpack).value
      val artifactFolder = (Compile / fullOptJS / crossTarget).value
      val distFolder = (ThisBuild / baseDirectory).value / "dist"

      distFolder.mkdirs()
      FileUtils.copyDirectory(assets, distFolder, true)
      artifacts.foreach { artifact =>
        val target = artifact.data.relativeTo(artifactFolder) match {
          case None          => distFolder / artifact.data.name
          case Some(relFile) => distFolder / relFile.toString
        }

        Files.copy(artifact.data.toPath, target.toPath, REPLACE_EXISTING)
      }

      distFolder
    }
  )

//////////////////////////////////////////////////////////////////////////////////////////////////
// Root project
lazy val root = project
  .in(file("."))
  .aggregate(server.js, server.jvm, auth.js, auth.jvm)
  .enablePlugins(
    GitVersioning
  )
  .settings(
    name           := "zio-auth",
    publish / skip := true
  )
