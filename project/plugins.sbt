////////////////////////////////////////////////////////////////////////////////////
// Common stuff
// sbt-git moved org: com.typesafe.sbt is sbt1-only, com.github.sbt publishes the sbt2 build.
addSbtPlugin("com.github.sbt" % "sbt-git"      % "2.1.0")
// sbt-header likewise moved from de.heikoseeberger to com.github.sbt for sbt 2.
addSbtPlugin("com.github.sbt" % "sbt-header"   % "5.11.0")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt" % "2.6.2")
// No sbt2 build of sbt-explicit-dependencies.
// addSbtPlugin("com.github.cb372" % "sbt-explicit-dependencies" % "0.3.1")

////////////////////////////////////////////////////////////////////////////////////
// Server
// No sbt2 build of sbt-revolver, so `reStart` is gone; use `run`.
// addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

////////////////////////////////////////////////////////////////////////////////////
// Web client
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.4.0")
// sbt-scalajs-bundler (webpack) has no sbt2 build. auth/js never needed it -- it is a library, and its
// auth/js/src/main/web is empty -- so it is simply gone there. The server/js demo app bundles with vite now;
// see runViteBuild in build.sbt and server/js/vite.config.js.
// addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.21.1")

////////////////////////////////////////////////////////////////////////////////////
// Testing

libraryDependencies ++= Seq(
  "org.eclipse.jgit" % "org.eclipse.jgit" % "7.7.1.202607240634-r",
  "commons-io"       % "commons-io"       % "2.22.0",
)
