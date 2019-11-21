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

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object InitCmdActor {
  def behavior(commandResponseManager: CommandResponseManager,
               galilHcd: Option[CommandService],
               axisName: String,
               axisConfig: Config,
               loggerFactory: LoggerFactory): Behavior[ControlCommand] =
    Behaviors.setup(ctx ⇒ InitCmdActor(ctx, commandResponseManager, galilHcd, axisName, axisConfig, loggerFactory))
}

case class InitCmdActor(ctx: ActorContext[ControlCommand],
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

  private val axisKey: Key[Char]            = KeyType.CharKey.make("axis")
  private val countsKey: Key[Int]           = KeyType.IntKey.make("counts")
  private val interpCountsKey: Key[Int]     = KeyType.IntKey.make("interpCounts")
  private val brushlessModulusKey: Key[Int] = KeyType.IntKey.make("brushlessModulus")
  private val voltsKey: Key[Double]         = KeyType.DoubleKey.make("volts")
  private val speedKey: Key[Int]            = KeyType.IntKey.make("speed")
  private val mTypeKey: Key[Double]         = KeyType.DoubleKey.make("mType")

  override def onMessage(msg: ControlCommand): Behavior[ControlCommand] = {
    msg match {
      case (x: ControlCommand) => processCommand(x)
      case _                   => log.error(s"unhandled message in AxesMapper onMessage: $msg")
    }
    this
  }

  private def processCommand(message: ControlCommand): Unit = {

    log.info("processing command")

    val axisType = axisConfig.getString("AxisType")
    val axis     = axisConfig.getString("Channel").toCharArray.head

    axisType match {
      case "servo" => {

        val analogFeedbackSelect = axisConfig.getInt("InterpolationCounts")
        val brushlessModulus     = axisConfig.getInt("BrushlessModulus")
        val brushlessZeroVolts   = axisConfig.getDouble("BrushlessZeroVolts")

        try {

          val output = new StringBuilder()

          val resp1 = Await.result(setBrushlessAxis(maybeObsId, axis), 3.seconds)

          if (resp1.isInstanceOf[Error]) throw new Exception(s"setBushelessAxis $resp1")
          else output.append(s"\nsetBrushlessAxis $resp1, ")

          val resp2 = Await.result(setAnalogFeedbackSelect(maybeObsId, axis, analogFeedbackSelect), 3.seconds)

          if (resp2.isInstanceOf[Error]) throw new Exception(s"setAnalogFeedbackSelect $resp2")
          else output.append(s"\nsetAnalogFeedbackSelect($analogFeedbackSelect) $resp2, ")

          val resp3 = Await.result(setBrushlessModulus(maybeObsId, axis, brushlessModulus), 3.seconds)

          if (resp3.isInstanceOf[Error]) throw new Exception(s"setBrushlessModulus $resp3")
          else output.append(s"\nsetBrushlessModulus($brushlessModulus) $resp3, ")

          val resp4 = Await.result(brushlessZero(maybeObsId, axis, brushlessZeroVolts), 3.seconds)

          if (resp4.isInstanceOf[Error]) throw new Exception(s"brushlessZero $resp4")
          else output.append(s"\nbrushlessZero($brushlessZeroVolts) $resp4")

          val resp5 = Await.result(servoHere(maybeObsId, axis), 3.seconds)

          if (resp5.isInstanceOf[Error]) throw new Exception(s"servoHere $resp5")
          else output.append(s"\nservoHere($brushlessZeroVolts) $resp5")


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

        try {
          val output = new StringBuilder()

          // set motor type to stepper
          val resp1 = Await.result(setMotorType(maybeObsId, axis, 2), 3.seconds)

          if (resp1.isInstanceOf[Error]) throw new Exception(s"setMotorType $resp1")
          else output.append(s"\nsetMotorType $resp1, ")

          val resp5 = Await.result(servoHere(maybeObsId, axis), 3.seconds)

          if (resp5.isInstanceOf[Error]) throw new Exception(s"servoHere $resp5")
          else output.append(s"\nservoHere() $resp5")

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
   * Sends a setBrushlessAxis message to the HCD and returns the response
   */
  def setBrushlessAxis(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setBrushlessAxis"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a setAnalogFeedbackSelect message to the HCD and returns the response
   */
  def setAnalogFeedbackSelect(obsId: Option[ObsId], axis: Char, interpCounts: Int): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setAnalogFeedbackSelect"), obsId)
          .add(axisKey.set(axis))
          .add(interpCountsKey.set(interpCounts))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a setBrushlessModulus message to the HCD and returns the response
   */
  def setBrushlessModulus(obsId: Option[ObsId], axis: Char, brushlessModulus: Int): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setBrushlessModulus"), obsId)
          .add(axisKey.set(axis))
          .add(brushlessModulusKey.set(brushlessModulus))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a brushlessZero message to the HCD and returns the response
   */
  def brushlessZero(obsId: Option[ObsId], axis: Char, volts: Double): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("brushlessZero"), obsId)
          .add(axisKey.set(axis))
          .add(voltsKey.set(volts))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a servoHere message to the HCD and returns the response
   */
  def servoHere(obsId: Option[ObsId], axis: Char, volts: Double): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("motorOn"), obsId)
          .add(axisKey.set(axis))


        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a setAnalogFeedbackSelect message to the HCD and returns the response
   */
  def setMotorType(obsId: Option[ObsId], axis: Char, motorType: Double): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setMotorType"), obsId)
          .add(axisKey.set(axis))
          .add(mTypeKey.set(motorType))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

}
