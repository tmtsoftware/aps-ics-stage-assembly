package org.tmt.aps.ics.stagedeploy

import csw.framework.deploy.hostconfig.HostConfig

object StageHostConfigApp extends App {

  HostConfig.start("stage-host-config-app", args)

}
