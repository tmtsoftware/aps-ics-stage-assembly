# stage

This project implements an HCD (Hardware Control Daemon) and Assembly using 
TMT Common Software ([CSW](https://github.com/tmtsoftware/csw-prod)) APIs. 

## Subprojects

* stage-assembly - an assembly that talks to the stage HCD
* stage-hcd - an HCD that talks to the stage hardware
* stage-deploy - for starting/deploying HCD's and Assembly's

## Build Instructions

The build is based on sbt and depends on libraries published to bintray from the 
[csw-prod](https://github.com/tmtsoftware/csw-prod) project.

See [here](https://www.scala-sbt.org/1.0/docs/Setup.html) for instructions on installing sbt.

## Pre-requisites before running Components

Make sure that the necessary environment variables are set. For example:

* Set the environment variables (Replace interface name, IP address and port with your own values):
```bash
export interfaceName=enp0s31f6
export clusterSeeds=192.168.178.77:7777
```
for bash shell, or 
```csh
setenv interfaceName enp0s31f6
setenv clusterSeeds 192.168.178.77:7777
```

for csh or tcsh. The list of available network interfaces can be found using the _ifconfig -a_ command.
If you don't specify the network interface this way, a default will be chosen, which might sometimes not be
the one you expect. 

Before running any components, follow below steps:
 - Download csw-apps zip from https://github.com/tmtsoftware/csw-prod/releases.
 - Unzip the downloaded zip
 - Go to the bin directory where you will find `csw-services.sh` script.
 - Run `./csw_services.sh --help` to see more information
 - Run `./csw_services.sh start` to start location service and config server

## Running HCD and Assembly

 - Run `sbt stage-deploy/universal:packageBin`, this will create self contained zip in target/universal directory
 - Unzip generate zip and enter into bin directory
 - Run container cmd script or host config app script