package mesosphere.marathon.core.instance

import java.util.Base64

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.Protos
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.update.InstanceUpdateEffect
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.task.update.TaskUpdateOperation
import mesosphere.marathon.core.task.update.TaskUpdateEffect
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml._
import mesosphere.marathon.state.{ MarathonState, PathId, RunSpec, Timestamp }
import mesosphere.mesos.Placed
import org.apache._
import org.apache.mesos.Protos.Attribute
import play.api.libs.json.{ Reads, Writes }
import org.slf4j.{ Logger, LoggerFactory }
// TODO PODs remove api import
import play.api.libs.json.{ Format, JsResult, JsString, JsValue, Json }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

// TODO: remove MarathonState stuff once legacy persistence is gone
case class Instance(
    instanceId: Instance.Id,
    agentInfo: Instance.AgentInfo,
    state: InstanceState,
    tasksMap: Map[Task.Id, Task]) extends MarathonState[Protos.Json, Instance] with Placed {

  // TODO(PODS): check consumers of this def and see if they can use the map instead
  val tasks = tasksMap.values

  val runSpecVersion: Timestamp = state.version
  val runSpecId: PathId = instanceId.runSpecId
  val isLaunched: Boolean = tasksMap.valuesIterator.forall(task => task.launched.isDefined)

  // TODO(PODS): verify functionality and reduce complexity
  // scalastyle:off cyclomatic.complexity
  def update(op: InstanceUpdateOperation): InstanceUpdateEffect = {
    // TODO(PODS): implement logic:
    // - propagate the change to the task
    // - calculate the new instance status based on the state of the task

    // TODO(PODS): make sure state transitions are allowed. maybe implement a simple state machine?
    op match {
      case InstanceUpdateOperation.ForceExpunge(_) =>
        InstanceUpdateEffect.Expunge(this)

      case InstanceUpdateOperation.MesosUpdate(instance, status, mesosStatus, now) =>
        // TODO(PODS): calculate the overall state afterwards
        val taskId = Task.Id(mesosStatus.getTaskId)
        val effect = tasks.find(_.taskId == taskId).map { task =>
          task.update(TaskUpdateOperation.MesosUpdate(status, mesosStatus))
        }.getOrElse(TaskUpdateEffect.Failure(s"$taskId not found in $instanceId"))

        effect match {
          case TaskUpdateEffect.Update(newTaskState) =>
            val updated: Instance = updatedInstance(newTaskState, now)
            InstanceUpdateEffect.Update(updated, Some(this))

          case TaskUpdateEffect.Expunge(newTaskState) =>
            val updated: Instance = updatedInstance(newTaskState, now)
            // TODO(PODS): should a TaskUpdateEffect.Expunge always lead to an InstanceUpdateEffect.Expunge?
            InstanceUpdateEffect.Expunge(updated)

          case TaskUpdateEffect.Noop =>
            InstanceUpdateEffect.Noop(instance.instanceId)

          case TaskUpdateEffect.Failure(cause) =>
            InstanceUpdateEffect.Failure(cause)
        }

      case InstanceUpdateOperation.LaunchOnReservation(_, version, status, hostPorts) =>
        if (this.isReserved) {
          val updated: Instance = ???
          InstanceUpdateEffect.Update(updated, Some(this))
        } else {
          InstanceUpdateEffect.Failure("LaunchOnReservation can only be applied to a reserved instance")
        }

      case InstanceUpdateOperation.ReservationTimeout(_) =>
        if (this.isReserved) {
          InstanceUpdateEffect.Expunge(this)
        } else {
          InstanceUpdateEffect.Failure("LaunchOnReservation can only be applied to a reserved instance")
        }

      case InstanceUpdateOperation.LaunchEphemeral(instance) =>
        InstanceUpdateEffect.Failure("LaunchEphemeral cannot be passed to an existing instance")

      case InstanceUpdateOperation.Reserve(_) =>
        InstanceUpdateEffect.Failure("LaunchEphemeral cannot be passed to an existing instance")

      case InstanceUpdateOperation.Revert(oldState) =>
        InstanceUpdateEffect.Failure("LaunchEphemeral cannot be passed to an existing instance")
    }
  }
  // scalastyle:on

  override def mergeFromProto(message: Protos.Json): Instance = {
    Json.parse(message.getJson).as[Instance]
  }
  override def mergeFromProto(bytes: Array[Byte]): Instance = {
    mergeFromProto(Protos.Json.parseFrom(bytes))
  }
  override def toProto: Protos.Json = {
    Protos.Json.newBuilder().setJson(Json.stringify(Json.toJson(this))).build()
  }
  override def version: Timestamp = Timestamp.zero

  override def hostname: String = agentInfo.host

  override def attributes: Seq[Attribute] = agentInfo.attributes

  private[instance] def updatedInstance(updatedTask: Task, now: Timestamp): Instance = {
    val updatedTasks = tasksMap.updated(updatedTask.taskId, updatedTask)
    copy(tasksMap = updatedTasks, state = newInstanceState(updatedTasks, now))
  }

  private[instance] def newInstanceState(newTaskMap: Map[Task.Id, Task], timestamp: Timestamp): InstanceState = {
    val tasks = newTaskMap.values

    //compute the new instance status
    val stateMap = tasks.groupBy(_.status.taskStatus)
    val status = if (stateMap.size == 1) {
      // all tasks have the same status -> this is the instance status
      stateMap.keys.head
    } else {
      // since we don't have a distinct state, we remove states where all tasks have to agree on
      // and search for a distinct state
      val distinctStates = Instance.AllInstanceStatuses.foldLeft(stateMap) { (ds, status) => ds - status }
      Instance.DistinctInstanceStatuses.find(distinctStates.contains).getOrElse {
        // if no distinct state is found all tasks are in different AllInstanceStatuses
        // we pick the first matching one
        Instance.AllInstanceStatuses.find(stateMap.contains).getOrElse {
          // if we come here, something is wrong, since we covered all existing states
          Instance.log.error(s"Could not compute new instance state for state map: $stateMap")
          InstanceStatus.Unknown
        }

      }
    }

    // an instance is healthy, if all tasks are healthy
    // an instance is unhealthy, if at least one task is unhealthy
    // otherwise the health is unknown
    val healthy = {
      val tasksHealth = tasks.map(_.status.mesosStatus.flatMap(p => if (p.hasHealthy) Some(p.getHealthy) else None))
      if (tasksHealth.exists(_.exists(healthy => !healthy))) Some(false)
      else if (tasksHealth.forall(_.exists(identity))) Some(true)
      else None
    }

    if (this.state.status == status && this.state.healthy == healthy) this.state
    else InstanceState(status, timestamp, this.state.version, healthy)
  }
}

object Instance {

  // TODO PODs remove api import
  import mesosphere.marathon.api.v2.json.Formats

  // required for legacy store, remove when legacy storage is removed.
  def apply(): Instance = {
    new Instance(Instance.Id(""), AgentInfo("", None, Nil),
      InstanceState(InstanceStatus.Unknown, Timestamp.zero, Timestamp.zero, healthy = None), Map.empty[Task.Id, Task])
  }

  private val log: Logger = LoggerFactory.getLogger(classOf[Instance])

  /**
    * An instance can only have this status, if all tasks of the intance have this status.
    * The order of the status is important.
    * If 2 tasks are Running and 2 tasks already Finished, the final status is Running.
    */
  private val AllInstanceStatuses: Seq[InstanceStatus] = Seq(
    InstanceStatus.Created,
    InstanceStatus.Reserved,
    InstanceStatus.Running,
    InstanceStatus.Finished,
    InstanceStatus.Killed
  )

  /**
    * An instance has this status, if at least one tasks of the instance has this status.
    * The order of the status is important.
    * If one task is Error and one task is Staging, the instance status is Error.
    */
  private val DistinctInstanceStatuses: Seq[InstanceStatus] = Seq(
    InstanceStatus.Error,
    InstanceStatus.Failed,
    InstanceStatus.Gone,
    InstanceStatus.Dropped,
    InstanceStatus.Unreachable,
    InstanceStatus.Killing,
    InstanceStatus.Starting,
    InstanceStatus.Staging,
    InstanceStatus.Unknown
  )

  def instancesById(tasks: Iterable[Instance]): Map[Instance.Id, Instance] =
    tasks.iterator.map(task => task.instanceId -> task).toMap

  // TODO ju remove apply
  def apply(task: Task): Instance = new Instance(task.taskId.instanceId, task.agentInfo,
    InstanceState(
      status = task.status.taskStatus,
      since = task.status.startedAt.getOrElse(task.status.stagedAt),
      version = task.version.getOrElse(Timestamp.zero),
      healthy = None),
    Map(task.taskId -> task))

  case class InstanceState(status: InstanceStatus, since: Timestamp, version: Timestamp, healthy: Option[Boolean])

  case class Id(idString: String) extends Ordered[Id] {
    lazy val runSpecId: PathId = Id.runSpecId(idString)
    // TODO(jdef) move this somewhere else?
    lazy val mesosExecutorId: mesos.Protos.ExecutorID = mesos.Protos.ExecutorID.newBuilder().setValue(idString).build()

    override def toString: String = s"instance [$idString]"

    override def compare(that: Instance.Id): Int =
      if (this.getClass == that.getClass)
        idString.compare(that.idString)
      else this.compareTo(that)
  }

  object Id {
    private val InstanceIdRegex = """^(.+)[\._]([^_\.]+)$""".r
    private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

    def apply(executorId: mesos.Protos.ExecutorID): Id = new Id(executorId.getValue)

    def runSpecId(instanceId: String): PathId = {
      instanceId match {
        case InstanceIdRegex(runSpecId, uuid) => PathId.fromSafePath(runSpecId)
        case _ => throw new RuntimeException("unable to extract instanceId from " + instanceId)
      }
    }

    def forRunSpec(id: PathId): Id = Instance.Id(id.safePath + ".instance-" + uuidGenerator.generate())
  }

  /**
    * Info relating to the host on which the Instance has been launched.
    */
  case class AgentInfo(
    host: String,
    agentId: Option[String],
    attributes: Seq[mesos.Protos.Attribute])

  object AgentInfo {
    def apply(offer: org.apache.mesos.Protos.Offer): AgentInfo = AgentInfo(
      host = offer.getHostname,
      agentId = Some(offer.getSlaveId.getValue),
      attributes = offer.getAttributesList.asScala.toVector
    )
  }

  implicit class InstanceStatusComparison(val instance: Instance) extends AnyVal {
    def isReserved: Boolean = instance.state.status == InstanceStatus.Reserved
    def isCreated: Boolean = instance.state.status == InstanceStatus.Created
    def isError: Boolean = instance.state.status == InstanceStatus.Error
    def isFailed: Boolean = instance.state.status == InstanceStatus.Failed
    def isFinished: Boolean = instance.state.status == InstanceStatus.Finished
    def isKilled: Boolean = instance.state.status == InstanceStatus.Killed
    def isKilling: Boolean = instance.state.status == InstanceStatus.Killing
    def isRunning: Boolean = instance.state.status == InstanceStatus.Running
    def isStaging: Boolean = instance.state.status == InstanceStatus.Staging
    def isStarting: Boolean = instance.state.status == InstanceStatus.Starting
    def isUnreachable: Boolean = instance.state.status == InstanceStatus.Unreachable
    def isGone: Boolean = instance.state.status == InstanceStatus.Gone
    def isUnknown: Boolean = instance.state.status == InstanceStatus.Unknown
    def isDropped: Boolean = instance.state.status == InstanceStatus.Dropped
  }

  /**
    * Marathon has requested (or will request) that this instance be launched by Mesos.
    *
    * @param instance is the thing that Marathon wants to launch
    * @param hostPorts is a list of actual (no dynamic!) hort-ports that are being requested from Mesos.
    */
  case class LaunchRequest(
    instance: Instance,
    hostPorts: Seq[Int])

  import Formats.TimestampFormat
  implicit object AttributeFormat extends Format[mesos.Protos.Attribute] {
    override def reads(json: JsValue): JsResult[Attribute] = {
      json.validate[String].map { base64 =>
        mesos.Protos.Attribute.parseFrom(Base64.getDecoder.decode(base64))
      }
    }

    override def writes(o: Attribute): JsValue = {
      JsString(Base64.getEncoder.encodeToString(o.toByteArray))
    }
  }
  implicit val agentFormat: Format[AgentInfo] = Json.format[AgentInfo]
  implicit val idFormat: Format[Instance.Id] = Json.format[Instance.Id]
  implicit val instanceStatusFormat: Format[InstanceStatus] = Json.format[InstanceStatus]
  implicit val instanceStateFormat: Format[InstanceState] = Json.format[InstanceState]
  implicit val instanceJsonFormat: Format[Instance] = Json.format[Instance]
  implicit lazy val tasksMapFormat: Format[Map[Task.Id, Task]] = Format(
    Reads.of[Map[String, Task]].map {
      _.map { case (k, v) => Task.Id(k) -> v }
    },
    Writes[Map[Task.Id, Task]] { m =>
      val stringToTask = m.map {
        case (k, v) => k.idString -> v
      }
      Json.toJson(stringToTask)
    }
  )

  /**
    * generate a pod instance status RAML for some instance.
    * @throws IllegalArgumentException if you provide a non-pod `spec`
    */
  def asPodInstanceStatus(spec: RunSpec, instance: Instance): PodInstanceStatus = {

    // TODO(jdef) pods move this code somewhere else, perhaps into a conversion package?

    // BLOCKED: need capability to get the container name from Task somehow, current thinking is that Task.Id will
    // provide such an API.

    val pod: PodDefinition = spec match {
      case x: PodDefinition => x
      case _ => throw new IllegalArgumentException(s"expected a pod spec instead of $spec")
    }

    assume(
      pod.id == instance.instanceId.runSpecId,
      s"pod id ${pod.id} should match spec id of the instance ${instance.instanceId.runSpecId}")

    // TODO(jdef) associate task w/ container by name, allocated host ports should be in relative order
    // def endpoints = instance.tasks.map(_.launched.map(_.hostPorts))

    def containerStatus: Seq[ContainerStatus] = instance.tasks.map { task =>
      val since = task.status.startedAt.getOrElse(task.status.stagedAt).toOffsetDateTime // TODO(jdef) inaccurate

      // some other layer should provide termination history
      // TODO(jdef) message, conditions
      ContainerStatus(
        name = task.taskId.mesosTaskId.getValue, //TODO(jdef) pods this is wrong, should be the container name from spec
        status = task.status.taskStatus.toMesosStateName,
        statusSince = since,
        containerId = task.launchedMesosId.map(_.getValue),
        endpoints = Seq.empty, //TODO(jdef) pods, report endpoint health, allocated host ports here
        lastUpdated = since, // TODO(jdef) pods fixme
        lastChanged = since // TODO(jdef) pods.fixme
      )
    }(collection.breakOut)

    val derivedStatus: PodInstanceState = instance.state.status match {
      case InstanceStatus.Created | InstanceStatus.Reserved => PodInstanceState.Pending
      case InstanceStatus.Staging | InstanceStatus.Starting => PodInstanceState.Staging
      case InstanceStatus.Error | InstanceStatus.Failed | InstanceStatus.Finished | InstanceStatus.Killed |
        InstanceStatus.Gone | InstanceStatus.Dropped | InstanceStatus.Unknown | InstanceStatus.Killing |
        InstanceStatus.Unreachable => PodInstanceState.Terminal
      case InstanceStatus.Running =>
        if (instance.state.healthy.getOrElse(true)) PodInstanceState.Stable else PodInstanceState.Degraded
    }

    val networkStatus: Seq[NetworkStatus] = instance.tasks.flatMap { task =>
      task.mesosStatus.filter(_.hasContainerStatus).fold(Seq.empty[NetworkStatus]) { mesosStatus =>
        mesosStatus.getContainerStatus.getNetworkInfosList.asScala.map { networkInfo =>
          NetworkStatus(
            name = if (networkInfo.hasName) Some(networkInfo.getName) else None,
            addresses = networkInfo.getIpAddressesList.asScala
              .filter(_.hasIpAddress).map(_.getIpAddress)(collection.breakOut)
          )
        }(collection.breakOut)
      }.groupBy(_.name).values.map { toMerge =>
        val networkStatus: NetworkStatus = toMerge.reduceLeft { (merged, single) =>
          merged.copy(addresses = merged.addresses ++ single.addresses)
        }
        networkStatus.copy(addresses = networkStatus.addresses.distinct)
      }
    }(collection.breakOut)

    val resources: Option[Resources] = instance.state.status match {
      case InstanceStatus.Staging | InstanceStatus.Starting | InstanceStatus.Running =>
        val containerResources = pod.containers.map(_.resources).fold(Resources(0, 0, 0, 0)) { (acc, res) =>
          acc.copy(
            cpus = acc.cpus + res.cpus,
            mem = acc.mem + res.mem,
            disk = acc.disk + res.disk,
            gpus = acc.gpus + res.gpus
          )
        }
        Some(containerResources.copy(
          cpus = containerResources.cpus + PodDefinition.DefaultExecutorCpus,
          mem = containerResources.mem + PodDefinition.DefaultExecutorMem
        // TODO(jdef) pods account for executor disk space, see TaskGroupBuilder for reference
        ))
      case _ => None
    }

    // TODO(jdef) message, conditions
    PodInstanceStatus(
      id = instance.instanceId.idString,
      status = derivedStatus,
      statusSince = instance.state.since.toOffsetDateTime,
      agentHostname = Some(instance.agentInfo.host),
      resources = resources,
      networks = networkStatus,
      containers = containerStatus,
      lastUpdated = instance.state.since.toOffsetDateTime, // TODO(jdef) pods we don't actually track lastUpdated yet
      lastChanged = instance.state.since.toOffsetDateTime
    )
  } // asPodInstanceStatus
}
