package mesosphere.marathon.core.matcher.base

import mesosphere.marathon.core.matcher.base.util.OfferOperation
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.state.{PersistentVolume, Timestamp}
import mesosphere.marathon.tasks.ResourceUtil
import org.apache.mesos.{ Protos => MesosProtos }

import scala.concurrent.Future

object OfferMatcher {

  /**
    * A [[TaskOp]] with a [[TaskOpSource]].
    *
    * The [[TaskOpSource]] is informed whether the op is ultimately send to Mesos or if it is rejected
    * (e.g. by throttling logic).
    */
  case class TaskOpWithSource(source: TaskOpSource, op: TaskOp) {
    def taskId: Task.Id = op.taskId
    def accept(): Unit = source.taskOpAccepted(op)
    def reject(reason: String): Unit = source.taskOpRejected(op, reason)
  }

  /**
    * An operation which relates to a task and is send to Mesos for execution in an `acceptOffers` API call.
    */
  sealed trait TaskOp {
    /** The ID of the affected task. */
    def taskId: Task.Id = newTask.taskId
    /** The MarathonTask state before this operation has been applied. */
    def oldTask: Option[Task]
    /** The MarathonTask state after this operation has been applied. */
    def newTask: Task
    /** How would the offer change when Mesos executes this op? */
    def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer
    /** To which Offer.Operations does this task op relate? */
    def offerOperations: Iterable[MesosProtos.Offer.Operation]
  }

  /** Launch a task on the offer. */
  case class Launch(taskInfo: MesosProtos.TaskInfo, newTask: Task, oldTask: Option[Task] = None) extends TaskOp {
    def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer = {
      import scala.collection.JavaConverters._
      ResourceUtil.consumeResourcesFromOffer(offer, taskInfo.getResourcesList.asScala)
    }
    def offerOperations: Iterable[MesosProtos.Offer.Operation] = Seq(OfferOperation.launch(taskInfo))
  }

  /** Reserves all resources required to launch a task and creates eventual local volumes.
    *
    * The current implementation just consumes the resources that are used for the reservation and creation
    * of the volumes. This means that the offer will be sent back to Mesos before any task is launched.
    * In principal we could also simulate the effects on the offer more accurately to enable immediate launches.
    *
    * @param taskResources All tasks resources -- excluding the resources needed for creating the volume.
    * @param localVolumes The local volumes to create.
    */
  case class ReserveAndCreateVolumes(
    taskResources: Iterable[MesosProtos.Resource],
    localVolumes: Iterable[ReserveAndCreateVolumes.LocalVolumeInfo],
    newTask: Task, oldTask: Option[Task]
  ) extends TaskOp {

    def applyToOffer(offer: MesosProtos.Offer): MesosProtos.Offer = {
      val offerWithoutTaskResources = ResourceUtil.consumeResourcesFromOffer(offer, taskResources)
      localVolumes.foldLeft(offerWithoutTaskResources) { (remainingOffer, volume) =>
        ResourceUtil.consumeResourcesFromOffer(remainingOffer, volume.resources)
      }
    }

    def offerOperations: Iterable[MesosProtos.Offer.Operation] = {
      OfferOperation.reserve(taskResources) :: localVolumes.view.map(_.offerOperation).toList
    }
  }

  object ReserveAndCreateVolumes {
    /** Everything needed to specify what local volume to create. */
    case class LocalVolumeInfo(
      principal: String,
      role: String,
      volumeId: LocalVolumeId,
      volumeConfig: PersistentVolume
    ) {
      lazy val offerOperation: MesosProtos.Offer.Operation = OfferOperation.createVolume(this)
      def resources: Iterable[MesosProtos.Resource] = {
        import scala.collection.JavaConverters._
        offerOperation.getCreate.getVolumesList.asScala
      }
    }
  }

  /**
    * Reply from an offer matcher to a MatchOffer. If the offer match
    * could not match the offer in any way it should simply leave the tasks
    * collection empty.
    *
    * To increase fairness between matchers, each normal matcher should schedule as few operations
    * as possible per offer per match, e.g. one for task launches without reservations. Multiple launches could be used
    * if the tasks need to be colocated or if the operations are intrinsically dependent on each other.
    * The OfferMultiplexer tries to summarize suitable
    * matches from multiple offer matches into one response.
    *
    * A MatchedTaskOps reply does not guarantee that these operations can actually be executed.
    * The launcher of message should setup some kind of timeout mechanism and handle
    * taskOpAccepted/taskOpRejected calls appropriately.
    *
    * @param offerId the identifier of the offer
    * @param opsWithSource the ops that should be executed on that offer including the source of each op
    * @param resendThisOffer true, if this offer could not be processed completely (e.g. timeout)
    *                        and should be resend and processed again
    */
  case class MatchedTaskOps(
    offerId: MesosProtos.OfferID,
    opsWithSource: Seq[TaskOpWithSource],
    resendThisOffer: Boolean = false
  ) {
    /** all included [TaskOp] without the source information. */
    def ops: Iterable[TaskOp] = opsWithSource.view.map(_.op)

    /** All TaskInfos of launched tasks. */
    def launchedTaskInfos: Iterable[MesosProtos.TaskInfo] = ops.view.collect { case Launch(taskInfo, _, _) => taskInfo }

    /** The last state of the affected MarathonTasks after this operations. */
    def marathonTasks: Map[Task.Id, Task] = ops.map(op => op.taskId -> op.newTask).toMap
  }

  trait TaskOpSource {
    def taskOpAccepted(taskOp: TaskOp)
    def taskOpRejected(taskOp: TaskOp, reason: String)
  }
}

/**
  * Tries to match offers with given tasks.
  */
trait OfferMatcher {
  /**
    * Process offer and return the ops that this matcher wants to execute on this offer.
    *
    * The offer matcher can expect either a taskOpAccepted or a taskOpRejected call
    * for every returned `org.apache.mesos.Protos.TaskInfo`.
    */
  def matchOffer(deadline: Timestamp, offer: MesosProtos.Offer): Future[OfferMatcher.MatchedTaskOps]
}
