package org.tmt.aps.ics.stageassembly

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.util.Timeout
import com.typesafe.config.Config
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandResponseManager
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse.{CompletedWithResult, Error}
import csw.params.commands.{CommandName, CommandResponse, ControlCommand, Result, Setup}
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

  private val axisKey: Key[Char]  = KeyType.CharKey.make("axis")
  private val speedKey: Key[Int]  = KeyType.IntKey.make("speed")
  private val countsKey: Key[Int] = KeyType.IntKey.make("counts")

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

    val axisType = axisConfig.getString("AxisType")

    axisType match {
      case "servo" => {

        try {

          val output = new StringBuilder()

          val resp1 = Await.result(setHomingMode(maybeObsId, axis), 3.seconds)

          if (resp1.isInstanceOf[Error]) throw new Exception(s"setHomingMode $resp1")
          else output.append(s"\nsetHomingMode $resp1, ")

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
      case "stepper" => {

        /*
MOG
SHG
JGG=-10000
BGG

MG _LFG will return 1.0 when limit is reached
then
STG
DPG=0 - set position to zero

         */

        try {

          val output = new StringBuilder()

          val resp0 = Await.result(motorOff(maybeObsId, axis), 3.seconds)

          if (resp0.isInstanceOf[Error]) throw new Exception(s"motorOff $resp0") else output.append(s"\nmotorOff $resp0, ")

          val resp1 = Await.result(motorOn(maybeObsId, axis), 3.seconds)

          if (resp1.isInstanceOf[Error]) throw new Exception(s"motorOn $resp1") else output.append(s"\nmotorOn $resp1, ")

          val resp2 = Await.result(setJogSpeed(maybeObsId, axis, homingJogSpeed), 3.seconds)

          if (resp2.isInstanceOf[Error]) throw new Exception(s"setJogSpeed $resp2")
          else output.append(s"\nsetJogSpeed($homingJogSpeed) $resp2, ")

          val resp3 = Await.result(beginMotion(maybeObsId, axis), 3.seconds)

          if (resp3.isInstanceOf[Error]) throw new Exception(s"beginMotion $resp3") else output.append(s"\nbeginMotion $resp3, ")

          output.toString()

          // now we wait until limit is reached

          var limitReached: Boolean = false;
          while (!limitReached) {

            val resp7: CommandResponse = Await.result(tellForwardLimit(maybeObsId, axis), 3.seconds)

            resp7 match {
              case e: Error => {
                throw new Exception(s"tellForwardLimit $resp7")
              }
              case cwr: CompletedWithResult => {
                output.append(s"\ntellForwardLimit ${cwr.result.paramSet}, ")
                log.info(s"\ntellForwardLimit ${cwr.result.paramSet}, ")
                val result: Result        = cwr.result
                val msgValueKey: Key[Int] = KeyType.IntKey.make("msgValue")
                val value: Int            = result.get(msgValueKey).get.items.head

                if (value == 1) limitReached = true
              }
            }

            Thread.sleep(1000)
          }

          val resp5 = Await.result(stopMotion(maybeObsId, axis), 3.seconds)

          if (resp5.isInstanceOf[Error]) throw new Exception(s"stop $resp5") else output.append(s"\nstop $resp5, ")

          // define this as position 'zero'
          val resp6 = Await.result(setMotorPostion(maybeObsId, axis, 0), 3.seconds)

          if (resp6.isInstanceOf[Error]) throw new Exception(s"setMotorPosition $resp6")
          else output.append(s"\nsetMotorPosition $resp6, ")

          log.info("command completed")

          commandResponseManager.updateSubCommand(CommandResponse.Completed(message.runId))

        } catch {

          case e: Exception =>
            commandResponseManager.updateSubCommand(CommandResponse.Error(message.runId, e.getMessage))
          case _: Throwable =>
            commandResponseManager.updateSubCommand(CommandResponse.Error(message.runId, "Unexpected error"))

        }

      }
      case _ => log.error(s"bad axis type")
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
   * Sends a stopMotion message to the HCD and returns the response
   */
  def stopMotion(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("stopMotion"), obsId)
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

  /**
   * Sends a motorOn (ServoHere) message to the HCD and returns the response
   */
  def motorOn(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("motorOn"), maybeObsId)
          .add(axisKey.set(axis))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
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

  /**
   * Returns true if forward limit is reached to the HCD and returns the response
   */
  def tellForwardLimit(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("tellForwardLimit"), maybeObsId)
          .add(axisKey.set(axis))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a DP message to the Galil and returns the response
   */
  def setMotorPostion(obsId: Option[ObsId], axis: Char, counts: Int): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setMotorPosition"), maybeObsId)
          .add(axisKey.set(axis))
          .add(countsKey.set(counts))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

}
