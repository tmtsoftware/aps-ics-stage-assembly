package org.tmt.aps.ics.stageassembly

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.util.Timeout
import com.typesafe.config.Config
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandResponseManager
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse.Error
import csw.params.commands.{CommandName, CommandResponse, ControlCommand, Setup}
import csw.params.core.generics.{Key, KeyType}
import csw.params.core.models.{Id, ObsId, Prefix}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object HomeCmdActor {
  def behavior(commandResponseManager: CommandResponseManager,
               galilHcd: Option[CommandService],
               axisName: String,
               axisConfig: Config,
               loggerFactory: LoggerFactory): Behavior[ControlCommand] =
    Behaviors.setup(ctx ⇒ HomeCmdActor(ctx, commandResponseManager, galilHcd, axisName, axisConfig, loggerFactory))
}

case class HomeCmdActor(ctx: ActorContext[ControlCommand],
                        commandResponseManager: CommandResponseManager,
                        galilHcd: Option[CommandService],
                        axisName: String,
                        axisConfig: Config,
                        loggerFactory: LoggerFactory)
    extends AbstractBehavior[ControlCommand] {

  private val log = loggerFactory.getLogger

  implicit val timeout: Timeout = Timeout(3.seconds)

  private val prefix: Prefix = Prefix("aps.ics.stage")
  private val maybeObsId     = None

  private val axisKey: Key[Char] = KeyType.CharKey.make("axis")
  private val speedKey: Key[Int] = KeyType.IntKey.make("speed")

  override def onMessage(msg: ControlCommand): Behavior[ControlCommand] = {
    msg match {
      case (x: ControlCommand) => processCommand(x)
      case _                   => log.error(s"unhandled message in AxesMapper onMessage: $msg")
    }
    this
  }

  private def processCommand(message: ControlCommand): Unit = {

    log.info("processing command")

    // channel character and homing jog speed from axis configuration

    val axis           = axisConfig.getString("Channel").toCharArray.head
    val homingJogSpeed = axisConfig.getInt("HomingJogSpeed")

    try {

      val output = new StringBuilder()

      val resp1 = Await.result(setHomingMode(maybeObsId, axis), 3.seconds)

      if (resp1.isInstanceOf[Error]) throw new Exception(s"setHomingMode $resp1") else output.append(s"\nsetHomingMode $resp1, ")

      val resp2 = Await.result(setJogSpeed(maybeObsId, axis, homingJogSpeed), 3.seconds)

      if (resp2.isInstanceOf[Error]) throw new Exception(s"setJogSpeed $resp2")
      else output.append(s"\nsetJogSpeed($homingJogSpeed) $resp2, ")

      val resp3 = Await.result(beginMotion(maybeObsId, axis), 3.seconds)

      if (resp3.isInstanceOf[Error]) throw new Exception(s"beginMotion $resp3") else output.append(s"\nbeginMotion $resp3, ")

      output.toString()

      Thread.sleep(1000)

      log.info("command completed")

      commandResponseManager.updateSubCommand(CommandResponse.Completed(message.runId))

    } catch {

      case e: Exception =>
        commandResponseManager.updateSubCommand(CommandResponse.Error(message.runId, e.getMessage))
      case _: Throwable =>
        commandResponseManager.updateSubCommand(CommandResponse.Error(message.runId, "Unexpected error"))

    }

  }

  /**
   * Sends a setHomingMode message to the HCD and returns the response
   */
  def setHomingMode(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setHomingMode"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a beginMotion message to the HCD and returns the response
   */
  def beginMotion(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("beginMotion"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a setJogSpeed message to the HCD and returns the response
   */
  def setJogSpeed(obsId: Option[ObsId], axis: Char, speed: Int): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setJogSpeed"), obsId)
          .add(axisKey.set(axis))
          .add(speedKey.set(speed))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a setFindIndexMode message to the HCD and returns the response
   */
  def setFindIndexMode(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setFindIndexMode"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

}
