package mesosphere.marathon.state

import java.util.regex.Pattern

import com.wix.accord._
import com.wix.accord.combinators.GeneralPurposeCombinators
import com.wix.accord.dsl._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.core.health.MesosCommandHealthCheck
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.api.serialization.{ ContainerSerializer, EnvVarRefSerializer, PortDefinitionSerializer, ResidencySerializer, SecretsSerializer }
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.core.health.{ HealthCheck, MarathonHealthCheck, MesosHealthCheck }
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.stream._

import mesosphere.marathon.plugin.validation.RunSpecValidator
import mesosphere.marathon.state.AppDefinition.VersionInfo.{ FullVersionInfo, OnlyVersion }
import mesosphere.marathon.state.AppDefinition.{ Labels, VersionInfo }
import mesosphere.marathon.{ Features, Protos, plugin }
import mesosphere.mesos.TaskBuilder

import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.{ Protos => mesos }

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.Try

case class AppDefinition(

  id: PathId = AppDefinition.DefaultId,

  cmd: Option[String] = AppDefinition.DefaultCmd,

  args: Option[Seq[String]] = AppDefinition.DefaultArgs,

  user: Option[String] = AppDefinition.DefaultUser,

  env: Map[String, EnvVarValue] = AppDefinition.DefaultEnv,

  instances: Int = AppDefinition.DefaultInstances,

  cpus: Double = AppDefinition.DefaultCpus,

  mem: Double = AppDefinition.DefaultMem,

  disk: Double = AppDefinition.DefaultDisk,

  gpus: Int = AppDefinition.DefaultGpus,

  executor: String = AppDefinition.DefaultExecutor,

  constraints: Set[Constraint] = AppDefinition.DefaultConstraints,

  fetch: Seq[FetchUri] = AppDefinition.DefaultFetch,

  storeUrls: Seq[String] = AppDefinition.DefaultStoreUrls,

  portDefinitions: Seq[PortDefinition] = AppDefinition.DefaultPortDefinitions,

  requirePorts: Boolean = AppDefinition.DefaultRequirePorts,

  backoff: FiniteDuration = AppDefinition.DefaultBackoff,

  backoffFactor: Double = AppDefinition.DefaultBackoffFactor,

  maxLaunchDelay: FiniteDuration = AppDefinition.DefaultMaxLaunchDelay,

  container: Option[Container] = AppDefinition.DefaultContainer,

  healthChecks: Set[_ <: HealthCheck] = AppDefinition.DefaultHealthChecks,

  readinessChecks: Seq[ReadinessCheck] = AppDefinition.DefaultReadinessChecks,

  taskKillGracePeriod: Option[FiniteDuration] = AppDefinition.DefaultTaskKillGracePeriod,

  dependencies: Set[PathId] = AppDefinition.DefaultDependencies,

  upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,

  labels: Map[String, String] = Labels.Default,

  acceptedResourceRoles: Option[Set[String]] = None,

  ipAddress: Option[IpAddress] = None,

  versionInfo: VersionInfo = VersionInfo.NoVersion,

  residency: Option[Residency] = AppDefinition.DefaultResidency,

  secrets: Map[String, Secret] = AppDefinition.DefaultSecrets)
    extends RunSpec with plugin.RunSpec with MarathonState[Protos.ServiceDefinition, AppDefinition] {

  import mesosphere.mesos.protos.Implicits._

  require(
    ipAddress.isEmpty || portDefinitions.isEmpty,
    s"IP address ($ipAddress) and ports ($portDefinitions) are not allowed at the same time")

  val portNumbers: Seq[Int] = portDefinitions.map(_.port)

  val isResident: Boolean = residency.isDefined

  val isSingleInstance: Boolean = labels.get(Labels.SingleInstanceApp).contains("true")
  val volumes: Iterable[Volume] = container.fold(Seq.empty[Volume])(_.volumes)
  val persistentVolumes: Iterable[PersistentVolume] = volumes.collect { case vol: PersistentVolume => vol }
  val externalVolumes: Iterable[ExternalVolume] = volumes.collect { case vol: ExternalVolume => vol }

  val diskForPersistentVolumes: Double = persistentVolumes.map(_.persistent.size).sum.toDouble

  def toProto: Protos.ServiceDefinition = {
    val commandInfo = TaskBuilder.commandInfo(
      runSpec = this,
      taskId = None,
      host = None,
      hostPorts = Seq.empty,
      envPrefix = None
    )
    val cpusResource = ScalarResource(Resource.CPUS, cpus)
    val memResource = ScalarResource(Resource.MEM, mem)
    val diskResource = ScalarResource(Resource.DISK, disk)
    val gpusResource = ScalarResource(Resource.GPUS, gpus.toDouble)
    val appLabels = labels.map {
      case (key, value) =>
        mesos.Parameter.newBuilder
          .setKey(key)
          .setValue(value)
          .build
    }

    val builder = Protos.ServiceDefinition.newBuilder
      .setId(id.toString)
      .setCmd(commandInfo)
      .setInstances(instances)
      .addAllPortDefinitions(portDefinitions.map(PortDefinitionSerializer.toProto).asJava)
      .setRequirePorts(requirePorts)
      .setBackoff(backoff.toMillis)
      .setBackoffFactor(backoffFactor)
      .setMaxLaunchDelay(maxLaunchDelay.toMillis)
      .setExecutor(executor)
      .addAllConstraints(constraints.asJava)
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(gpusResource)
      .addAllHealthChecks(healthChecks.map(_.toProto).asJava)
      .setUpgradeStrategy(upgradeStrategy.toProto)
      .addAllDependencies(dependencies.map(_.toString).asJava)
      .addAllStoreUrls(storeUrls.asJava)
      .addAllLabels(appLabels.asJava)
      .addAllSecrets(secrets.map(SecretsSerializer.toProto).asJava)
      .addAllEnvVarReferences(env.flatMap(EnvVarRefSerializer.toProto).asJava)

    ipAddress.foreach { ip => builder.setIpAddress(ip.toProto) }
    container.foreach { c => builder.setContainer(ContainerSerializer.toProto(c)) }
    readinessChecks.foreach { r => builder.addReadinessCheckDefinition(ReadinessCheckSerializer.toProto(r)) }
    taskKillGracePeriod.foreach { t => builder.setTaskKillGracePeriod(t.toMillis) }

    acceptedResourceRoles.foreach { acceptedResourceRoles =>
      val roles = Protos.ResourceRoles.newBuilder()
      acceptedResourceRoles.seq.foreach(roles.addRole)
      builder.setAcceptedResourceRoles(roles)
    }

    builder.setVersion(version.toString)
    versionInfo match {
      case fullInfo: FullVersionInfo =>
        builder.setLastScalingAt(fullInfo.lastScalingAt.toDateTime.getMillis)
        builder.setLastConfigChangeAt(fullInfo.lastConfigChangeAt.toDateTime.getMillis)
      case _ => // ignore
    }

    residency.foreach { r => builder.setResidency(ResidencySerializer.toProto(r)) }

    builder.build
  }

  def mergeFromProto(proto: Protos.ServiceDefinition): AppDefinition = {
    val envMap: Map[String, EnvVarValue] = EnvVarValue(
      proto.getCmd.getEnvironment.getVariablesList.map {
        v => v.getName -> v.getValue
      }(collection.breakOut))

    val envRefs: Map[String, EnvVarValue] =
      proto.getEnvVarReferencesList.flatMap(EnvVarRefSerializer.fromProto)(collection.breakOut)

    val resourcesMap: Map[String, Double] =
      proto.getResourcesList.map {
        r => r.getName -> (r.getScalar.getValue: Double)
      }(collection.breakOut)

    val argsOption =
      if (proto.getCmd.getArgumentsCount > 0)
        Some(proto.getCmd.getArgumentsList.toSeq)
      else None

    //Precondition: either args or command is defined
    val commandOption =
      if (argsOption.isEmpty && proto.getCmd.hasValue && proto.getCmd.getValue.nonEmpty)
        Some(proto.getCmd.getValue)
      else None

    val containerOption = if (proto.hasContainer) Some(ContainerSerializer.fromProto(proto.getContainer)) else None

    val acceptedResourceRoles: Option[Set[String]] =
      if (proto.hasAcceptedResourceRoles)
        Some(proto.getAcceptedResourceRoles.getRoleList.toSet)
      else
        None

    val versionInfoFromProto =
      if (proto.hasLastScalingAt)
        FullVersionInfo(
          version = Timestamp(proto.getVersion),
          lastScalingAt = Timestamp(proto.getLastScalingAt),
          lastConfigChangeAt = Timestamp(proto.getLastConfigChangeAt)
        )
      else
        OnlyVersion(Timestamp(proto.getVersion))

    val ipAddressOption = if (proto.hasIpAddress) Some(IpAddress.fromProto(proto.getIpAddress)) else None

    val residencyOption = if (proto.hasResidency) Some(ResidencySerializer.fromProto(proto.getResidency)) else None

    // TODO (gkleiman): we have to be able to read the ports from the deprecated field in order to perform migrations
    // until the deprecation cycle is complete.
    val portDefinitions =
      if (proto.getPortsCount > 0) PortDefinitions(proto.getPortsList.map(_.intValue)(collection.breakOut): _*)
      else proto.getPortDefinitionsList.map(PortDefinitionSerializer.fromProto)(collection.breakOut)

    AppDefinition(
      id = PathId(proto.getId),
      user = if (proto.getCmd.hasUser) Some(proto.getCmd.getUser) else None,
      cmd = commandOption,
      args = argsOption,
      executor = proto.getExecutor,
      instances = proto.getInstances,
      portDefinitions = portDefinitions,
      requirePorts = proto.getRequirePorts,
      backoff = proto.getBackoff.milliseconds,
      backoffFactor = proto.getBackoffFactor,
      maxLaunchDelay = proto.getMaxLaunchDelay.milliseconds,
      constraints = proto.getConstraintsList.toSet,
      acceptedResourceRoles = acceptedResourceRoles,
      cpus = resourcesMap.getOrElse(Resource.CPUS, this.cpus),
      mem = resourcesMap.getOrElse(Resource.MEM, this.mem),
      disk = resourcesMap.getOrElse(Resource.DISK, this.disk),
      gpus = resourcesMap.getOrElse(Resource.GPUS, this.gpus.toDouble).toInt,
      env = envMap ++ envRefs,
      fetch = proto.getCmd.getUrisList.map(FetchUri.fromProto)(collection.breakOut),
      storeUrls = proto.getStoreUrlsList.toSeq,
      container = containerOption,
      healthChecks = proto.getHealthChecksList.map(HealthCheck.fromProto).toSet,
      readinessChecks =
        proto.getReadinessCheckDefinitionList.map(ReadinessCheckSerializer.fromProto)(collection.breakOut),
      taskKillGracePeriod = if (proto.hasTaskKillGracePeriod) Some(proto.getTaskKillGracePeriod.milliseconds)
      else None,
      labels = proto.getLabelsList.map { p => p.getKey -> p.getValue }(collection.breakOut),
      versionInfo = versionInfoFromProto,
      upgradeStrategy =
        if (proto.hasUpgradeStrategy) UpgradeStrategy.fromProto(proto.getUpgradeStrategy)
        else UpgradeStrategy.empty,
      dependencies = proto.getDependenciesList.map(PathId(_))(collection.breakOut),
      ipAddress = ipAddressOption,
      residency = residencyOption,
      secrets = proto.getSecretsList.map(SecretsSerializer.fromProto)(collection.breakOut)
    )
  }

  private val portIndices: Range = container.flatMap(_.hostPorts.filter(_.nonEmpty)).getOrElse(portNumbers).indices

  val hostPorts: Seq[Option[Int]] =
    container.flatMap(_.hostPorts.filter(_.nonEmpty)).getOrElse(portNumbers.map(Some(_)))

  val servicePorts: Seq[Int] = container.flatMap(_.servicePorts.filter(_.nonEmpty)).getOrElse(portNumbers)

  val hasDynamicServicePorts: Boolean = servicePorts.contains(AppDefinition.RandomPortValue)

  val networkModeBridge: Boolean =
    container.exists(_.docker().exists(_.network.contains(mesos.ContainerInfo.DockerInfo.Network.BRIDGE)))

  val networkModeUser: Boolean =
    container.exists(_.docker().exists(_.network.contains(mesos.ContainerInfo.DockerInfo.Network.USER)))

  def mergeFromProto(bytes: Array[Byte]): AppDefinition = {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  val version: Timestamp = versionInfo.version

  /**
    * Returns whether this is a scaling change only.
    */
  def isOnlyScaleChange(to: RunSpec): Boolean = !isUpgrade(to) && (instances != to.instances)

  /**
    * True if the given app definition is a change to the current one in terms of runtime characteristics
    * of all deployed tasks of the current app, otherwise false.
    */
  def isUpgrade(to: RunSpec): Boolean = {
    id == to.id && {
      cmd != to.cmd ||
        args != to.args ||
        user != to.user ||
        env != to.env ||
        cpus != to.cpus ||
        mem != to.mem ||
        disk != to.disk ||
        gpus != to.gpus ||
        executor != to.executor ||
        constraints != to.constraints ||
        fetch != to.fetch ||
        storeUrls != to.storeUrls ||
        portDefinitions != to.portDefinitions ||
        requirePorts != to.requirePorts ||
        backoff != to.backoff ||
        backoffFactor != to.backoffFactor ||
        maxLaunchDelay != to.maxLaunchDelay ||
        container != to.container ||
        healthChecks != to.healthChecks ||
        taskKillGracePeriod != to.taskKillGracePeriod ||
        dependencies != to.dependencies ||
        upgradeStrategy != to.upgradeStrategy ||
        labels != to.labels ||
        acceptedResourceRoles != to.acceptedResourceRoles ||
        ipAddress != to.ipAddress ||
        readinessChecks != to.readinessChecks ||
        residency != to.residency ||
        secrets != to.secrets
    }
  }

  /**
    * Returns the changed app definition that is marked for restarting.
    */
  def markedForRestarting: AppDefinition = copy(versionInfo = VersionInfo.NoVersion)

  /**
    * Returns true if we need to restart all tasks.
    *
    * This can either be caused by changed configuration (e.g. a new cmd, a new docker image version)
    * or by a forced restart.
    */
  def needsRestart(to: RunSpec): Boolean = this.versionInfo != to.versionInfo || isUpgrade(to)

  /**
    * Identify other app definitions as the same, if id and version is the same.
    * Override the default equals implementation generated by scalac, which is very expensive.
    */
  override def equals(obj: Any): Boolean = {
    obj match {
      case that: AppDefinition => (that eq this) || (that.id == id && that.version == version)
      case _ => false
    }
  }

  /**
    * Compute the hashCode of an app only by id.
    * Override the default equals implementation generated by scalac, which is very expensive.
    */
  override def hashCode(): Int = id.hashCode()

  def withCanonizedIds(base: PathId = PathId.empty): AppDefinition = {
    val baseId = id.canonicalPath(base)
    copy(id = baseId, dependencies = dependencies.map(_.canonicalPath(baseId)))
  }

  def portAssignments(task: Task): Seq[PortAssignment] = {
    def fromDiscoveryInfo: Seq[PortAssignment] = ipAddress.flatMap {
      case IpAddress(_, _, DiscoveryInfo(appPorts), _) =>
        for {
          launched <- task.launched
          effectiveIpAddress <- task.effectiveIpAddress(this)
        } yield appPorts.zip(launched.hostPorts).map {
          case (appPort, hostPort) =>
            PortAssignment(
              portName = Some(appPort.name),
              effectiveIpAddress = Some(effectiveIpAddress),
              effectivePort = hostPort,
              hostPort = Some(hostPort))
        }.toList
    }.getOrElse(Seq.empty)

    @SuppressWarnings(Array("OptionGet", "TraversableHead"))
    def fromPortMappings: Seq[PortAssignment] = {
      for {
        c <- container
        pms <- c.portMappings
        launched <- task.launched
      } yield {
        var hostPorts = launched.hostPorts
        pms.map { portMapping =>
          val hostPort: Option[Int] =
            if (portMapping.hostPort.isEmpty) {
              None
            } else {
              val hostPort = hostPorts.head
              hostPorts = hostPorts.drop(1)
              Some(hostPort)
            }

          val effectivePort =
            if (ipAddress.isDefined || portMapping.hostPort.isEmpty) {
              portMapping.containerPort
            } else {
              hostPort.get
            }

          PortAssignment(
            portName = portMapping.name,
            effectiveIpAddress = task.effectiveIpAddress(this),
            effectivePort = effectivePort,
            hostPort = hostPort,
            containerPort = Some(portMapping.containerPort))
        }
      }.toList
    }.getOrElse(Seq.empty)

    def fromPortDefinitions: Seq[PortAssignment] = task.launched.map { launched =>
      portDefinitions.zip(launched.hostPorts).map {
        case (portDefinition, hostPort) =>
          PortAssignment(
            portName = portDefinition.name,
            effectiveIpAddress = Some(task.agentInfo.host),
            effectivePort = hostPort,
            hostPort = Some(hostPort))
      }
    }.getOrElse(Seq.empty)

    if (networkModeBridge || networkModeUser) fromPortMappings
    else if (ipAddress.isDefined) fromDiscoveryInfo
    else fromPortDefinitions
  }

  val portNames: Seq[String] = {
    def fromDiscoveryInfo = ipAddress.map(_.discoveryInfo.ports.map(_.name).toList).getOrElse(Seq.empty)
    def fromPortMappings = container.map(_.portMappings.getOrElse(Seq.empty).flatMap(_.name)).getOrElse(Seq.empty)
    def fromPortDefinitions = portDefinitions.flatMap(_.name)

    if (networkModeBridge || networkModeUser) fromPortMappings
    else if (ipAddress.isDefined) fromDiscoveryInfo
    else fromPortDefinitions
  }
}

@SuppressWarnings(Array("IsInstanceOf")) // doesn't work well in the validation macros?!
object AppDefinition extends GeneralPurposeCombinators {

  type AppKey = PathId

  sealed trait VersionInfo {
    def version: Timestamp
    def lastConfigChangeVersion: Timestamp

    def withScaleOrRestartChange(newVersion: Timestamp): VersionInfo = {
      VersionInfo.forNewConfig(version).withScaleOrRestartChange(newVersion)
    }

    def withConfigChange(newVersion: Timestamp): VersionInfo = {
      VersionInfo.forNewConfig(newVersion)
    }
  }

  object VersionInfo {

    /**
      * This should only be used for new AppDefinitions.
      *
      * If you set the versionInfo of existing AppDefinitions to `NoVersion`,
      * it will result in a restart when this AppDefinition is passed to the GroupManager update method.
      */
    case object NoVersion extends VersionInfo {
      override def version: Timestamp = Timestamp(0)
      override def lastConfigChangeVersion: Timestamp = version
    }

    /**
      * Only contains a version timestamp. Will be converted to a FullVersionInfo before stored.
      */
    case class OnlyVersion(version: Timestamp) extends VersionInfo {
      override def lastConfigChangeVersion: Timestamp = version
    }

    /**
      * @param version The versioning timestamp (we are currently assuming that this is the same as lastChangeAt)
      * @param lastScalingAt The time stamp of the last change including scaling or restart changes
      * @param lastConfigChangeAt The time stamp of the last change that changed configuration
      *                           besides scaling or restarting
      */
    case class FullVersionInfo(
        version: Timestamp,
        lastScalingAt: Timestamp,
        lastConfigChangeAt: Timestamp) extends VersionInfo {

      override def lastConfigChangeVersion: Timestamp = lastConfigChangeAt

      override def withScaleOrRestartChange(newVersion: Timestamp): VersionInfo = {
        copy(version = newVersion, lastScalingAt = newVersion)
      }
    }

    def forNewConfig(newVersion: Timestamp): FullVersionInfo = FullVersionInfo(
      version = newVersion,
      lastScalingAt = newVersion,
      lastConfigChangeAt = newVersion
    )
  }

  val RandomPortValue: Int = 0
  val RandomPortDefinition: PortDefinition = PortDefinition(RandomPortValue, "tcp", None, Map.empty[String, String])

  // App defaults
  val DefaultId: PathId = PathId.empty

  val DefaultCmd: Option[String] = None

  val DefaultArgs: Option[Seq[String]] = None

  val DefaultUser: Option[String] = None

  val DefaultEnv: Map[String, EnvVarValue] = Map.empty

  val DefaultInstances: Int = 1

  val DefaultCpus: Double = 1.0

  val DefaultMem: Double = 128.0

  val DefaultDisk: Double = 0.0

  val DefaultGpus: Int = 0

  val DefaultExecutor: String = ""

  val DefaultConstraints: Set[Constraint] = Set.empty

  val DefaultUris: Seq[String] = Seq.empty

  val DefaultFetch: Seq[FetchUri] = FetchUri.empty

  val DefaultStoreUrls: Seq[String] = Seq.empty

  val DefaultPortDefinitions: Seq[PortDefinition] = Seq(RandomPortDefinition)

  val DefaultRequirePorts: Boolean = false

  val DefaultBackoff: FiniteDuration = 1.second

  val DefaultBackoffFactor = 1.15

  val DefaultMaxLaunchDelay: FiniteDuration = 1.hour

  val DefaultContainer: Option[Container] = None

  val DefaultHealthChecks: Set[HealthCheck] = Set.empty

  val DefaultReadinessChecks: Seq[ReadinessCheck] = Seq.empty

  val DefaultTaskKillGracePeriod: Option[FiniteDuration] = None

  val DefaultDependencies: Set[PathId] = Set.empty

  val DefaultUpgradeStrategy: UpgradeStrategy = UpgradeStrategy.empty

  val DefaultSecrets: Map[String, Secret] = Map.empty

  object Labels {
    val Default: Map[String, String] = Map.empty

    val DcosMigrationApiPath = "DCOS_MIGRATION_API_PATH"
    val DcosMigrationApiVersion = "DCOS_MIGRATION_API_VERSION"
    val DcosPackageFrameworkName = "DCOS_PACKAGE_FRAMEWORK_NAME"
    val SingleInstanceApp = "MARATHON_SINGLE_INSTANCE_APP"
  }

  /**
    * This default is only used in tests
    */
  val DefaultAcceptedResourceRoles: Set[String] = Set.empty

  val DefaultResidency: Option[Residency] = None

  def fromProto(proto: Protos.ServiceDefinition): AppDefinition =
    AppDefinition().mergeFromProto(proto)

  /**
    * We cannot validate HealthChecks here, because it would break backwards compatibility in weird ways.
    * If users had already one invalid app definition, each deployment would cause a complete revalidation of
    * the root group including the invalid one.
    * Until the user changed all invalid apps, the user would get weird validation
    * errors for every deployment potentially unrelated to the deployed apps.
    */
  def validAppDefinition(
    enabledFeatures: Set[String])(implicit pluginManager: PluginManager): Validator[AppDefinition] =
    validator[AppDefinition] { app =>
      app.id is valid
      app.id is PathId.absolutePathValidator
      app.dependencies is every(PathId.validPathWithBase(app.id.parent))
    } and validBasicAppDefinition(enabledFeatures) and pluginValidators

  /**
    * Validator for apps, which are being part of a group.
    *
    * @param base Path of the parent group.
    * @return
    */
  def validNestedAppDefinition(base: PathId, enabledFeatures: Set[String]): Validator[AppDefinition] =
    validator[AppDefinition] { app =>
      app.id is PathId.validPathWithBase(base)
    } and validBasicAppDefinition(enabledFeatures)

  private def pluginValidators(implicit pluginManager: PluginManager): Validator[AppDefinition] =
    new Validator[AppDefinition] {
      override def apply(app: AppDefinition): Result = {
        val plugins = pluginManager.plugins[RunSpecValidator]
        new And(plugins: _*)(app)
      }
    }

  private def complyWithIpAddressRules(app: AppDefinition): Validator[IpAddress] = {
    import mesos.ContainerInfo.DockerInfo.Network.{ BRIDGE, USER }
    isTrue[IpAddress]("ipAddress/discovery is not allowed for Docker containers using BRIDGE or USER networks") { ip =>
      !(ip.discoveryInfo.nonEmpty &&
        app.container.exists(_.docker().exists(_.network.exists(Set(BRIDGE, USER)))))
    }
  }

  private val complyWithResidencyRules: Validator[AppDefinition] =
    isTrue("AppDefinition must contain persistent volumes and define residency") { app =>
      !(app.residency.isDefined ^ app.persistentVolumes.nonEmpty)
    }

  private val containsCmdArgsOrContainer: Validator[AppDefinition] =
    isTrue("AppDefinition must either contain one of 'cmd' or 'args', and/or a non-Mesos 'container'.") { app =>
      val cmd = app.cmd.nonEmpty
      val args = app.args.nonEmpty
      val container = app.container.exists {
        case _: Container.Mesos => false
        case _ => true
      }
      (cmd ^ args) || (!(cmd || args) && container)
    }

  private val complyWithMigrationAPI: Validator[AppDefinition] =
    isTrue("DCOS_PACKAGE_FRAMEWORK_NAME and DCOS_MIGRATION_API_PATH must be defined" +
      " when using DCOS_MIGRATION_API_VERSION") { app =>
      val understandsMigrationProtocol = app.labels.get(AppDefinition.Labels.DcosMigrationApiVersion).exists(_.nonEmpty)

      // if the api version IS NOT set, we're ok
      // if the api version IS set, we expect to see a valid version, a frameworkName and a path
      def compliesWithMigrationApi =
        app.labels.get(AppDefinition.Labels.DcosMigrationApiVersion).fold(true) { apiVersion =>
          apiVersion == "v1" &&
            app.labels.get(AppDefinition.Labels.DcosPackageFrameworkName).exists(_.nonEmpty) &&
            app.labels.get(AppDefinition.Labels.DcosMigrationApiPath).exists(_.nonEmpty)
        }

      !understandsMigrationProtocol || (understandsMigrationProtocol && compliesWithMigrationApi)
    }

  private val complyWithSingleInstanceLabelRules: Validator[AppDefinition] =
    isTrue("Single instance app may only have one instance") { app =>
      (!app.isSingleInstance) || (app.instances <= 1)
    }

  private val complyWithReadinessCheckRules: Validator[AppDefinition] = validator[AppDefinition] { app =>
    app.readinessChecks.size should be <= 1
    app.readinessChecks is every(ReadinessCheck.readinessCheckValidator(app))
  }

  private val complyWithUpgradeStrategyRules: Validator[AppDefinition] = validator[AppDefinition] { appDef =>
    (appDef.isSingleInstance is false) or (appDef.upgradeStrategy is UpgradeStrategy.validForSingleInstanceApps)
    (appDef.isResident is false) or (appDef.upgradeStrategy is UpgradeStrategy.validForResidentTasks)
  }

  private def complyWithGpuRules(enabledFeatures: Set[String]): Validator[AppDefinition] =
    conditional[AppDefinition](_.gpus > 0) {
      isTrue[AppDefinition]("GPU resources only work with the Mesos containerizer") { app =>
        app.container match {
          case Some(_: Docker) => false
          case _ => true
        }
      } and featureEnabled(enabledFeatures, Features.GPU_RESOURCES)
    }

  private val complyWithConstraintRules: Validator[Constraint] = new Validator[Constraint] {
    import Constraint.Operator._
    override def apply(c: Constraint): Result = {
      if (!c.hasField || !c.hasOperator) {
        Failure(Set(RuleViolation(c, "Missing field and operator", None)))
      } else {
        c.getOperator match {
          case UNIQUE =>
            if (c.hasValue && c.getValue.nonEmpty) {
              Failure(Set(RuleViolation(c, "Value specified but not used", None)))
            } else {
              Success
            }
          case CLUSTER =>
            if (c.hasValue && c.getValue.nonEmpty) {
              Success
            } else {
              Failure(Set(RuleViolation(c, "Missing value", None)))
            }
          case GROUP_BY =>
            if (!c.hasValue || (c.hasValue && c.getValue.nonEmpty && Try(c.getValue.toInt).isSuccess)) {
              Success
            } else {
              Failure(Set(RuleViolation(
                c,
                "Value was specified but is not a number",
                Some("GROUP_BY may either have no value or an integer value"))))
            }
          case LIKE | UNLIKE =>
            if (c.hasValue) {
              Try(Pattern.compile(c.getValue)) match {
                case util.Success(_) =>
                  Success
                case util.Failure(e) =>
                  Failure(Set(RuleViolation(
                    c,
                    s"'${c.getValue}' is not a valid regular expression",
                    Some(s"${c.getValue}\n${e.getMessage}"))))
              }
            } else {
              Failure(Set(RuleViolation(c, "A regular expression value must be provided", None)))
            }
          case MAX_PER =>
            if (c.hasValue && c.getValue.nonEmpty && Try(c.getValue.toInt).isSuccess) {
              Success
            } else {
              Failure(Set(RuleViolation(
                c,
                "Value was specified but is not a number",
                Some("MAX_PER may have an integer value"))))
            }
          case _ =>
            Failure(Set(
              RuleViolation(c, "Operator must be one of UNIQUE, CLUSTER, GROUP_BY, LIKE, MAX_PER or UNLIKE", None)))
        }
      }
    }
  }

  private val haveAtMostOneMesosHealthCheck: Validator[AppDefinition] =
    isTrue[AppDefinition]("AppDefinition can contain at most one Mesos health check") { appDef =>
      // Previous versions of Marathon allowed saving an app definition with more than one command health check, and
      // we don't want to make them invalid
      (appDef.healthChecks.count(_.isInstanceOf[MesosHealthCheck]) -
        appDef.healthChecks.count(_.isInstanceOf[MesosCommandHealthCheck])) <= 1
    }

  private def validBasicAppDefinition(enabledFeatures: Set[String]) = validator[AppDefinition] { appDef =>
    appDef.upgradeStrategy is valid
    appDef.container.each is valid(Container.validContainer(enabledFeatures))
    appDef.storeUrls is every(urlCanBeResolvedValidator)
    appDef.portDefinitions is PortDefinitions.portDefinitionsValidator
    appDef.executor should matchRegexFully("^(//cmd)|(/?[^/]+(/[^/]+)*)|$")
    appDef is containsCmdArgsOrContainer
    appDef.healthChecks is every(portIndexIsValid(appDef.portIndices))
    appDef must haveAtMostOneMesosHealthCheck
    appDef.instances should be >= 0
    appDef.fetch is every(fetchUriIsValid)
    appDef.mem should be >= 0.0
    appDef.cpus should be >= 0.0
    appDef.instances should be >= 0
    appDef.disk should be >= 0.0
    appDef.gpus should be >= 0
    appDef.secrets is valid(Secret.secretsValidator)
    appDef.secrets is empty or featureEnabled(enabledFeatures, Features.SECRETS)
    appDef.env is valid(EnvVarValue.envValidator)
    appDef.acceptedResourceRoles is optional(ResourceRole.validAcceptedResourceRoles(appDef.isResident))
    appDef must complyWithGpuRules(enabledFeatures)
    appDef must complyWithMigrationAPI
    appDef must complyWithReadinessCheckRules
    appDef must complyWithResidencyRules
    appDef must complyWithSingleInstanceLabelRules
    appDef must complyWithUpgradeStrategyRules
    appDef.constraints.each must complyWithConstraintRules
    appDef.ipAddress must optional(complyWithIpAddressRules(appDef))
  } and ExternalVolumes.validApp and EnvVarValue.validApp

  @SuppressWarnings(Array("TraversableHead"))
  private def portIndexIsValid(hostPortsIndices: Range): Validator[HealthCheck] =
    isTrue("Health check port indices must address an element of the ports array or container port mappings.") {
      case hc: MarathonHealthCheck =>
        hc.portIndex match {
          case Some(idx) => hostPortsIndices.contains(idx)
          case None => hc.port.nonEmpty || (hostPortsIndices.length == 1 && hostPortsIndices.head == 0)
        }
      case _ => true
    }

  @SuppressWarnings(Array("ComparingFloatingPointTypes"))
  def residentUpdateIsValid(from: AppDefinition): Validator[AppDefinition] = {
    val changeNoVolumes =
      isTrue[AppDefinition]("Persistent volumes can not be changed!") { to =>
        val fromVolumes = from.persistentVolumes
        val toVolumes = to.persistentVolumes
        def sameSize = fromVolumes.size == toVolumes.size
        def noChange = from.persistentVolumes.forall { fromVolume =>
          toVolumes.find(_.containerPath == fromVolume.containerPath).contains(fromVolume)
        }
        sameSize && noChange
      }

    val changeNoResources =
      isTrue[AppDefinition]("Resident Tasks may not change resource requirements!") { to =>
        from.cpus == to.cpus &&
          from.mem == to.mem &&
          from.disk == to.disk &&
          from.gpus == to.gpus &&
          from.portDefinitions == to.portDefinitions
      }

    validator[AppDefinition] { app =>
      app should changeNoVolumes
      app should changeNoResources
      app.upgradeStrategy is UpgradeStrategy.validForResidentTasks
    }
  }

  def updateIsValid(from: Group): Validator[AppDefinition] = {
    new Validator[AppDefinition] {
      override def apply(app: AppDefinition): Result = {
        from.transitiveAppsById.get(app.id) match {
          case (Some(last)) if last.isResident || app.isResident => residentUpdateIsValid(last)(app)
          case _ => Success
        }
      }
    }
  }
}
