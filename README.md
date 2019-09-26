# APS ICS Stage Assembly

This project implements an  Assembly using 
TMT Common Software ([CSW](https://github.com/tmtsoftware/csw-prod)) APIs. 

## Subprojects

* stage-assembly - an assembly that talks to the stage HCD
* stage-deploy - for starting/deploying HCD's and Assembly's

## Build Instructions

The build is based on sbt and depends on libraries published to bintray from the 
[csw-prod](https://github.com/tmtsoftware/csw-prod) project.

See [here](https://www.scala-sbt.org/1.0/docs/Setup.html) for instructions on installing sbt.

## Setting up ICS Prototype configuration

put the following in .bashrc:

```
export PATH=$PATH:/home/smichaels/Desktop/csw/csw-apps-1.0.0-RC2/bin

export TMT_LOG_HOME=/tmp/csw/log
```
cd to the stage-deploy/src/main/resource directory and run the following:

```
./initialize-config.sh  (use kevin/abcd as username and password)
```


## Running the Assemblies

 cd to the stage-deploy/target/universal/stage/bin and run:
 ```
 ./stage-container-cmd-app /config/org/tmt/aps/ics/StageContainer.conf
 ```