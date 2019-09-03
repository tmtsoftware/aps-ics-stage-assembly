package org.tmt.aps.ics.stagedeploy

import java.util.concurrent.TimeUnit

import akka.Done
import akka.util.Timeout
import csw.client.utils.Extensions.FutureExt
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.framework.CswClientWiring
import csw.framework.commons.CoordinatedShutdownReasons.ApplicationFinishedReason
import csw.location.models.{AkkaLocation, ComponentId, ComponentType}
import csw.location.models.ComponentType.{Assembly, HCD}
import csw.location.models.Connection.AkkaConnection
import csw.params.commands.{CommandName, CommandResponse, Setup}
import csw.params.core.generics.{Key, KeyType}
import csw.params.core.models.{ObsId, Prefix}
import org.tmt.aps.ics.stagedeploy.StageClientApp.maybeObsId

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
 * A demo client to test locating and communicating with the Galil HCD
 */
object StageClientApp extends App {

  lazy val clientWiring = new CswClientWiring
  import clientWiring._
  import wiring._
  import actorRuntime._

  private val axesKey: Key[String]           = KeyType.StringKey.make("axes")
  private val positionKey: Key[Double]       = KeyType.DoubleKey.make("position")
  private val positionMethodKey: Key[String] = KeyType.StringKey.make("positionMethod")
  val positionCoordKey: Key[String]          = KeyType.StringKey.make("positionCoord")

  implicit val timeout: FiniteDuration = FiniteDuration(30, TimeUnit.SECONDS)
  private val maybeObsId               = None
  private val prefix                   = Prefix("aps.ics.stage.client")

  def assemblyCommandService(assemblyName: String): CommandService = createCommandService(getAkkaLocation(assemblyName, Assembly))

  def hcdCommandService(hcdName: String): CommandService = createCommandService(getAkkaLocation(hcdName, HCD))

  def shutdown(): Done = wiring.actorRuntime.shutdown(ApplicationFinishedReason).await()

  private def getAkkaLocation(name: String, cType: ComponentType): AkkaLocation = {
    val maybeLocation = locationService.resolve(AkkaConnection(ComponentId(name, cType)), timeout).await()
    maybeLocation.getOrElse(throw new RuntimeException(s"Location not found for component: name:[$name] type:[${cType.name}]"))
  }

  private def createCommandService: AkkaLocation ⇒ CommandService = CommandServiceFactory.make

  println("ABOUT TO GET ASSEMBLY COMMAND")
  private val assemblyCommand = assemblyCommandService("PupilMaskStageAssembly")

  println("DONE")

  val resp1 = Await.result(init(maybeObsId), 10.seconds)
  println(s"init: $resp1")

  val resp2 = Await.result(home(maybeObsId), 10.seconds)
  println(s"home: $resp2")

  val resp3 = Await.result(motorOff(maybeObsId), 10.seconds)
  println(s"motorOff: $resp3")

  val resp4 = Await.result(position(maybeObsId), 10.seconds)
  println(s"position: $resp4")

  /**
   * Sends an init message to the Assembly and returns the response
   */
  private def init(obsId: Option[ObsId]): Future[CommandResponse] = {

    implicit val timeout: Timeout = Timeout(10.seconds)

    val axes = axesKey.set("pupil-x", "pupil-y", "pupil-phi")

    val setup = Setup(prefix, CommandName("init"), obsId).add(axes)

    assemblyCommand.submitAndWait(setup)

  }

  /**
   * Sends a home message to the Assembly and returns the response
   */
  def home(obsId: Option[ObsId]): Future[CommandResponse] = {

    implicit val timeout: Timeout = Timeout(10.seconds)

    val axes = axesKey.set("pupil-x", "pupil-y", "pupil-phi")

    val setup = Setup(prefix, CommandName("home"), obsId).add(axes)

    assemblyCommand.submitAndWait(setup)

  }

  /**
   * Sends a home message to the Assembly and returns the response
   */
  def motorOff(obsId: Option[ObsId]): Future[CommandResponse] = {

    implicit val timeout: Timeout = Timeout(10.seconds)

    val axes = axesKey.set("pupil-x", "pupil-y", "pupil-phi")

    val setup = Setup(prefix, CommandName("motorOff"), obsId).add(axes)

    assemblyCommand.submitAndWait(setup)

  }

  /**
   * Sends a position message to the Assembly and returns the response
   */
  def position(obsId: Option[ObsId]): Future[CommandResponse] = {
    implicit val timeout: Timeout = Timeout(10.seconds)

    val axes           = axesKey.set("pupil-x", "pupil-y", "pupil-phi")
    val position       = positionKey.set(2.3, 3.1, 4.1)
    val positionMethod = positionMethodKey.set("absolute")
    val positionCoord  = positionCoordKey.set("Stage")

    val setup = Setup(prefix, CommandName("position"), obsId)
      .add(axes)
      .add(position)
      .add(positionMethod)
      .add(positionCoord)

    assemblyCommand.submitAndWait(setup)

  }

}
