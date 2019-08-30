package org.tmt.aps.ics.stageassembly

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.typesafe.config.{Config, ConfigObject}
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandResponseManager
import csw.location.api.scaladsl.LocationService
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.{CommandName, ControlCommand, Setup}
import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.Prefix

// Add messages here
// No sealed trait or messages for this actor.  Always accepts the Submit command message.

object MultiAxisCommandHandler {
  def behavior(commandResponseManager: CommandResponseManager,
               galilHcd: Option[CommandService],
               assemblyConfig: Config,
               locationService: LocationService,
               loggerFactory: LoggerFactory): Behavior[ControlCommand] =
    Behaviors.setup(
      ctx ⇒ MultiAxisCommandHandler(ctx, commandResponseManager, galilHcd, assemblyConfig, locationService, loggerFactory)
    )
}

case class MultiAxisCommandHandler(ctx: ActorContext[ControlCommand],
                                   commandResponseManager: CommandResponseManager,
                                   galilHcd: Option[CommandService],
                                   assemblyConfig: Config,
                                   locationService: LocationService,
                                   loggerFactory: LoggerFactory)
    extends AbstractBehavior[ControlCommand] {

  private val log = loggerFactory.getLogger

  val axesKey: Key[String]           = KeyType.StringKey.make("axes")
  val countsKey: Key[Int]            = KeyType.IntKey.make("counts")
  val positionMethodKey: Key[String] = KeyType.StringKey.make("positionMethod")
  val positionKey: Key[Double]       = KeyType.DoubleKey.make("position")
  val positionCoordKey: Key[String]  = KeyType.StringKey.make("positionCoord")

  private var actorList: List[ActorRef[_]] = List.empty

  override def onMessage(msg: ControlCommand): Behavior[ControlCommand] = {

    handleSubmitCommand(msg)

    this
  }

  private def handleSubmitCommand(message: ControlCommand): Unit = {

    // complete the command by sending a command response "Completed"

    // spawn command actors for each axis being commanded

    // 1. map the command name to the command actor

    message.commandName.name match {

      case "init" =>
        val axesValues = message.paramSet.find(x => x.keyName == "axes").get.values

        // need pattern matching because of type erasure (need to get [String] back from [Any])
        axesValues match {

          case axesNames: Array[String] => {

            axesNames.foreach((axisName: String) => {

              val axisConfig = extractAxisConfig(assemblyConfig, axisName)

              // we need to spawn a new instance of the command actor
              val initCmdActor: ActorRef[ControlCommand] =
                ctx.spawnAnonymous(InitCmdActor.behavior(commandResponseManager, galilHcd, axisName, axisConfig, loggerFactory))

              val setup = Setup(Prefix("init-single-axis"), CommandName("init"), message.maybeObsId)

              commandResponseManager.addSubCommand(message.runId, setup.runId)

              // send it the message
              initCmdActor ! setup

              actorList = actorList :+ initCmdActor

            })
          }
          case _ =>
        }

      case "home" =>
        val axesValues = message.paramSet.find(x => x.keyName == "axes").get.values

        // need pattern matching because of type erasure (need to get [String] back from [Any])
        axesValues match {

          case axesNames: Array[String] => {

            axesNames.foreach((axisName: String) => {

              val axisConfig = extractAxisConfig(assemblyConfig, axisName)

              // we need to spawn a new instance of the command actor
              val homeCmdActor: ActorRef[ControlCommand] =
                ctx.spawnAnonymous(HomeCmdActor.behavior(commandResponseManager, galilHcd, axisName, axisConfig, loggerFactory))

              val setup = Setup(Prefix("home-single-axis"), CommandName("home"), message.maybeObsId)

              commandResponseManager.addSubCommand(message.runId, setup.runId)

              // send it the message
              homeCmdActor ! setup

              actorList = actorList :+ homeCmdActor
            })
          }
          case _ =>
        }

      case "motorOff" =>
        val axesValues = message.paramSet.find(x => x.keyName == "axes").get.values

        // need pattern matching because of type erasure (need to get [String] back from [Any])
        axesValues match {

          case axesNames: Array[String] => {

            axesNames.foreach((axisName: String) => {

              val axisConfig = extractAxisConfig(assemblyConfig, axisName)

              // we need to spawn a new instance of the command actor
              val motorOffCmdActor: ActorRef[ControlCommand] =
                ctx.spawnAnonymous(
                  MotorOffCmdActor.behavior(commandResponseManager, galilHcd, axisName, axisConfig, loggerFactory)
                )

              val setup = Setup(Prefix("motoroff-single-axis"), CommandName("motorOff"), message.maybeObsId)

              commandResponseManager.addSubCommand(message.runId, setup.runId)

              // send it the message
              motorOffCmdActor ! setup

              actorList = actorList :+ motorOffCmdActor

            })
          }
          case _ =>
        }

      case "position" =>
        val axesValues         = message.paramSet.find(x => x.keyName == "axes").get.values
        val positionParam      = message.paramSet.find(x => x.keyName == "position").get
        val positionValues     = extractDoubleParam(positionParam).values
        val positionMethod     = message.paramSet.find(x => x.keyName == "positionMethod").get
        val positionCoordParam = message.paramSet.find(x => x.keyName == "positionCoord").get
        val positionCoordValue = extractStringParam(positionCoordParam).values.head

        // need pattern matching because of type erasure (need to get [String] back from [Any])
        axesValues match {

          case axesNames: Array[String] => {

            var i: Int = 0

            axesNames.foreach((axisName: String) => {

              val axisConfig = extractAxisConfig(assemblyConfig, axisName)

              // we need to spawn a new instance of the command actor
              val positionCmdActor: ActorRef[ControlCommand] =
                ctx.spawnAnonymous(
                  PositionCmdActor.behavior(commandResponseManager,
                                            galilHcd,
                                            axisName,
                                            axisConfig,
                                            locationService,
                                            loggerFactory)
                )

              // value for this axis, either in encoder counts or stage (mm)
              val position    = positionValues(i)
              val countsPerMm = axisConfig.getDouble("CountsPerMm")

              val counts: Int = calculateEncoderCounts(position, positionCoordValue, countsPerMm)

              val setup = Setup(Prefix("position-single-axis"), CommandName("position"), message.maybeObsId)
                .add(positionMethod)
                .add(countsKey.set(counts))

              i += 1

              commandResponseManager.addSubCommand(message.runId, setup.runId)

              // send it the message
              positionCmdActor ! setup

              actorList = actorList :+ positionCmdActor

            })
          }
          case _ =>
        }

    }

    log.info("command message handled")

    // stop all actors once command is completed
    Thread.sleep(200)
    actorList.foreach((actorRef: ActorRef[_]) => {
      ctx.stop(actorRef)
    })
    actorList = List.empty

  }

  def extractDoubleParam(input: Parameter[_]): Parameter[Double] = {
    input match {
      case x: Parameter[Double] => x
      case _                    => throw new Exception("unexpected exception")
    }
  }

  def extractStringParam(input: Parameter[_]): Parameter[String] = {
    input match {
      case x: Parameter[String] => x
      case _                    => throw new Exception("unexpected exception")
    }
  }

  def extractAxisConfig(assemblyConfig: Config, axisName: String): Config = {

    import scala.collection.JavaConverters._

    println("extractAxisConfig::" + axisName)

    assemblyConfig
      .getObjectList("stageConfig.axes")
      .asScala
      .filter((p: ConfigObject) => p.toConfig().getString("AxisName").equals(axisName))
      .head
      .toConfig

  }

  def calculateEncoderCounts(position: Double, positionCoord: String, countsPerMm: Double): Int = {

    if (positionCoord.equals("stage")) {
      (position * countsPerMm).toInt
    } else {
      position.toInt
    }
  }

}
