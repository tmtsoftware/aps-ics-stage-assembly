# stage-deploy

This module contains apps and configuration files for host deployment using 
HostConfig (https://tmtsoftware.github.io/csw/apps/hostconfig.html) and 
ContainerCmd (https://tmtsoftware.github.io/csw/framework/deploying-components.html).

An important part of making this work is ensuring the host config app (StageHostConfigApp) is built
with all of the necessary dependencies of the components it may run.  This is done by adding settings to the
built.sbt file:

```
lazy val `stage-deploy` = project
  .dependsOn(
    `stage-assembly`,
    `stage-hcd`
  )
  .enablePlugins(JavaAppPackaging)
  .settings(
    libraryDependencies ++= Dependencies.StageDeploy
  )
```

and in Libs.scala:

```

  val `csw-framework`  = "org.tmt" %% "csw-framework"  % Version

```


##Setting up ICS Prototype configuration

put the following in .bashrc:

```
export PATH=$PATH:/home/smichaels/Desktop/csw/csw-apps-1.0.0-RC2/bin

export TMT_LOG_HOME=/tmp/csw/log
```
cd to the stage-deploy/src/main/resource directory and run the following:

```
csw-config-cli login --consoleLogin   (and use kevin/abcd as username and password)

csw-config-cli create /config/org/tmt/aps/ics/FiberSourceStageAssembly.conf -i FiberSourceStageAssembly.conf --comment 'changed config file'
csw-config-cli create /config/org/tmt/aps/ics/DmOpticStageAssembly.conf -i DmOpticStageAssembly.conf --comment 'changed config file'
csw-config-cli create /config/org/tmt/aps/ics/PupilMaskStageAssembly.conf -i PupilMaskStageAssembly.conf --comment 'changed config file'
csw-config-cli create /config/org/tmt/aps/ics/StageContainer.conf -i StageContainer.conf --comment 'changed config file'
```

cd to the stage-deploy/target/universal/stage/bin and run:
```
./stage-container-cmd-app /config/org/tmt/aps/ics/StageContainer.conf
```
to start the stage assemblies
To start stage Assembly and HCD, follow below steps:

 - Run `sbt stage-deploy/universal:packageBin`, this will create self contained zip in target/universal directory
 - Unzip generate zip and enter into bin directory
 - Run container cmd script or host config app script
 - Ex.  `./stage-host-config-app --local ../../../../stage-deploy/src/main/resources/StageHostConfig.conf -s ./stage-container-cmd-app`

Note: the CSW Location Service must be running before starting the components.
See https://tmtsoftware.github.io/csw/apps/cswlocationserver.html .