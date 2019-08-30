import com.typesafe.sbt.SbtNativePackager.Universal

lazy val aggregatedProjects: Seq[ProjectReference] = Seq(
  `stage-assembly`,
  `stage-hcd`,
  `stage-deploy`
)

lazy val `stage` = project
  .in(file("."))
  .aggregate(aggregatedProjects: _*)

lazy val `stage-assembly` = project
  .settings(
    libraryDependencies ++= Dependencies.StageAssembly
  )

lazy val `stage-hcd` = project
  .settings(
    libraryDependencies ++= Dependencies.StageHcd
  )

lazy val `stage-deploy` = project
  .dependsOn(
    `stage-assembly`,
    `stage-hcd`
  )
  .enablePlugins(JavaAppPackaging, CswBuildInfo)
  .settings(
    libraryDependencies ++= Dependencies.StageDeploy,
    // This is the placeholder for setting JVM options via sbt native packager.
    // You can add more JVM options below.
//    javaOptions in Universal ++= Seq(
//      // -J params will be added as jvm parameters
//      "-J-Xmx8GB",
//      "J-XX:+UseG1GC", // G1GC is default in jdk9 and above
//      "J-XX:MaxGCPauseMillis=30" // Sets a target for the maximum GC pause time. This is a soft goal, and the JVM will make its best effort to achieve it
//    )
  )
