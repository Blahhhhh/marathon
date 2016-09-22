package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.matcher.base.util.OfferOperationFactory
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolume
import mesosphere.marathon.state.DiskSource
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }

class InstanceOpFactoryHelper(
    private val principalOpt: Option[String],
    private val roleOpt: Option[String]) {

  private[this] val offerOperationFactory = new OfferOperationFactory(principalOpt, roleOpt)

  // TODO PODs remove
  def launchEphemeral(
    taskInfo: Mesos.TaskInfo,
    newTask: Task.LaunchedEphemeral): InstanceOp.LaunchTask = launchEphemeral(taskInfo, newTask, Instance(newTask))

  def launchEphemeral(
    taskInfo: Mesos.TaskInfo,
    newTask: Task.LaunchedEphemeral,
    instance: Instance): InstanceOp.LaunchTask = {

    assume(newTask.taskId.mesosTaskId == taskInfo.getTaskId, "marathon task id and mesos task id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    val stateOp = InstanceUpdateOperation.LaunchEphemeral(instance)
    InstanceOp.LaunchTask(taskInfo, stateOp, oldInstance = None, createOperations)
  }

  def launchEphemeral(
    executorInfo: Mesos.ExecutorInfo,
    groupInfo: Mesos.TaskGroupInfo,
    launched: Instance.LaunchRequest): InstanceOp.LaunchTaskGroup = {

    assume(
      executorInfo.getExecutorId.getValue == launched.instance.instanceId.executorIdString,
      "marathon pod instance id and mesos executor id must be equal")

    def createOperations = Seq(offerOperationFactory.launch(executorInfo, groupInfo))

    val stateOp = InstanceUpdateOperation.LaunchEphemeral(launched.instance)
    InstanceOp.LaunchTaskGroup(executorInfo, groupInfo, stateOp, oldInstance = None, createOperations)
  }

  def launchOnReservation(
    taskInfo: Mesos.TaskInfo,
    newTask: InstanceUpdateOperation.LaunchOnReservation,
    oldTask: Task.Reserved): InstanceOp.LaunchTask = {

    def createOperations = Seq(offerOperationFactory.launch(taskInfo))

    InstanceOp.LaunchTask(taskInfo, newTask, Some(Instance(oldTask)), createOperations)
  }

  /**
    * Returns a set of operations to reserve ALL resources (cpu, mem, ports, disk, etc.) and then create persistent
    * volumes against them as needed
    */
  def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    newTask: InstanceUpdateOperation.Reserve,
    resources: Iterable[Mesos.Resource],
    localVolumes: Iterable[(DiskSource, LocalVolume)]): InstanceOp.ReserveAndCreateVolumes = {

    def createOperations = Seq(
      offerOperationFactory.reserve(frameworkId, newTask.instanceId, resources),
      offerOperationFactory.createVolumes(
        frameworkId,
        newTask.instanceId,
        localVolumes))

    InstanceOp.ReserveAndCreateVolumes(newTask, resources, createOperations)
  }
}
