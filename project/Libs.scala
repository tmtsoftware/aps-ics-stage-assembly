import sbt._

object Libs {
  val ScalaVersion = "2.13.0"

  val `scalatest`       = "org.scalatest"          %% "scalatest"       % "3.0.8"  //Apache License 2.0
  val `scala-async`     = "org.scala-lang.modules" %% "scala-async"     % "0.10.0"  //BSD 3-clause "New" or "Revised" License
  val `junit`           = "junit"                  %  "junit"           % "4.12"   //Eclipse Public License 1.0
  val `junit-interface` = "com.novocode"           %  "junit-interface" % "0.11"   //BSD 2-clause "Simplified" License
  val `mockito-core`    = "org.mockito"            %  "mockito-core"    % "2.28.2" //MIT License
}

object CSW {
  val Version = "1.0.0-RC2"

  val `csw-framework` = "com.github.tmtsoftware.csw" %% "csw-framework" % Version
  val `csw-testkit`   = "com.github.tmtsoftware.csw" %% "csw-testkit" % Version
}
