////////////////////////////////////////////////////////////////////////////////////
// Common stuff
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.2")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
addSbtPlugin("com.github.cb372" % "sbt-explicit-dependencies" % "0.3.1")

////////////////////////////////////////////////////////////////////////////////////
// Server
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

////////////////////////////////////////////////////////////////////////////////////
// Web client
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.20.1")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.21.1")
//addSbtPlugin("org.scalablytyped.converter" % "sbt-converter"            % "1.0.0-beta44")

////////////////////////////////////////////////////////////////////////////////////
// Testing

libraryDependencies ++= Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "7.5.0.202512021534-r",
  "commons-io" % "commons-io" % "2.21.0")

