package org.tmt.aps.ics.stagedeploy

import csw.framework.deploy.containercmd.ContainerCmd

object StageContainerCmdApp extends App {

  ContainerCmd.start("stage-container-cmd-app", args)

}
