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

object MotorOffCmdActor {
  def behavior(commandResponseManager: CommandResponseManager,
               galilHcd: Option[CommandService],
               axisName: String,
               axisConfig: Config,
               loggerFactory: LoggerFactory): Behavior[ControlCommand] =
    Behaviors.setup(ctx ⇒ MotorOffCmdActor(ctx, commandResponseManager, galilHcd, axisName, axisConfig, loggerFactory))
}

case class MotorOffCmdActor(ctx: ActorContext[ControlCommand],
                            commandResponseManager: CommandResponseManager,
                            galilHcd: Option[CommandService],
                            axisName: String,
                            axisConfig: Config,
                            loggerFactory: LoggerFactory)
    extends AbstractBehavior[ControlCommand] {

  private val log = loggerFactory.getLogger

  implicit val timeout: Timeout = Timeout(3.seconds)

  private val prefix: Prefix = Prefix("galil-init-cmds")
  private val maybeObsId     = None

  private val axisKey: Key[Char] = KeyType.CharKey.make("axis")

  override def onMessage(msg: ControlCommand): Behavior[ControlCommand] = {
    msg match {
      case (x: ControlCommand) => processCommand(x)
      case _                   => log.error(s"unhandled message in AxesMapper onMessage: $msg")
    }
    this
  }

  private def processCommand(message: ControlCommand): Unit = {

    log.info("processing command")

    val axis = axisConfig.getString("Channel").toCharArray.head

    try {

      val output = new StringBuilder()

      val resp1 = Await.result(motorOff(message.maybeObsId, axis), 3.seconds)

      if (resp1.isInstanceOf[Error]) throw new Exception(s"motorOff $resp1") else output.append(s"\nmotorOff $resp1, ")

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
   * Sends a motorOff message to the HCD and returns the response
   */
  def motorOff(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("motorOff"), maybeObsId)
          .add(axisKey.set(axis))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

}
