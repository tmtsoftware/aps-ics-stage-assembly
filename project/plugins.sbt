addSbtPlugin("org.scalastyle"   %%  "scalastyle-sbt-plugin" % "1.0.0")

addSbtPlugin("com.geirsson"     %   "sbt-scalafmt"          % "1.5.1")
addSbtPlugin("org.scoverage"    %   "sbt-scoverage"         % "1.6.0")
addSbtPlugin("com.typesafe.sbt" %   "sbt-native-packager"   % "1.3.6")
addSbtPlugin("com.eed3si9n"     %   "sbt-buildinfo"         % "0.9.0")

addSbtPlugin("io.get-coursier"  % "sbt-coursier" % "1.1.0-M13-4")
classpathTypes += "maven-plugin"

scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-feature",
  "-unchecked",
  "-deprecation",
  //"-Xfatal-warnings",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Xfuture"
)
