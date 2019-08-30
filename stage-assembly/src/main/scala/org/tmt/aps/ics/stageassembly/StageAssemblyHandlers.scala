package org.tmt.aps.ics.stageassembly

import java.nio.file.Paths

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.stream.typed.scaladsl.ActorMaterializer
import com.typesafe.config.Config
import csw.command.api.CurrentStateSubscription
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.command.client.messages.TopLevelActorMessage
import csw.config.api.ConfigData
import csw.config.api.scaladsl.ConfigClientService
import csw.config.client.scaladsl.ConfigClientFactory
import csw.framework.exceptions.FailureStop
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.models.{AkkaLocation, LocationRemoved, LocationUpdated, TrackingEvent}
import csw.params.commands.CommandResponse._
import csw.params.commands.ControlCommand
import org.tmt.aps.ics.stageassembly.EventPublisherMessage.CurrentStateEventMessage

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._

/**
 * Domain specific logic should be written in below handlers.
 * This handlers gets invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to StageHcd,
 * This will be first validated in the supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw/commons/framework.html
 */
class StageAssemblyHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger

  // Handle to the config client service

  private val configClient: ConfigClientService = cswCtx.configClientService

  // Load the configuration from the configuration service
  val assemblyConfig: Config = getAssemblyConfig

  // reference to the template HCD
  var galilHcd: Option[CommandService] = None

  var subscription: Option[CurrentStateSubscription] = None

  val eventPublisherActor: ActorRef[EventPublisherMessage] =
    ctx.spawnAnonymous(
      EventPublisherActor.behavior(componentInfo, galilHcd, assemblyConfig, currentStatePublisher, loggerFactory)
    )

  override def initialize(): Future[Unit] = {
    log.info("Initializing stage assembly...")
    Future.unit
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {

    log.info(s"got a tracking event: $trackingEvent")

    trackingEvent match {
      case LocationUpdated(location) =>
        // new Hcd Command service is stored as the Some option
        galilHcd = Some(CommandServiceFactory.make(location.asInstanceOf[AkkaLocation])(ctx.system))

        // set up Hcd CurrentState subscription to be handled by the monitor actor
        subscription = Some(galilHcd.get.subscribeCurrentState({
          eventPublisherActor ! CurrentStateEventMessage(_)
        }))

      case LocationRemoved(_) =>
        galilHcd = None

        subscription.get.unsubscribe()
    }

  }

  override def validateCommand(controlCommand: ControlCommand): ValidateCommandResponse = Accepted(controlCommand.runId)

  override def onSubmit(controlCommand: ControlCommand): SubmitResponse = {

    val multiAxisCommandHandler: ActorRef[ControlCommand] =
      ctx.spawnAnonymous(
        MultiAxisCommandHandler.behavior(commandResponseManager, galilHcd, assemblyConfig, locationService, loggerFactory)
      )

    multiAxisCommandHandler ! controlCommand

    // FIXME: this is a guess at what should be here
    Started(controlCommand.runId)

  }

  override def onOneway(controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Future[Unit] = { Future.unit }

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  case class ConfigNotAvailableException() extends FailureStop("Configuration not available. Initialization failure.")

  private def getAssemblyConfig: Config = {

    implicit val actorSystem = ctx.system

    implicit val mat: ActorMaterializer = ActorMaterializer()

    val configData: ConfigData = Await.result(getAssemblyConfigData, 10.seconds)

    log.info("configData = " + configData)

    Await.result(configData.toConfigObject, 3.seconds)

  }

  private def getAssemblyConfigData: Future[ConfigData] = {

    log.info("loading assembly configuration")

    configClient.getActive(Paths.get(s"config/org/tmt/aps/ics/${componentInfo.name}.conf")).flatMap {
      case Some(config) ⇒ Future.successful(config) // do work
      case None         ⇒
        // required configuration could not be found in the configuration service. Component can choose to stop until the configuration is made available in the
        // configuration service and started again
        throw ConfigNotAvailableException()
    }
  }

}
