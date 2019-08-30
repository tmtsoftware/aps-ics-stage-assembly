package org.tmt.aps.ics.stageassembly

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigObject}
import csw.command.api.scaladsl.CommandService
import csw.command.client.models.framework.ComponentInfo
import csw.framework.CurrentStatePublisher
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.core.generics.{KeyType, Parameter}
import csw.params.core.models.{Prefix, Struct, Units}
import csw.params.core.states.{CurrentState, StateName}
import org.tmt.aps.ics.stageassembly.EventPublisherMessage.{CurrentStateEventMessage, LocationEventMessage}

import scala.concurrent.duration._

// Add messages here
sealed trait EventPublisherMessage

object EventPublisherMessage {

  case class LocationEventMessage(galilHcd: Option[CommandService]) extends EventPublisherMessage
  case class CurrentStateEventMessage(currentState: CurrentState)   extends EventPublisherMessage

  val axisStatusText = Array(
    "Motor Off",
    "3rd Phase of HM in Progress",
    "Latch is armed",
    "Motion is making final deceleration",
    "Motion is stopping due to ST of Limit Switch",
    "Motion is slewing",
    "Mode of Motion Contour",
    "Negative Direction Move",
    "Mode of Motion Coord. Motion",
    "2nd Phase of HM complete or FI command issued",
    "1st Phase of HM complete",
    "Home (HM) in Progress",
    "(FE) Find Edge in Progress",
    "Mode of Motion PA only",
    "Mode of Motion PA or PR",
    "Move in Progress"
  )
}

object EventPublisherActor {
  def behavior(componentInfo: ComponentInfo,
               galilHcd: Option[CommandService],
               assemblyConfig: Config,
               currentStatePublisher: CurrentStatePublisher,
               loggerFactory: LoggerFactory): Behavior[EventPublisherMessage] =
    Behaviors.setup(
      ctx ⇒ EventPublisherActor(ctx, componentInfo, galilHcd, assemblyConfig, currentStatePublisher, loggerFactory)
    )
}

case class EventPublisherActor(ctx: ActorContext[EventPublisherMessage],
                               componentInfo: ComponentInfo,
                               galilHcd: Option[CommandService],
                               assemblyConfig: Config,
                               currentStatePublisher: CurrentStatePublisher,
                               loggerFactory: LoggerFactory)
    extends AbstractBehavior[EventPublisherMessage] {

  private val log = loggerFactory.getLogger

  implicit val timeout: Timeout = Timeout(3.seconds)

  private val prefix: Prefix = componentInfo.prefix
  private val maybeObsId     = None

  override def onMessage(msg: EventPublisherMessage): Behavior[EventPublisherMessage] = {
    msg match {
      case (x: LocationEventMessage) => {
        EventPublisherActor.behavior(componentInfo, x.galilHcd, assemblyConfig, currentStatePublisher, loggerFactory)
      }
      case (x: CurrentStateEventMessage) => {
        publishCurrentState(x)
        this
      }
      case _ => {
        log.error(s"unhandled message in AxesMapper onMessage: $msg")
        this
      }
    }

  }

  /**
   * publish the current state
   */
  def publishCurrentState(currentStateMessage: CurrentStateEventMessage): Unit = {

    import scala.collection.JavaConverters._

    val hcdCurrentState = currentStateMessage.currentState

    val stateName: StateName = StateName("AssemblyState")
    var assemblyCurrentState = CurrentState(prefix, stateName)

    // extract each axis in this assembly only from the HCD current state
    val axes = assemblyConfig
      .getObjectList("stageConfig.axes")
      .asScala
      .foreach((x: ConfigObject) => {
        val axisConfig = x.toConfig

        val axisName    = axisConfig.getString("AxisName")
        val channel     = axisConfig.getString("Channel")
        val countsPerMm = axisConfig.getInt("CountsPerMm")

        val channelValueKey  = KeyType.StructKey.make(channel)
        val motorPositionKey = KeyType.IntKey.make("motorPosition")
        val statusKey        = KeyType.ShortKey.make("status")

        val struct: Struct = hcdCurrentState.get(channelValueKey).get.value(0)

        //val struct: Struct = extractStructParam(axisParam).value(0)

        val axisCurrentState = CurrentState(prefix, StateName("dummy"), struct.paramSet)

        val encoderCounts = axisCurrentState.get(motorPositionKey).get.value(0)

        val axisStatus = axisCurrentState.get(statusKey).get.value(0)

        //val motorPositionParam = struct.paramSet.find((x: Parameter[_]) => x.keyName == "motorPosition")

        //val encoderCounts: Int = extractIntParam(motorPositionParam.get).value(0)

        // perform conversion
        val position: Double = encoderCounts.toDouble / countsPerMm.toDouble

        // build up axisName, channel, stagePosition (mm) parameter structure
        val assemblyAxisParam     = KeyType.StringKey.make("axisName").set(axisName)
        val assemblyChannelParam  = KeyType.CharKey.make("channel").set(channel.charAt(0))
        val assemblyPositionParam = KeyType.DoubleKey.make("position").set(position).withUnits(Units.millimeter)
        val axisStatusParam       = statusKey.set(axisStatus)
        val assemblyStructParam =
          KeyType.StructKey
            .make(axisName)
            .set(Struct(Set(assemblyAxisParam, assemblyChannelParam, assemblyPositionParam, axisStatusParam)))

        // add this axis to the current state
        assemblyCurrentState = assemblyCurrentState.add(assemblyStructParam)

      })

    log.debug(s"publishCurrentState::assemblyCurrentState = $assemblyCurrentState")

    currentStatePublisher.publish(assemblyCurrentState)
  }

  def extractStructParam(input: Parameter[_]): Parameter[Struct] = {
    input match {
      case x: Parameter[Struct] => x
      case _                    => throw new Exception("unexpected exception")
    }
  }

  def extractIntParam(input: Parameter[_]): Parameter[Int] = {
    input match {
      case x: Parameter[Int] => x
      case _                 => throw new Exception("unexpected exception")
    }
  }

}
