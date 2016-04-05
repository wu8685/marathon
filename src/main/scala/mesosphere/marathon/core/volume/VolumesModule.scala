package mesosphere.marathon.core.volume

import com.wix.accord._
import mesosphere.marathon.core.volume.providers._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ContainerInfo, CommandInfo}

/**
  * VolumeProvider is an interface implemented by storage volume providers
  */
trait VolumeProvider[+T <: Volume] {
  /** appValidation implements a provider's app validation rules */
  val appValidation: Validator[AppDefinition]
  /** groupValidation implements a provider's group validation rules */
  val groupValidation: Validator[Group]

  /** collect scrapes volumes from an application definition that are supported by this volume provider */
  def collect(container: Container): Iterable[T]

  /** build adds v to the given builder **/
  def build(builder: ContainerInfo.Builder, v: Volume): Unit
}

trait PersistentVolumeProvider[+T <: PersistentVolume] extends VolumeProvider[T] {
  val name: String

  /**
    * don't invoke validator on v because that's circular, just check the additional
    * things that we need for agent local volumes.
    * see implicit validator in the PersistentVolume class for reference.
    */
  val volumeValidation: Validator[PersistentVolume]

  /** build adds v to the given builder **/
  def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, pv: PersistentVolume): Unit
}

trait VolumeProviderRegistry {
  /** @return the VolumeProvider interface registered for the given volume */
  def apply[T <: Volume](v: T): Option[VolumeProvider[T]]
}

trait PersistentVolumeProviderRegistry extends VolumeProviderRegistry {
  /**
    * @return the PersistentVolumeProvider interface registered for the given name; if name is None then
    * the default PersistenVolumeProvider implementation is returned. None is returned if Some name is given
    * but no volume provider is registered for that name.
    */
  def apply(name: Option[String]): Option[PersistentVolumeProvider[PersistentVolume]]
}

/**
  * API facade for callers interested in storage volumes
  */
object VolumesModule {
  lazy val localVolumes: VolumeProvider[PersistentVolume] = ResidentVolumeProvider
  lazy val providers: PersistentVolumeProviderRegistry = StaticRegistry

  def build(builder: ContainerInfo.Builder, v: Volume): Unit = {
    providers(v).foreach { _.build(builder, v) }
  }

  def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, pv: PersistentVolume): Unit = {
    providers(pv.persistent.providerName).foreach { _.build(containerType, builder, pv) }
  }

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validApp(): Validator[AppDefinition] = new Validator[AppDefinition] {
    def apply(app: AppDefinition) = app match {
      // scalastyle:off null
      case null => Failure(Set(RuleViolation(null, "is a null", None)))
      // scalastyle:on null

      // grab all related volume providers and apply their appValidation
      case _ => app.container.toSet[Container].flatMap{ ct =>
        ct.volumes.map(providers(_))
      }.flatten.map(_.appValidation).map(validate(app)(_)).fold(Success)(_ and _)
    }
  }

  /** @return a validator that checks the validity of a group given the related volume providers */
  def validGroup(): Validator[Group] = new Validator[Group] {
    def apply(grp: Group) = grp match {
      // scalastyle:off null
      case null => Failure(Set(RuleViolation(null, "is a null", None)))
      // scalastyle:on null

      // grab all related volume providers and apply their groupValidation
      case _ => grp.transitiveApps.flatMap{ app => app.container }.flatMap{ ct =>
        ct.volumes.map(providers(_))
      }.flatten.map{ p => validate(grp)(p.groupValidation) }.fold(Success)(_ and _)
    }
  }
}