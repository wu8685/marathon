package mesosphere.marathon

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.event.{ EventStream, LoggingReceive }
import akka.pattern.ask
import akka.stream.Materializer
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.core.election.{ ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.core.event.{ AppTerminatedEvent, DeploymentFailed, DeploymentSuccess }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{ DeploymentRepository, GroupRepository, ReadOnlyAppRepository }
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.upgrade.DeploymentManager._
import mesosphere.marathon.upgrade.{ DeploymentManager, DeploymentPlan }
import org.apache.mesos.Protos.Status
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

class LockingFailedException(msg: String) extends Exception(msg)

class MarathonSchedulerActor private (
  createSchedulerActions: ActorRef => SchedulerActions,
  deploymentManagerProps: SchedulerActions => Props,
  historyActorProps: Props,
  appRepository: ReadOnlyAppRepository,
  deploymentRepository: DeploymentRepository,
  healthCheckManager: HealthCheckManager,
  killService: KillService,
  launchQueue: LaunchQueue,
  marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
  electionService: ElectionService,
  eventBus: EventStream,
  cancellationTimeout: FiniteDuration = 1.minute)(implicit val mat: Materializer) extends Actor
    with ActorLogging with Stash {
  import context.dispatcher
  import mesosphere.marathon.MarathonSchedulerActor._

  var lockedApps = Set.empty[PathId]
  var schedulerActions: SchedulerActions = _
  var deploymentManager: ActorRef = _
  var historyActor: ActorRef = _
  var activeReconciliation: Option[Future[Status]] = None

  override def preStart(): Unit = {
    schedulerActions = createSchedulerActions(self)
    deploymentManager = context.actorOf(deploymentManagerProps(schedulerActions), "DeploymentManager")
    historyActor = context.actorOf(historyActorProps, "HistoryActor")

    electionService.subscribe(self)
  }

  override def postStop(): Unit = {
    electionService.unsubscribe(self)
  }

  def receive: Receive = suspended

  def suspended: Receive = LoggingReceive.withLabel("suspended"){
    case LocalLeadershipEvent.ElectedAsLeader =>
      log.info("Starting scheduler actor")
      deploymentRepository.all().runWith(Sink.seq).onComplete {
        case Success(deployments) => self ! RecoverDeployments(deployments)
        case Failure(_) => self ! RecoverDeployments(Nil)
      }

    case RecoverDeployments(deployments) =>
      deployments.foreach { plan =>
        log.info(s"Recovering deployment:\n$plan")
        deploy(context.system.deadLetters, Deploy(plan, force = false))
      }

      log.info("Scheduler actor ready")
      unstashAll()
      context.become(started)
      self ! ReconcileHealthChecks

    case LocalLeadershipEvent.Standby =>
    // ignored
    // FIXME: When we get this while recovering deployments, we become active anyway
    // and drop this message.

    case _ => stash()
  }

  def started: Receive = LoggingReceive.withLabel("started")(sharedHandlers orElse {
    case LocalLeadershipEvent.Standby =>
      log.info("Suspending scheduler actor")
      healthCheckManager.removeAll()
      deploymentManager ! StopAllDeployments
      lockedApps = Set.empty
      context.become(suspended)

    case LocalLeadershipEvent.ElectedAsLeader => // ignore

    case ReconcileTasks =>
      import akka.pattern.pipe
      import context.dispatcher
      val reconcileFuture = activeReconciliation match {
        case None =>
          log.info("initiate task reconciliation")
          val newFuture = schedulerActions.reconcileTasks(driver)
          activeReconciliation = Some(newFuture)
          newFuture.onFailure {
            case NonFatal(e) => log.error(e, "error while reconciling tasks")
          }
          newFuture
            // the self notification MUST happen before informing the initiator
            // if we want to ensure that we trigger a new reconciliation for
            // the first call after the last ReconcileTasks.answer has been received.
            .andThen { case _ => self ! ReconcileFinished }
        case Some(active) =>
          log.info("task reconciliation still active, reusing result")
          active
      }
      reconcileFuture.map(_ => ReconcileTasks.answer).pipeTo(sender)

    case ReconcileFinished =>
      log.info("task reconciliation has finished")
      activeReconciliation = None

    case ReconcileHealthChecks =>
      schedulerActions.reconcileHealthChecks()

    case ScaleApps => schedulerActions.scaleApps()

    case cmd @ ScaleApp(appId) =>
      val origSender = sender()
      withLockFor(appId) {
        val res = schedulerActions.scale(appId)

        if (origSender != context.system.deadLetters)
          res.sendAnswer(origSender, cmd)

        res andThen {
          case _ => self ! cmd.answer // unlock app
        }
      }

    case cmd: CancelDeployment =>
      deploymentManager forward cmd

    case cmd @ Deploy(plan, force) =>
      deploy(sender(), cmd)

    case cmd @ KillTasks(appId, tasks) =>
      val origSender = sender()
      @SuppressWarnings(Array("all")) /* async/await */
      def killTasks(): Unit = {
        val res = async { // linter:ignore UnnecessaryElseBranch
          await(killService.killTasks(tasks, KillReason.KillingTasksViaApi))
          val app = await(appRepository.get(appId))
          app.foreach(schedulerActions.scale)
        }

        res onComplete { _ =>
          self ! cmd.answer // unlock app
        }

        res.sendAnswer(origSender, cmd)
      }
      withLockFor(appId) { killTasks() }
  })

  /**
    * handlers for messages that unlock apps and to retrieve running deployments
    */
  def sharedHandlers: Receive = {
    case DeploymentFinished(plan) =>
      lockedApps --= plan.affectedRunSpecIds
      deploymentSuccess(plan)

    case DeploymentManager.DeploymentFailed(plan, reason) =>
      lockedApps --= plan.affectedRunSpecIds
      deploymentFailed(plan, reason)

    case AppScaled(id) => lockedApps -= id

    case TasksKilled(appId, _) => lockedApps -= appId

    case RetrieveRunningDeployments =>
      deploymentManager forward RetrieveRunningDeployments
  }

  /**
    * Waits for all the apps affected by @plan to be unlocked
    * and starts @plan. If it receives a CancellationTimeoutExceeded
    * message, it will mark the deployment as failed and go into
    * the started state.
    *
    * @param plan The deployment plan we are trying to execute.
    * @param origSender The original sender of the Deploy message.
    * @return
    */
  @SuppressWarnings(Array("all")) // async/await
  def awaitCancellation(plan: DeploymentPlan, origSender: ActorRef, cancellationHandler: Cancellable): Receive =
    sharedHandlers.andThen[Unit] { _ =>
      if (tryDeploy(plan, origSender)) {
        cancellationHandler.cancel()
      }
    } orElse {
      case CancellationTimeoutExceeded =>
        val reason = new TimeoutException("Exceeded timeout for canceling conflicting deployments.")
        async { // linter:ignore UnnecessaryElseBranch
          await(deploymentFailed(plan, reason))
          origSender ! CommandFailed(Deploy(plan, force = true), reason)
          unstashAll()
          context.become(started)
        }
      case _ => stash()
    }

  /**
    * If all required apps are unlocked, start the deployment,
    * unstash all messages and put actor in started state
    *
    * @param plan The deployment plan that has been sent with force=true
    * @param origSender The original sender of the deployment
    */
  def tryDeploy(plan: DeploymentPlan, origSender: ActorRef): Boolean = {
    val affectedApps = plan.affectedRunSpecIds
    if (!lockedApps.exists(affectedApps)) {
      deploy(origSender, Deploy(plan, force = false))
      unstashAll()
      context.become(started)
      true
    } else {
      false
    }
  }

  /**
    * Tries to acquire the lock for the given appId.
    * If it succeeds it executes the given function,
    * otherwise the result will contain an AppLockedException.
    */
  def withLockFor[A](appIds: Set[PathId])(f: => A): Try[A] = {
    // there's no need for synchronization here, because this is being
    // executed inside an actor, i.e. single threaded
    val conflicts = lockedApps intersect appIds
    if (conflicts.isEmpty) {
      lockedApps ++= appIds
      Try(f)
    } else {
      Failure(new LockingFailedException("Failed to acquire locks."))
    }
  }

  /**
    * Tries to acquire the lock for the given appId.
    * If it succeeds it executes the given function,
    * otherwise the result will contain an AppLockedException.
    */
  def withLockFor[A](appId: PathId)(f: => A): Try[A] =
    withLockFor(Set(appId))(f)

  // there has to be a better way...
  @SuppressWarnings(Array("OptionGet"))
  def driver: SchedulerDriver = marathonSchedulerDriverHolder.driver.get

  def deploy(origSender: ActorRef, cmd: Deploy): Unit = {
    val plan = cmd.plan
    val ids = plan.affectedRunSpecIds

    val res = withLockFor(ids) {
      deploy(driver, plan)
    }

    res match {
      case Success(_) =>
        if (origSender != Actor.noSender) origSender ! cmd.answer
      case Failure(e: LockingFailedException) if cmd.force =>
        deploymentManager ! CancelConflictingDeployments(plan)
        val cancellationHandler = context.system.scheduler.scheduleOnce(
          cancellationTimeout,
          self,
          CancellationTimeoutExceeded)

        context.become(awaitCancellation(plan, origSender, cancellationHandler))
      case Failure(e: LockingFailedException) =>
        deploymentManager.ask(RetrieveRunningDeployments)(2.seconds)
          .mapTo[RunningDeployments]
          .foreach {
            case RunningDeployments(plans) =>
              def intersectsWithNewPlan(existingPlan: DeploymentPlan): Boolean = {
                existingPlan.affectedRunSpecIds.intersect(plan.affectedRunSpecIds).nonEmpty
              }
              val relatedDeploymentIds: Seq[String] = plans.collect {
                case DeploymentStepInfo(p, _, _, _) if intersectsWithNewPlan(p) => p.id
              }
              origSender ! CommandFailed(cmd, AppLockedException(relatedDeploymentIds))
          }
    }
  }

  def deploy(driver: SchedulerDriver, plan: DeploymentPlan): Unit = {
    deploymentRepository.store(plan).foreach { done =>
      deploymentManager ! PerformDeployment(driver, plan)
    }
  }

  def deploymentSuccess(plan: DeploymentPlan): Future[Unit] = {
    log.info(s"Deployment of ${plan.target.id} successful")
    eventBus.publish(DeploymentSuccess(plan.id, plan))
    deploymentRepository.delete(plan.id).map(_ => ())
  }

  def deploymentFailed(plan: DeploymentPlan, reason: Throwable): Future[Unit] = {
    log.error(reason, s"Deployment of ${plan.target.id} failed")
    plan.affectedRunSpecIds.foreach(appId => launchQueue.purge(appId))
    eventBus.publish(DeploymentFailed(plan.id, plan))
    reason match {
      case _: DeploymentCanceledException =>
        deploymentRepository.delete(plan.id).map(_ => ())
      case _ =>
        Future.successful(())
    }
  }
}

object MarathonSchedulerActor {
  @SuppressWarnings(Array("MaxParameters"))
  def props(
    createSchedulerActions: ActorRef => SchedulerActions,
    deploymentManagerProps: SchedulerActions => Props,
    historyActorProps: Props,
    appRepository: ReadOnlyAppRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    killService: KillService,
    launchQueue: LaunchQueue,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    electionService: ElectionService,
    eventBus: EventStream)(implicit mat: Materializer): Props = {
    Props(new MarathonSchedulerActor(
      createSchedulerActions,
      deploymentManagerProps,
      historyActorProps,
      appRepository,
      deploymentRepository,
      healthCheckManager,
      killService,
      launchQueue,
      marathonSchedulerDriverHolder,
      electionService,
      eventBus
    ))
  }

  case class RecoverDeployments(deployments: Seq[DeploymentPlan])

  sealed trait Command {
    def answer: Event
  }

  case object ReconcileTasks extends Command {
    def answer: Event = TasksReconciled
  }

  private case object ReconcileFinished

  case object ReconcileHealthChecks

  case object ScaleApps

  case class ScaleApp(appId: PathId) extends Command {
    def answer: Event = AppScaled(appId)
  }

  case class Deploy(plan: DeploymentPlan, force: Boolean = false) extends Command {
    def answer: Event = DeploymentStarted(plan)
  }

  case class KillTasks(appId: PathId, tasks: Iterable[Instance]) extends Command {
    def answer: Event = TasksKilled(appId, tasks.map(_.instanceId))
  }

  case object RetrieveRunningDeployments

  sealed trait Event
  case class AppScaled(appId: PathId) extends Event
  case object TasksReconciled extends Event
  case class DeploymentStarted(plan: DeploymentPlan) extends Event
  case class TasksKilled(appId: PathId, taskIds: Iterable[Instance.Id]) extends Event

  case class RunningDeployments(plans: Seq[DeploymentStepInfo])

  case class CommandFailed(cmd: Command, reason: Throwable) extends Event

  case object CancellationTimeoutExceeded

  implicit class AnswerOps[A](val f: Future[A]) extends AnyVal {
    def sendAnswer(receiver: ActorRef, cmd: Command)(implicit ec: ExecutionContext): Future[A] = {
      f onComplete {
        case Success(_) =>
          receiver ! cmd.answer

        case Failure(t) =>
          receiver ! CommandFailed(cmd, t)
      }

      f
    }
  }
}

class SchedulerActions(
    appRepository: ReadOnlyAppRepository,
    groupRepository: GroupRepository,
    healthCheckManager: HealthCheckManager,
    instanceTracker: InstanceTracker,
    launchQueue: LaunchQueue,
    eventBus: EventStream,
    val schedulerActor: ActorRef,
    val killService: KillService)(implicit ec: ExecutionContext, mat: Materializer) {

  private[this] val log = LoggerFactory.getLogger(getClass)

  // TODO move stuff below out of the scheduler

  def startApp(runSpec: RunSpec): Unit = {
    log.info(s"Starting runSpec ${runSpec.id}")
    scale(runSpec)
  }

  def stopApp(runSpec: RunSpec): Future[_] = {
    healthCheckManager.removeAllFor(runSpec.id)

    log.info(s"Stopping runSpec ${runSpec.id}")
    instanceTracker.specInstances(runSpec.id).map { tasks =>
      tasks.foreach {
        instance =>
          if (instance.isLaunched) {
            log.info("Killing {}", instance.instanceId)
            killService.killTask(instance, KillReason.DeletingApp)
          }
      }
      launchQueue.purge(runSpec.id)
      launchQueue.resetDelay(runSpec)

      // The tasks will be removed from the InstanceTracker when their termination
      // was confirmed by Mesos via a task update.

      eventBus.publish(AppTerminatedEvent(runSpec.id))
    }
  }

  def scaleApps(): Future[Unit] = {
    appRepository.ids().runWith(Sink.set).andThen {
      case Success(appIds) => for (appId <- appIds) schedulerActor ! ScaleApp(appId)
      case Failure(t) => log.warn("Failed to get task names", t)
    }.map(_ => ())
  }

  /**
    * Make sure all apps are running the configured amount of tasks.
    *
    * Should be called some time after the framework re-registers,
    * to give Mesos enough time to deliver task updates.
    *
    * @param driver scheduler driver
    */
  def reconcileTasks(driver: SchedulerDriver): Future[Status] = {
    // TODO(jdef) pods
    appRepository.ids().runWith(Sink.set).flatMap { appIds =>
      instanceTracker.instancesBySpec().map { instances =>
        val knownTaskStatuses = appIds.flatMap { appId =>
          instances.specInstances(appId).flatMap(_.tasks.flatMap(_.mesosStatus))
        }

        (instances.allSpecIdsWithInstances -- appIds).foreach { unknownAppId =>
          log.warn(
            s"App $unknownAppId exists in InstanceTracker, but not App store. " +
              "The app was likely terminated. Will now expunge."
          )
          instances.specInstances(unknownAppId).foreach { orphanTask =>
            log.info(s"Killing ${orphanTask.instanceId}")
            killService.killTask(orphanTask, KillReason.Orphaned)
          }
        }

        log.info("Requesting task reconciliation with the Mesos master")
        log.debug(s"Tasks to reconcile: $knownTaskStatuses")
        if (knownTaskStatuses.nonEmpty)
          driver.reconcileTasks(knownTaskStatuses.asJava)

        // in addition to the known statuses send an empty list to get the unknown
        driver.reconcileTasks(java.util.Arrays.asList())
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def reconcileHealthChecks(): Unit = {
    async { // linter:ignore UnnecessaryElseBranch
      val group = await(groupRepository.root())
      val apps = group.transitiveAppsById.keys
      apps.foreach(healthCheckManager.reconcileWith)
    }
  }

  /**
    * Ensures current application parameters (resource requirements, URLs,
    * command, and constraints) are applied consistently across running
    * application instances.
    */
  @SuppressWarnings(Array("EmptyMethod", "UnusedMethodParameter"))
  private def update( // linter:ignore UnusedParameter
    driver: SchedulerDriver,
    updatedApp: AppDefinition,
    appUpdate: AppUpdate): Unit = {
    // TODO: implement app instance restart logic
  }

  /**
    * Make sure the app is running the correct number of instances
    */
  // FIXME: extract computation into a function that can be easily tested
  def scale(runSpec: RunSpec): Unit = {
    import SchedulerActions._

    def inQueueOrRunning(t: Instance) = t.isCreated || t.isRunning || t.isStaging || t.isStarting || t.isKilling

    val launchedCount = instanceTracker.countSpecInstancesSync(runSpec.id, inQueueOrRunning)

    val targetCount = runSpec.instances

    if (targetCount > launchedCount) {
      log.info(s"Need to scale ${runSpec.id} from $launchedCount up to $targetCount instances")

      val queuedOrRunning = launchQueue.get(runSpec.id).map {
        info => info.finalInstanceCount - info.unreachableInstances
      }.getOrElse(launchedCount)

      val toQueue = targetCount - queuedOrRunning

      if (toQueue > 0) {
        log.info(s"Queueing $toQueue new tasks for ${runSpec.id} ($queuedOrRunning queued or running)")
        launchQueue.add(runSpec, toQueue)
      } else {
        log.info(s"Already queued or started $queuedOrRunning tasks for ${runSpec.id}. Not scaling.")
      }
    } else if (targetCount < launchedCount) {
      log.info(s"Scaling ${runSpec.id} from $launchedCount down to $targetCount instances")
      launchQueue.purge(runSpec.id)

      val toKill = instanceTracker.specInstancesSync(runSpec.id).toSeq
        .filter(t => runningOrStaged.contains(t.state.status))
        .sortWith(sortByStateAndTime)
        .take(launchedCount - targetCount)

      log.info("Killing tasks {}", toKill.map(_.instanceId))
      killService.killTasks(toKill, KillReason.ScalingApp)
    } else {
      log.info(s"Already running ${runSpec.instances} instances of ${runSpec.id}. Not scaling.")
    }
  }

  def scale(appId: PathId): Future[Unit] = {
    currentAppVersion(appId).map {
      case Some(app) => scale(app)
      case _ => log.warn(s"App $appId does not exist. Not scaling.")
    }
  }

  def currentAppVersion(appId: PathId): Future[Option[AppDefinition]] =
    appRepository.get(appId)
}

private[this] object SchedulerActions {
  def sortByStateAndTime(a: Instance, b: Instance): Boolean = {
    runningOrStaged(b.state.status).compareTo(runningOrStaged(a.state.status)) match {
      case 0 => a.state.since.compareTo(b.state.since) > 0
      case value: Int => value > 0
    }

  }

  val runningOrStaged: Map[InstanceStatus, Int] = Map(
    InstanceStatus.Staging -> 1,
    InstanceStatus.Starting -> 2,
    InstanceStatus.Running -> 3)
}
