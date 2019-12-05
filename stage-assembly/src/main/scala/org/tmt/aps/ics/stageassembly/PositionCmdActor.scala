package org.tmt.aps.ics.stageassembly

import java.io.{PrintWriter, StringWriter}

import akka.actor.ActorRefFactory
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.Config
import csw.command.api.scaladsl.CommandService
import csw.command.client.{CommandResponseManager, CommandServiceFactory}
import csw.command.client.models.matchers.MatcherResponses.{MatchCompleted, MatchFailed}
import csw.command.client.models.matchers.{Matcher, MatcherResponse}
import csw.location.api.scaladsl.LocationService
import csw.location.models.{AkkaLocation, ComponentId, ComponentType}
import csw.location.models.Connection.AkkaConnection
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse.{Accepted, Completed, Error, Invalid}
import csw.params.commands.{CommandName, CommandResponse, ControlCommand, Setup}
import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.{Id, ObsId, Prefix, Struct}
import csw.params.core.states.StateName
import csw.command.client.extensions.AkkaLocationExt.RichAkkaLocation

import scala.async.Async._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

object PositionCmdActor {
  def behavior(commandResponseManager: CommandResponseManager,
               galilHcd: Option[CommandService],
               axisName: String,
               axisConfig: Config,
               locationService: LocationService,
               loggerFactory: LoggerFactory): Behavior[ControlCommand] =
    Behaviors.setup(
      ctx ⇒ PositionCmdActor(ctx, commandResponseManager, galilHcd, axisName, axisConfig, locationService, loggerFactory)
    )
}

case class PositionCmdActor(ctx: ActorContext[ControlCommand],
                            commandResponseManager: CommandResponseManager,
                            galilHcd: Option[CommandService],
                            axisName: String,
                            axisConfig: Config,
                            locationService: LocationService,
                            loggerFactory: LoggerFactory)
    extends AbstractBehavior[ControlCommand] {

  private val log = loggerFactory.getLogger

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  implicit val timeout: Timeout             = Timeout(3.seconds)

  private val prefix: Prefix = Prefix("aps.ics.stage")
  private val maybeObsId     = None

  private val axisKey: Key[Char]  = KeyType.CharKey.make("axis")
  private val countsKey: Key[Int] = KeyType.IntKey.make("counts")
  private val speedKey: Key[Int]  = KeyType.IntKey.make("speed")

  override def onMessage(msg: ControlCommand): Behavior[ControlCommand] = {
    msg match {
      case (x: ControlCommand) => processCommand(x)
      case _                   => log.error(s"unhandled message in AxesMapper onMessage: $msg")
    }
    this
  }

  private def processCommand(message: ControlCommand): Unit = {

    log.info("processing command")

    val axis          = axisConfig.getString("Channel").toCharArray.head
    val positionSpeed = axisConfig.getInt("PositionSpeed")

    val countsParam = message.paramSet.find(x => x.keyName == "counts").get
    val counts      = extractIntParam(countsParam).values.head

    val methodParam    = message.paramSet.find(x => x.keyName == "positionMethod").get
    val positionMethod = extractStringParam(methodParam).values.head

    val output = new StringBuilder()

    val axisType = axisConfig.getString("AxisType")

    axisType match {
      case "servo" => {

        try {
          positionMethod match {
            case "absolute" =>
              val resp1 = Await.result(setAbsTarget(maybeObsId, axis, counts), 3.seconds)
              if (resp1.isInstanceOf[Error]) throw new Exception(s"setRelTarget $resp1")
              else output.append(s"\nsetRelTarget $resp1, ")
            case "relative" =>
              val resp2 = Await.result(setRelTarget(maybeObsId, axis, counts), 3.seconds)
              if (resp2.isInstanceOf[Error]) throw new Exception(s"setAbsTarget $resp2")
              else output.append(s"\nsetAbsTarget $resp2, ")

          }

          // set speed
          val resp4 = Await.result(setSpeed(maybeObsId, axis, positionSpeed), 3.seconds)
          if (resp4.isInstanceOf[Error]) throw new Exception(s"setSpeed $resp4")
          else output.append(s"\nsetSpeed $resp4, ")

          // motor on
          val resp5 = Await.result(servoHere(maybeObsId, axis), 3.seconds)
          if (resp5.isInstanceOf[Error]) throw new Exception(s"servoHere $resp5")
          else output.append(s"\nservoHere() $resp5")

          val resp3 = Await.result(beginMotion(maybeObsId, axis, counts), 30.seconds)
          if (resp3.isInstanceOf[Error]) throw new Exception(s"beginMotion $resp3") else output.append(s"\nbeginMotion $resp3, ")

          output.toString()

          // the param set is only a single param for an axis current state (we should invent this in the HCD)
          // the published state should be by axis (or the whole thing as we might have thought)

          log.info("command completed")

          commandResponseManager.updateSubCommand(CommandResponse.Completed(message.runId))

        } catch {

          case e: Exception =>
            val sw = new StringWriter
            e.printStackTrace(new PrintWriter(sw))
            log.error(sw.toString)

            commandResponseManager.updateSubCommand(CommandResponse.Error(message.runId, e.getMessage))
          case _: Throwable =>
            commandResponseManager.updateSubCommand(CommandResponse.Error(message.runId, "Unexpected error"))

        }
      }
      case "stepper" => {

        try {
          positionMethod match {
            case "absolute" =>
              val resp1 = Await.result(setAbsTarget(maybeObsId, axis, counts), 3.seconds)
              if (resp1.isInstanceOf[Error]) throw new Exception(s"setRelTarget $resp1")
              else output.append(s"\nsetRelTarget $resp1, ")
            case "relative" =>
              val resp2 = Await.result(setRelTarget(maybeObsId, axis, counts), 3.seconds)
              if (resp2.isInstanceOf[Error]) throw new Exception(s"setAbsTarget $resp2")
              else output.append(s"\nsetAbsTarget $resp2, ")

          }

          // set speed
          val resp4 = Await.result(setSpeed(maybeObsId, axis, positionSpeed), 3.seconds)
          if (resp4.isInstanceOf[Error]) throw new Exception(s"setSpeed $resp4")
          else output.append(s"\nsetSpeed $resp4, ")

          // motor on
          val resp5 = Await.result(servoHere(maybeObsId, axis), 3.seconds)
          if (resp5.isInstanceOf[Error]) throw new Exception(s"servoHere $resp5")
          else output.append(s"\nservoHere() $resp5")

          // begin motion
          val resp3 = Await.result(beginMotion(maybeObsId, axis, counts), 30.seconds)
          if (resp3.isInstanceOf[Error]) throw new Exception(s"beginMotion $resp3") else output.append(s"\nbeginMotion $resp3, ")

          output.toString()

          // the param set is only a single param for an axis current state (we should invent this in the HCD)
          // the published state should be by axis (or the whole thing as we might have thought)

          log.info("command completed")

          commandResponseManager.updateSubCommand(CommandResponse.Completed(message.runId))

        } catch {

          case e: Exception =>
            val sw = new StringWriter
            e.printStackTrace(new PrintWriter(sw))
            log.error(sw.toString)

            commandResponseManager.updateSubCommand(CommandResponse.Error(message.runId, e.getMessage))
          case _: Throwable =>
            commandResponseManager.updateSubCommand(CommandResponse.Error(message.runId, "Unexpected error"))

        }

      }
      case _ => log.error(s"bad axis type")
    }

  }

  def extractIntParam(input: Parameter[_]): Parameter[Int] = {
    input match {
      case x: Parameter[Int] => x
      case _                 => throw new Exception("unexpected exception")
    }
  }

  def extractStringParam(input: Parameter[_]): Parameter[String] = {
    input match {
      case x: Parameter[String] => x
      case _                    => throw new Exception("unexpected exception")
    }
  }

  /**
   * Sends a setRelTarget message to the HCD and returns the response
   */
  def setRelTarget(obsId: Option[ObsId], axis: Char, count: Int): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setRelTarget"), obsId)
          .add(axisKey.set(axis))
          .add(countsKey.set(count))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a setAbsTarget message to the HCD and returns the response
   */
  def setAbsTarget(obsId: Option[ObsId], axis: Char, count: Int): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setAbsTarget"), obsId)
          .add(axisKey.set(axis))
          .add(countsKey.set(count))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a setSpeed message to the HCD and returns the response
   */
  def setSpeed(obsId: Option[ObsId], axis: Char, speed: Int): Future[CommandResponse] = {
    galilHcd match {
      case Some(hcd) =>
        val setup = Setup(prefix, CommandName("setMotorSpeed"), obsId)
          .add(axisKey.set(axis))
          .add(speedKey.set(speed))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a servoHere message to the HCD and returns the response
   */
  def servoHere(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
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
   * Sends a beginMotion message to the HCD and returns the response
   */
  def beginMotion(obsId: Option[ObsId], axis: Char, matchCounts: Int): Future[CommandResponse] = {

    implicit val context: ActorRefFactory = ctx.system.toUntyped

    implicit val actorSystem = ctx.system

    implicit val mat: ActorMaterializer = ActorMaterializer()

    galilHcd match {
      case Some(hcd) =>
        val setupWithMatcher = Setup(prefix, CommandName("beginMotion"), obsId)
          .add(axisKey.set(axis))

        // FIXME: use this line instead of the test one when integrating
        //val param: Parameter[Int] = KeyType.IntKey.make("motorPostion").set(matchCounts)
        val param: Parameter[Int] = KeyType.IntKey.make("motorPosition").set(100)

        val p1: Parameter[Int] = KeyType.IntKey.make("motorPosition").set(100)
        val p2: Parameter[Int] = KeyType.IntKey.make("positionError").set(100)

        // FIXME: will this work?
        val matchParam = KeyType.StructKey.make(axis.toString()).set(Struct(Set(param)))

        val stateName: StateName = StateName("GalilState")

        log.info(s"matchParam = $matchParam")

        val param1: Parameter[_] = KeyType.StructKey.make("A").set(Struct(Set(p1, p2)))
        val param2: Parameter[_] = KeyType.StructKey.make("B").set(Struct(Set(p1, p2)))
        val param3: Parameter[_] = KeyType.StructKey.make("C").set(Struct(Set(p1, p2)))
        val param4: Parameter[_] = KeyType.StructKey.make("D").set(Struct(Set(p1, p2)))
        val param5: Parameter[_] = KeyType.StructKey.make("E").set(Struct(Set(p1, p2)))
        val param6: Parameter[_] = KeyType.StructKey.make("F").set(Struct(Set(p1, p2)))
        val param7: Parameter[_] = KeyType.StructKey.make("G").set(Struct(Set(p1, p2)))
        val param8: Parameter[_] = KeyType.StructKey.make("H").set(Struct(Set(p1, p2)))
        val param9: Parameter[_] = KeyType.IntKey.make("amplifierStatus").set(100)

        val testParamSet: Set[Parameter[_]] = Set(param1, param2, param3, param4, param5, param6, param7, param8, param9)

        // create a DemandMatcher which specifies the desired state to be matched.
        val demandMatcher = GalilPositionMatcher(axis, 100, prefix, stateName, withUnits = false, timeout)

        //val testCurrentState = CurrentState(prefix, stateName, testParamSet)

        //log.info(s"match eval = ${demandMatcher.check(testCurrentState)}")

        val hcdLocF       = locationService.resolve(AkkaConnection(ComponentId("IcsStimulusGalilHcd", ComponentType.HCD)), 5.seconds)
        val maybeLocation = Await.result(hcdLocF, 10.seconds)
        val hcdRef        = maybeLocation.map(_.componentRef).get

        //val hcdRef  = Await.result(hcdLocF, 10.seconds).map(_.componentRef).get

        // TODO: use this code when location based prefix is available instead of hard coded prefix

        //val locationOption = Await.result(hcdLocF, 10.seconds)
        //val location       = locationOption.get
        //val galilPrefix = location.maybePrefix.getOrElse("NONE")
        //log.info(s"prefix = $galilPrefix")

        // create matcher instance
        val matcher = new Matcher(hcdRef, demandMatcher)

        // start the matcher so that it is ready to receive state published by the source
        val matcherResponseF: Future[MatcherResponse] = matcher.start

        log.info("matcher started")

        // submit command and if the command is successfully validated, check for matching of demand state against current state
        val eventualCommandResponse: Future[CommandResponse] = async {
          val initialResponse = await(hcd.submit(setupWithMatcher))
          initialResponse match {
            case _: Accepted ⇒
              log.info("Command Accepted")
              val matcherResponse = await(matcherResponseF)
              log.info(s"matcherResponse = $matcherResponse")
              // create appropriate response if demand state was matched from among the published state or otherwise
              matcherResponse match {
                case MatchCompleted  ⇒ Completed(setupWithMatcher.runId)
                case MatchFailed(ex) ⇒ Error(setupWithMatcher.runId, ex.getMessage)
              }
            case invalid: Invalid ⇒
              matcher.stop()
              invalid
            case x ⇒
              x
          }
        }

        eventualCommandResponse

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

}
