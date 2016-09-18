package mesosphere.marathon

import akka.Done
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.{ SpecInstances, InstancesBySpec }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.storage.repository.{ AppRepository, GroupRepository }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.verifyNoMoreInteractions
import org.scalatest.concurrent.{ PatienceConfiguration, ScalaFutures }
import org.scalatest.time.{ Millis, Span }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class SchedulerActionsTest
    extends MarathonActorSupport
    with MarathonSpec
    with Matchers
    with Mockito
    with ScalaFutures
    with GivenWhenThen {
  import system.dispatcher

  test("Reset rate limiter if application is stopped") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/myapp"))

    f.repo.delete(app.id) returns Future.successful(Done)
    f.taskTracker.specInstances(eq(app.id))(any) returns Future.successful(Iterable.empty[Task])

    f.scheduler.stopApp(app).futureValue(1.second)

    verify(f.queue).purge(app.id)
    verify(f.queue).resetDelay(app)
    verifyNoMoreInteractions(f.queue)
  }

  test("Task reconciliation sends known running and staged tasks and empty list") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/myapp"))
    val runningTask = MarathonTestHelper.runningTaskForApp(app.id)
    val stagedTask = MarathonTestHelper.stagedTaskForApp(app.id)

    import MarathonTestHelper.Implicits._
    val stagedTaskWithSlaveId =
      MarathonTestHelper.stagedTaskForApp(app.id)
        .withAgentInfo(_.copy(agentId = Some("slave 1")))

    val tasks = Set(runningTask, stagedTask, stagedTaskWithSlaveId)
    f.taskTracker.instancesBySpec() returns Future.successful(InstancesBySpec.of(SpecInstances.forInstances(app.id, tasks)))
    f.repo.ids() returns Source.single(app.id)

    f.scheduler.reconcileTasks(f.driver).futureValue(5.seconds)

    verify(f.driver).reconcileTasks(Set(
      runningTask,
      stagedTask,
      stagedTaskWithSlaveId
    ).flatMap(_.launched.flatMap(_.status.mesosStatus)).asJava)
    verify(f.driver).reconcileTasks(java.util.Arrays.asList())
  }

  test("Task reconciliation only one empty list, when no tasks are present in Marathon") {
    val f = new Fixture

    f.taskTracker.instancesBySpec() returns Future.successful(InstancesBySpec.empty)
    f.repo.ids() returns Source.empty

    f.scheduler.reconcileTasks(f.driver).futureValue

    verify(f.driver, times(1)).reconcileTasks(java.util.Arrays.asList())
  }

  test("Kill orphaned task") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/myapp"))
    val orphanedApp = AppDefinition(id = PathId("/orphan"))
    val task = MarathonTestHelper.runningTaskForApp(app.id)
    val orphanedTask = MarathonTestHelper.runningTaskForApp(orphanedApp.id)

    val tasksOfApp = SpecInstances.forInstances(app.id, Iterable(task))
    val tasksOfOrphanedApp = SpecInstances.forInstances(orphanedApp.id, Iterable(orphanedTask))

    f.taskTracker.instancesBySpec() returns Future.successful(InstancesBySpec.of(tasksOfApp, tasksOfOrphanedApp))
    f.repo.ids() returns Source.single(app.id)

    f.scheduler.reconcileTasks(f.driver).futureValue(5.seconds)

    verify(f.killService, times(1)).killTask(orphanedTask, KillReason.Orphaned)
  }

  test("Scale up correctly in case of lost tasks (active queue)") {
    val f = new Fixture

    Given("An active queue and lost tasks")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 15)
    val queued = QueuedInstanceInfo(
      app,
      instancesLeftToLaunch = 1,
      inProgress = true,
      finalInstanceCount = 15,
      unreachableInstances = 5,
      backOffUntil = f.clock.now())
    f.queue.get(app.id) returns Some(queued)
    f.taskTracker.countSpecInstancesSync(eq(app.id), any) returns (queued.finalInstanceCount - queued.unreachableInstances) // 10

    When("the app is scaled")
    f.scheduler.scale(app)

    Then("5 tasks should be placed onto the launchQueue")
    verify(f.queue, times(1)).add(app, 5)
  }

  test("Scale up correctly in case of lost tasks (inactive queue)") {
    val f = new Fixture

    Given("An active queue and lost tasks")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 15)
    f.queue.get(app.id) returns None
    f.taskTracker.countSpecInstancesSync(eq(app.id), any) returns 10

    When("the app is scaled")
    f.scheduler.scale(app)

    Then("5 tasks should be placed onto the launchQueue")
    verify(f.queue, times(1)).add(app, 5)
  }

  // This scenario is the following:
  // - There's an active queue and Marathon has 10 running + 5 staged tasks
  // - Marathon receives StatusUpdates for 5 previously LOST tasks
  // - A scale is initiated and Marathon realizes there are 5 tasks over capacity
  // => We expect Marathon to kill the 5 staged tasks
  test("Kill staged tasks in correct order in case lost tasks reappear") {
    val f = new Fixture

    Given("an active queue, staged tasks and 5 overCapacity")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 5)
    val queued = QueuedInstanceInfo(
      app,
      instancesLeftToLaunch = 0,
      inProgress = true,
      finalInstanceCount = 7,
      unreachableInstances = 0,
      backOffUntil = f.clock.now())

    def stagedTask(stagedAt: Long) = MarathonTestHelper.stagedTaskForApp(app.id, stagedAt = stagedAt)

    val staged_2 = stagedTask(2L)
    val staged_3 = stagedTask(3L)
    val tasks = Seq(
      MarathonTestHelper.runningTaskForApp(app.id),
      stagedTask(1L),
      MarathonTestHelper.runningTaskForApp(app.id),
      staged_3,
      MarathonTestHelper.runningTaskForApp(app.id),
      staged_2,
      MarathonTestHelper.runningTaskForApp(app.id)
    )

    f.queue.get(app.id) returns Some(queued)
    f.taskTracker.countSpecInstancesSync(eq(app.id), any) returns 7
    f.taskTracker.specInstancesSync(app.id) returns tasks
    When("the app is scaled")
    f.scheduler.scale(app)

    Then("the queue is purged")
    verify(f.queue, times(1)).purge(app.id)

    And("the youngest STAGED tasks are killed")
    verify(f.killService).killTasks(List(staged_3, staged_2), KillReason.ScalingApp)
    verifyNoMoreInteractions(f.driver)
    verifyNoMoreInteractions(f.killService)
  }

  def runningTask(id: Task.Id, stagedAt: Long) = MarathonTestHelper.runningTask(id, stagedAt = stagedAt, startedAt = stagedAt)
  def runningTask(runSpecId: PathId, stagedAt: Long) = MarathonTestHelper.runningTask(Task.Id.forRunSpec(runSpecId), stagedAt = stagedAt, startedAt = stagedAt)

  test("Kill running tasks in correct order in case of lost tasks") {
    val f = new Fixture

    Given("an inactive queue, running tasks and some overCapacity")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 5)

    val running_6 = runningTask(app.id, stagedAt = 6L)
    val running_7 = runningTask(app.id, stagedAt = 7L)
    val tasks = Seq(
      runningTask(app.id, stagedAt = 3L),
      running_7,
      runningTask(app.id, stagedAt = 1L),
      runningTask(app.id, stagedAt = 4L),
      runningTask(app.id, stagedAt = 5L),
      running_6,
      runningTask(app.id, stagedAt = 2L)
    )

    f.queue.get(app.id) returns None
    f.taskTracker.countSpecInstancesSync(eq(app.id), any) returns 7
    f.taskTracker.specInstancesSync(app.id) returns tasks
    When("the app is scaled")
    f.scheduler.scale(app)

    Then("the queue is purged")
    verify(f.queue, times(1)).purge(app.id)

    And("the youngest RUNNING tasks are killed")
    verify(f.killService).killTasks(List(running_7, running_6), KillReason.ScalingApp)
    verifyNoMoreInteractions(f.driver)
    verifyNoMoreInteractions(f.killService)
  }

  test("Kill staged and running tasks in correct order in case of lost tasks") {
    val f = new Fixture

    Given("an active queue, running tasks and some overCapacity")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 3)

    val queued = QueuedInstanceInfo(
      app,
      instancesLeftToLaunch = 0,
      inProgress = true,
      finalInstanceCount = 5,
      unreachableInstances = 0,
      backOffUntil = f.clock.now())

    def stagedTask(id: String, stagedAt: Long) = // linter:ignore:UnusedParameter
      MarathonTestHelper.stagedTaskForApp(stagedAt = stagedAt)
    def runningTask(id: String, stagedAt: Long) = // linter:ignore:UnusedParameter
      MarathonTestHelper.runningTaskForApp(stagedAt = stagedAt, startedAt = stagedAt)

    val staged_1 = stagedTask("staged-1", 1L)
    val running_4 = runningTask("running-4", stagedAt = 4L)
    val tasks = Seq(
      runningTask("running-3", stagedAt = 3L),
      running_4,
      staged_1,
      runningTask("running-1", stagedAt = 1L),
      runningTask("running-2", stagedAt = 2L)
    )

    f.queue.get(app.id) returns Some(queued)
    f.taskTracker.countSpecInstancesSync(eq(app.id), any) returns 5
    f.taskTracker.specInstancesSync(app.id) returns tasks
    When("the app is scaled")
    f.scheduler.scale(app)

    Then("the queue is purged")
    verify(f.queue, times(1)).purge(app.id)

    And("all STAGED tasks plus the youngest RUNNING tasks are killed")
    verify(f.killService).killTasks(List(staged_1, running_4), KillReason.ScalingApp)
    verifyNoMoreInteractions(f.driver)
    verifyNoMoreInteractions(f.killService)
  }

  import scala.language.implicitConversions
  implicit def durationToPatienceConfigTimeout(d: FiniteDuration): PatienceConfiguration.Timeout = {
    PatienceConfiguration.Timeout(Span(d.toMillis, Millis))
  }

  class Fixture {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[InstanceTracker]
    val driver = mock[SchedulerDriver]
    val killService = mock[KillService]
    val clock = ConstantClock()

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      killService
    )
  }

}
