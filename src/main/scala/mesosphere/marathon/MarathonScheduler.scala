package mesosphere.marathon

import javax.inject.Inject

import akka.event.EventStream
import mesosphere.marathon.core.base.CurrentRuntime
import mesosphere.marathon.core.event.{ SchedulerRegisteredEvent, _ }
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.util.state.{ FrameworkId, MesosLeaderInfo }
import org.apache.mesos.Protos._
import org.apache.mesos.{ Scheduler, SchedulerDriver }
import org.slf4j.LoggerFactory

import scala.concurrent._
import scala.util.control.NonFatal

class MarathonScheduler @Inject() (
    eventBus: EventStream,
    offerProcessor: OfferProcessor,
    taskStatusProcessor: TaskStatusUpdateProcessor,
    frameworkIdRepository: FrameworkIdRepository,
    mesosLeaderInfo: MesosLeaderInfo,
    config: MarathonConf) extends Scheduler {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val zkTimeout = config.zkTimeoutDuration

  override def registered(
    driver: SchedulerDriver,
    frameworkId: FrameworkID,
    master: MasterInfo): Unit = {
    log.info(s"Registered as ${frameworkId.getValue} to master '${master.getId}'")
    Await.result(frameworkIdRepository.store(FrameworkId.fromProto(frameworkId)), zkTimeout)
    mesosLeaderInfo.onNewMasterInfo(master)
    eventBus.publish(SchedulerRegisteredEvent(frameworkId.getValue, master.getHostname))
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    log.info("Re-registered to %s".format(master))
    mesosLeaderInfo.onNewMasterInfo(master)
    eventBus.publish(SchedulerReregisteredEvent(master.getHostname))
  }

  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]): Unit = {
    import scala.collection.JavaConverters._
    offers.asScala.foreach { offer =>
      val processFuture = offerProcessor.processOffer(offer)
      processFuture.onComplete {
        case scala.util.Success(_) => log.debug(s"Finished processing offer '${offer.getId.getValue}'")
        case scala.util.Failure(NonFatal(e)) => log.error(s"while processing offer '${offer.getId.getValue}'", e)
      }
    }
  }

  override def offerRescinded(driver: SchedulerDriver, offer: OfferID): Unit = {
    log.info("Offer %s rescinded".format(offer))
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    taskStatusProcessor.publish(status).onFailure {
      case NonFatal(e) =>
        log.error(s"while processing task status update $status", e)
    }
  }

  override def frameworkMessage(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    message: Array[Byte]): Unit = {
    log.info(s"Received framework message $executor $slave $message")
    eventBus.publish(MesosFrameworkMessageEvent(executor.getValue, slave.getValue, message))
  }

  override def disconnected(driver: SchedulerDriver): Unit = {
    log.warn("Disconnected")

    eventBus.publish(SchedulerDisconnectedEvent())

    // stop the driver. this avoids ambiguity and delegates leadership-abdication responsibility.
    // this helps to clarify responsibility during leadership transitions: currently the
    // **scheduler service** is responsible for integrating with leadership election.
    // @see MarathonSchedulerService.startLeadership
    driver.stop(true)
  }

  override def slaveLost(driver: SchedulerDriver, slave: SlaveID): Unit = {
    log.info(s"Lost slave $slave")
  }

  override def executorLost(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    p4: Int): Unit = {
    log.info(s"Lost executor $executor slave $p4")
  }

  override def error(driver: SchedulerDriver, message: String): Unit = {
    log.warn(s"Error: $message\n" +
      "In case Mesos does not allow registration with the current frameworkId, " +
      s"delete the ZooKeeper Node: ${config.zkPath}/state/framework:id\n" +
      "CAUTION: if you remove this node, all tasks started with the current frameworkId will be orphaned!")

    // Currently, it's pretty hard to disambiguate this error from other causes of framework errors.
    // Watch MESOS-2522 which will add a reason field for framework errors to help with this.
    // For now the frameworkId is removed based on the error message.
    val removeFrameworkId = message match {
      case "Framework has been removed" => true
      case _: String => false
    }
    suicide(removeFrameworkId)
  }

  /**
    * Exits the JVM process, optionally deleting Marathon's FrameworkID
    * from the backing persistence store.
    *
    * If `removeFrameworkId` is set, the next Marathon process elected
    * leader will fail to find a stored FrameworkID and invoke `register`
    * instead of `reregister`.  This is important because on certain kinds
    * of framework errors (such as exceeding the framework failover timeout),
    * the scheduler may never re-register with the saved FrameworkID until
    * the leading Mesos master process is killed.
    */
  protected def suicide(removeFrameworkId: Boolean): Unit = {
    log.error("Committing suicide!")

    if (removeFrameworkId) Await.ready(frameworkIdRepository.delete(), config.zkTimeoutDuration)

    // Asynchronously call asyncExit to avoid deadlock due to the JVM shutdown hooks
    CurrentRuntime.asyncExit()
  }
}
