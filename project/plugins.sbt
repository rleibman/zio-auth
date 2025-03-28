////////////////////////////////////////////////////////////////////////////////////
// Common stuff
addSbtPlugin("com.typesafe.sbt"  % "sbt-git"                   % "1.0.2")
addSbtPlugin("de.heikoseeberger" % "sbt-header"                % "5.10.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"              % "2.5.4")
addSbtPlugin("com.github.cb372"  % "sbt-explicit-dependencies" % "0.3.1")
addSbtPlugin("ch.epfl.scala"     % "sbt-scalafix"              % "0.14.2")
addSbtPlugin("com.typesafe"      % "sbt-mima-plugin"           % "1.1.4")

////////////////////////////////////////////////////////////////////////////////////
// Server
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

////////////////////////////////////////////////////////////////////////////////////
// Web client
addSbtPlugin("org.scala-js"          % "sbt-scalajs"              % "1.18.2")
addSbtPlugin("org.portable-scala"    % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("ch.epfl.scala"         % "sbt-scalajs-bundler"      % "0.21.1")

////////////////////////////////////////////////////////////////////////////////////
// Testing
addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "0.17.2")
addSbtPlugin("org.scoverage"      % "sbt-scoverage" % "2.3.1")

libraryDependencies ++= Seq("org.eclipse.jgit" % "org.eclipse.jgit" % "7.2.0.202503040940-r")
libraryDependencies ++= Seq("commons-io" % "commons-io" % "2.18.0")
