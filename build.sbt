import org.apache.commons.io.FileUtils
import org.scalajs.linker.interface.ModuleSplitStyle

import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

lazy val dist = TaskKey[File]("dist")
lazy val debugDist = TaskKey[File]("debugDist")

enablePlugins(
  com.github.sbt.git.GitVersioning
)

lazy val SCALA = "3.9.0"
Global / onChangedBuildSource                     := ReloadOnSourceChanges
scalaVersion                                      := SCALA
Global / scalaVersion                             := SCALA
import scala.concurrent.duration.*

Global / watchAntiEntropy := 1.second

val catsVersion = "2.13.0"
val courierVersion = "4.0.0-RC1"
val jwtVersion = "11.0.4"
val logbackVersion = "1.6.3"
val scalaJsDomVersion = "2.8.1"
val scalaTagsVersion = "0.13.1"
val scalaXmlVersion = "2.3.0"
val scalajsReactVersion = "4.0.0"
val sttpVersion = "4.0.26"
val zioConfigVersion = "4.0.8"
val zioHttpVersion = "3.11.4"
val zioJsonVersion = "1.0.0"
val zioLoggingVersion = "2.5.3"
val zioNioVersion = "2.0.2"
val zioCacheVersion = "0.2.8"
val zioSchemaJsonVersion = "1.8.0"
val zioVersion = "2.1.26"

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
  "-Werror", // Fail the compilation if there are any warnings.
  "-Xmax-inlines",
  "128",
  //  "-explain-types", // Explain type errors in more detail.
  //  "-explain",
  "-Yexplicit-nulls", // Make reference types non-nullable. Nullable types can be expressed with unions: e.g. String|Null.
  "-Yretain-trees" // Retain trees for debugging.,
)

// `%%` expands to the JVM artifact (zio-json_3) only, so the Scala.js side needs naming explicitly -- there is
// no `%%%` under sbt 2. sttp-client4 still asks for zio-json 0.9.0 while this build is on 1.0.0.
ThisBuild / libraryDependencySchemes ++= Seq(
  "dev.zio" %% "zio-json" % VersionScheme.Always,
  "dev.zio"  % "zio-json_sjs1_3" % VersionScheme.Always,
)

// sbt-git sets ThisBuild / version but nothing in this build reads it; that is the plugin's business, not a
// mistake here, and sbt 2's lintUnused check has no other way to be told so.
Global / excludeLintKeys += version

/** Links with Scala.js, bundles with `vite build`, then lays the result out next to the static assets.
  *
  * sbt drives vite rather than the other way round: @scala-js/vite-plugin-scalajs resolves the linker output by
  * spawning `sbt print fastLinkJSOutput`, which from inside an sbt task means sbt re-entering itself for a path the
  * caller already holds. So the paths go over in the environment instead (see server/js/vite.config.js).
  */
def viteDistImpl(
  viteRoot:      File,
  scalaJSOutput: File,
  assets:        File,
  stagingDir:    File,
  outputFolder:  File,
  mode:          String,
  log:           Logger
): File = {
  import scala.sys.process.*

  if (!(viteRoot / "node_modules").exists()) {
    log.info(s"node_modules missing, running `npm install` in $viteRoot")
    val installed = Process("npm" :: "install" :: Nil, viteRoot).!
    if (installed != 0) sys.error(s"npm install failed in $viteRoot (exit code $installed)")
  }

  val env = Seq(
    "SCALAJS_OUTPUT_DIR" -> scalaJSOutput.getAbsolutePath,
    "VITE_OUT_DIR"       -> stagingDir.getAbsolutePath
  )
  log.info(s"vite build --mode $mode (scala.js output: $scalaJSOutput)")
  val built = Process("npx" :: "vite" :: "build" :: "--mode" :: mode :: Nil, viteRoot, env*).!
  if (built != 0) sys.error(s"vite build failed in $viteRoot (exit code $built)")

  outputFolder.mkdirs()
  if (assets.exists()) FileUtils.copyDirectory(assets, outputFolder, true)
  FileUtils.copyDirectory(stagingDir, outputFolder, true)
  outputFolder
}


lazy val commonSettings = Seq(
  organization     := "net.leibman",
  startYear        := Some(2025),
  organizationName := "Roberto Leibman",
  headerLicense    := Some(HeaderLicense.MIT("2025", "Roberto Leibman", HeaderLicenseStyle.Detailed)),
  resolvers += Resolver.mavenLocal,
  scalacOptions ++= scala3Opts,
  publishMavenStyle := true,
  publishTo := Some(
    "GitHub Package Registry" at "https://maven.pkg.github.com/rleibman/zio-auth"
  ),
  credentials += Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "rleibman",
    sys.env.getOrElse("GITHUB_TOKEN", "")
  )
)

//React app that manages the login workflow
lazy val auth = crossProject(JSPlatform, JVMPlatform)
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning
  )
  .in(file("auth"))
  .settings(commonSettings)
  .jvmEnablePlugins(com.github.sbt.git.GitVersioning)
  .jsEnablePlugins(com.github.sbt.git.GitVersioning)
  .jvmSettings(
    name         := "zio-auth",
    scalaVersion := SCALA,
    libraryDependencies ++= Seq(
      // Log
      ("ch.qos.logback" % "logback-classic" % logbackVersion).withSources(),
      // ZIO
      ("dev.zio"                %% "zio"                   % zioVersion).withSources(),
      ("dev.zio"                %% "zio-nio"               % zioNioVersion).withSources(),
      ("dev.zio"                %% "zio-cache"             % zioCacheVersion).withSources(),
      ("dev.zio"                %% "zio-config"            % zioConfigVersion).withSources(),
      ("dev.zio"                %% "zio-config-derivation" % zioConfigVersion).withSources(),
      ("dev.zio"                %% "zio-config-magnolia"   % zioConfigVersion).withSources(),
      ("dev.zio"                %% "zio-config-typesafe"   % zioConfigVersion).withSources(),
      ("dev.zio"                %% "zio-logging-slf4j2"    % zioLoggingVersion).withSources(),
      ("dev.zio"                %% "zio-http"              % zioHttpVersion).withSources(),
      ("com.github.jwt-scala"   %% "jwt-circe"             % jwtVersion).withSources(),
      ("dev.zio"                %% "zio-json"              % zioJsonVersion).withSources(),
      ("org.scala-lang.modules" %% "scala-xml"             % scalaXmlVersion).withSources(),
      // HTTP client for OAuth providers
      ("com.softwaremill.sttp.client4" %% "core"     % sttpVersion).withSources(),
      ("com.softwaremill.sttp.client4" %% "zio"      % sttpVersion).withSources(),
      ("com.softwaremill.sttp.client4" %% "zio-json" % sttpVersion).withSources(),
      // Other random utilities
      ("com.github.daddykotex" %% "courier" % courierVersion).withSources(),
      // Testing
      ("dev.zio" %% "zio-test"     % zioVersion % "test").withSources(),
      ("dev.zio" %% "zio-test-sbt" % zioVersion % "test").withSources()
    ),
    dependencyOverrides += "dev.zio" %% "zio-json"        % zioJsonVersion,
    dependencyOverrides += "dev.zio" %% "zio-schema-json" % zioSchemaJsonVersion
  )
  // auth/js is a LIBRARY: no main, and auth/js/src/main/web is empty. sbt-scalajs-bundler was carrying webpack,
  // npmDependencies (react/react-dom) and a dist/debugDist pair that copied that empty directory -- none of which
  // ever affected the published zio-auth_sjs1_3 jar. The bundler has no sbt2 build, and nothing here needs one.
  .jsSettings(
    name         := "zio-auth",
    scalaVersion := SCALA,
    scalaJSLinkerConfig ~= (_.withSourceMap(false)),
    libraryDependencies ++= Seq(
      ("com.softwaremill.sttp.client4" %% "core"      % sttpVersion).withSources(),
      ("com.softwaremill.sttp.client4" %% "zio-json"  % sttpVersion).withSources(),
      ("org.scala-js" %% "scalajs-dom"                % scalaJsDomVersion).withSources(),
      ("com.github.japgolly.scalajs-react" %% "core"  % scalajsReactVersion).withSources(),
      ("com.github.japgolly.scalajs-react" %% "extra" % scalajsReactVersion).withSources(),
      ("com.lihaoyi" %% "scalatags"                   % scalaTagsVersion).withSources(),
      ("dev.zio" %% "zio-json"                        % zioJsonVersion).withSources(),
      ("org.typelevel" %% "cats-core"                 % catsVersion).withSources()
    )
  )

lazy val server = crossProject(JSPlatform, JVMPlatform)
  .enablePlugins(
    AutomateHeaderPlugin,
    com.github.sbt.git.GitVersioning
  )
  .dependsOn(auth)
  .in(file("server"))
  .settings(commonSettings)
  .jvmEnablePlugins(com.github.sbt.git.GitVersioning)
  .jsEnablePlugins(com.github.sbt.git.GitVersioning)
  .jvmSettings(
    publish / skip := true,
    libraryDependencies ++= Seq(
      // Log
      ("ch.qos.logback" % "logback-classic" % logbackVersion).withSources(),
      // ZIO
      ("dev.zio"                %% "zio"                   % zioVersion).withSources(),
      ("dev.zio"                %% "zio-nio"               % zioNioVersion).withSources(),
      ("dev.zio"                %% "zio-cache"             % zioCacheVersion).withSources(),
      ("dev.zio"                %% "zio-config"            % zioConfigVersion).withSources(),
      ("dev.zio"                %% "zio-config-derivation" % zioConfigVersion).withSources(),
      ("dev.zio"                %% "zio-config-magnolia"   % zioConfigVersion).withSources(),
      ("dev.zio"                %% "zio-config-typesafe"   % zioConfigVersion).withSources(),
      ("dev.zio"                %% "zio-logging-slf4j2"    % zioLoggingVersion).withSources(),
      ("dev.zio"                %% "zio-http"              % zioHttpVersion).withSources(),
      ("com.github.jwt-scala"   %% "jwt-circe"             % jwtVersion).withSources(),
      ("dev.zio"                %% "zio-json"              % zioJsonVersion).withSources(),
      ("org.scala-lang.modules" %% "scala-xml"             % scalaXmlVersion).withSources(),
      // Other random utilities
      ("com.github.daddykotex" %% "courier" % courierVersion).withSources(),
      // Testing
      ("dev.zio" %% "zio-test"     % zioVersion % "test").withSources(),
      ("dev.zio" %% "zio-test-sbt" % zioVersion % "test").withSources()
    )
  )
  .jsSettings(
    publish / skip := true,
    scalaVersion   := SCALA,
    // ES modules, the only module kind a current web toolchain consumes directly. This is what replaces the
    // webpack the bundler plugin used to drive (see server/js/vite.config.js).
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(ModuleSplitStyle.SmallModulesFor(List("auth")))
        .withSourceMap(true)
    },
    run / fork                                := true,
    Global / scalaJSStage                     := FastOptStage,
    Compile / scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      ("org.scala-js" %% "scalajs-dom"                % scalaJsDomVersion).withSources(),
      ("com.github.japgolly.scalajs-react" %% "core"  % scalajsReactVersion).withSources(),
      ("com.github.japgolly.scalajs-react" %% "extra" % scalajsReactVersion).withSources(),
      ("com.lihaoyi" %% "scalatags"                   % scalaTagsVersion).withSources(),
      ("dev.zio" %% "zio-json"                        % zioJsonVersion).withSources()
    ),
    debugDist := Def.uncached {
      viteDistImpl(
        viteRoot = (ThisBuild / baseDirectory).value / "server" / "js",
        scalaJSOutput = (Compile / fastLinkJSOutput).value,
        assets = (ThisBuild / baseDirectory).value / "server" / "js" / "src" / "main" / "web",
        stagingDir = target.value / "vite" / "debugDist",
        outputFolder = (ThisBuild / baseDirectory).value / "debugDist",
        mode = "development",
        log = streams.value.log
      )
    },
    dist := Def.uncached {
      viteDistImpl(
        viteRoot = (ThisBuild / baseDirectory).value / "server" / "js",
        scalaJSOutput = (Compile / fullLinkJSOutput).value,
        assets = (ThisBuild / baseDirectory).value / "server" / "js" / "src" / "main" / "web",
        stagingDir = target.value / "vite" / "dist",
        outputFolder = (ThisBuild / baseDirectory).value / "dist",
        mode = "production",
        log = streams.value.log
      )
    }
  )

//////////////////////////////////////////////////////////////////////////////////////////////////
// Root project
lazy val root = project
  .in(file("."))
  .aggregate(server.js, server.jvm, auth.js, auth.jvm)
  .enablePlugins(
    com.github.sbt.git.GitVersioning
  )
  .settings(
    // NOT "zio-auth": sbt 2 derives each project's output directory from its name, and the aggregator sharing a
    // name with authJVM makes them collide ("Overlapping output directories"). The published artifact is authJVM's,
    // and that keeps the zio-auth name -- this one is never published.
    name           := "zio-auth-root",
    publish / skip := true
  )
