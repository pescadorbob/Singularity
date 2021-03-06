package com.hubspot.singularity.scheduler;

import java.text.ParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.ExtendedTaskState;
import com.hubspot.singularity.RequestState;
import com.hubspot.singularity.SingularityCreateResult;
import com.hubspot.singularity.SingularityDeployKey;
import com.hubspot.singularity.SingularityDeployMarker;
import com.hubspot.singularity.SingularityDeployStatistics;
import com.hubspot.singularity.SingularityDeployStatisticsBuilder;
import com.hubspot.singularity.SingularityPendingRequest;
import com.hubspot.singularity.SingularityPendingRequest.PendingType;
import com.hubspot.singularity.SingularityPendingTask;
import com.hubspot.singularity.SingularityPendingTaskId;
import com.hubspot.singularity.SingularityRack;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.SingularityRequestDeployState;
import com.hubspot.singularity.SingularityRequestWithState;
import com.hubspot.singularity.SingularitySlave;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskCleanup;
import com.hubspot.singularity.SingularityTaskCleanup.TaskCleanupType;
import com.hubspot.singularity.SingularityTaskHistoryUpdate;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.SingularityTaskRequest;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.DeployManager;
import com.hubspot.singularity.data.RackManager;
import com.hubspot.singularity.data.RequestManager;
import com.hubspot.singularity.data.SlaveManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.TaskRequestManager;
import com.hubspot.singularity.smtp.SingularityMailer;

@Singleton
public class SingularityScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(SingularityScheduler.class);

  private final SingularityConfiguration configuration;
  private final SingularityCooldown cooldown;

  private final TaskManager taskManager;
  private final RequestManager requestManager;
  private final TaskRequestManager taskRequestManager;
  private final DeployManager deployManager;

  private final SlaveManager slaveManager;
  private final RackManager rackManager;

  private final SingularityMailer mailer;

  @Inject
  public SingularityScheduler(TaskRequestManager taskRequestManager, SingularityConfiguration configuration, SingularityCooldown cooldown, DeployManager deployManager,
      TaskManager taskManager, RequestManager requestManager, SlaveManager slaveManager, RackManager rackManager, SingularityMailer mailer) {
    this.taskRequestManager = taskRequestManager;
    this.configuration = configuration;
    this.deployManager = deployManager;
    this.taskManager = taskManager;
    this.requestManager = requestManager;
    this.slaveManager = slaveManager;
    this.rackManager = rackManager;
    this.mailer = mailer;
    this.cooldown = cooldown;
  }

  private void cleanupTaskDueToDecomission(final Set<String> requestIdsToReschedule, final Set<SingularityTaskId> matchingTaskIds, SingularityTask task, String decomissioningObject) {
    requestIdsToReschedule.add(task.getTaskRequest().getRequest().getId());

    matchingTaskIds.add(task.getTaskId());

    LOG.trace("Scheduling a cleanup task for {} due to decomissioning {}", task.getTaskId(), decomissioningObject);

    taskManager.createCleanupTask(new SingularityTaskCleanup(Optional.<String> absent(), TaskCleanupType.DECOMISSIONING, System.currentTimeMillis(), task.getTaskId()));
  }

  public void checkForDecomissions(SingularitySchedulerStateCache stateCache) {
    final long start = System.currentTimeMillis();

    final Set<String> requestIdsToReschedule = Sets.newHashSet();
    final Set<SingularityTaskId> matchingTaskIds = Sets.newHashSet();

    final List<SingularityTaskId> activeTaskIds = stateCache.getActiveTaskIds();

    final List<SingularitySlave> slaves = slaveManager.getDecomissioningObjectsFiltered(stateCache.getDecomissioningSlaves());

    for (SingularitySlave slave : slaves) {
      for (SingularityTask activeTask : taskManager.getTasksOnSlave(activeTaskIds, slave)) {
        cleanupTaskDueToDecomission(requestIdsToReschedule, matchingTaskIds, activeTask, slave.toString());
      }
    }

    final List<SingularityRack> racks = rackManager.getDecomissioningObjectsFiltered(stateCache.getDecomissioningRacks());

    for (SingularityRack rack : racks) {
      for (SingularityTaskId activeTaskId : activeTaskIds) {
        if (matchingTaskIds.contains(activeTaskId)) {
          continue;
        }

        if (rack.getId().equals(activeTaskId.getRackId())) {
          Optional<SingularityTask> maybeTask = taskManager.getActiveTask(activeTaskId.getId());
          cleanupTaskDueToDecomission(requestIdsToReschedule, matchingTaskIds, maybeTask.get(), rack.toString());
        }
      }
    }

    for (String requestId : requestIdsToReschedule) {
      LOG.trace("Rescheduling request {} due to decomissions", requestId);

      Optional<String> maybeDeployId = deployManager.getInUseDeployId(requestId);

      if (maybeDeployId.isPresent()) {
        requestManager.addToPendingQueue(new SingularityPendingRequest(requestId, maybeDeployId.get(), PendingType.DECOMISSIONED_SLAVE_OR_RACK));
      } else {
        LOG.warn("Not rescheduling a request ({}) because of no active deploy", requestId);
      }
    }

    for (SingularitySlave slave : slaves) {
      LOG.debug("Marking slave {} as decomissioned", slave);
      slaveManager.markAsDecomissioned(slave);
    }

    for (SingularityRack rack : racks) {
      LOG.debug("Marking rack {} as decomissioned", rack);
      rackManager.markAsDecomissioned(rack);
    }

    if (slaves.isEmpty() && racks.isEmpty() && requestIdsToReschedule.isEmpty() && matchingTaskIds.isEmpty()) {
      LOG.trace("Decomission check found nothing");
    } else {
      LOG.info("Found {} decomissioning slaves, {} decomissioning racks, rescheduling {} requests and scheduling {} tasks for cleanup in {}", slaves.size(), racks.size(), requestIdsToReschedule.size(), matchingTaskIds.size(), JavaUtils.duration(start));
    }
  }

  private Collection<SingularityPendingRequest> filterPendingRequestsByDeployAndPriority(List<SingularityPendingRequest> pendingRequests) {
    Map<SingularityDeployKey, SingularityPendingRequest> deployKeyToMostImportantPendingRequest = Maps.newHashMapWithExpectedSize(pendingRequests.size());

    for (SingularityPendingRequest pendingRequest : pendingRequests) {
      SingularityDeployKey deployKey = new SingularityDeployKey(pendingRequest.getRequestId(), pendingRequest.getDeployId());
      SingularityPendingRequest existingRequest = deployKeyToMostImportantPendingRequest.get(deployKey);
      if (existingRequest == null) {
        deployKeyToMostImportantPendingRequest.put(deployKey, pendingRequest);
      } else {
        if (pendingRequest.hasPriority(existingRequest)) {
          LOG.debug("Dropping pending request {} because {} has priority {}", existingRequest, pendingRequest);
          deployKeyToMostImportantPendingRequest.put(deployKey, pendingRequest);
        } else {
          LOG.debug("Dropping pending request {} because {} has priority {}", pendingRequest, existingRequest);
        }
      }
    }

    return deployKeyToMostImportantPendingRequest.values();
  }

  public void drainPendingQueue(final SingularitySchedulerStateCache stateCache) {
    final long start = System.currentTimeMillis();

    final Collection<SingularityPendingRequest> pendingRequests = filterPendingRequestsByDeployAndPriority(requestManager.getPendingRequests());

    if (pendingRequests.isEmpty()) {
      LOG.trace("Pending queue was empty");
      return;
    }

    LOG.info("Pending queue had {} requests", pendingRequests.size());

    int totalNewScheduledTasks = 0;
    int heldForScheduledActiveTask = 0;
    int obsoleteRequests = 0;

    for (SingularityPendingRequest pendingRequest : pendingRequests) {
      Optional<SingularityRequestWithState> maybeRequest = requestManager.getRequest(pendingRequest.getRequestId());

      if (shouldScheduleTasks(pendingRequest, maybeRequest)) {
        checkForBounceAndAddToCleaningTasks(pendingRequest, stateCache.getActiveTaskIds(), stateCache.getCleaningTasks());

        final List<SingularityTaskId> matchingTaskIds = getMatchingTaskIds(stateCache, maybeRequest.get().getRequest(), pendingRequest);
        final SingularityDeployStatistics deployStatistics = getDeployStatistics(pendingRequest.getRequestId(), pendingRequest.getDeployId());

        final RequestState requestState = checkCooldown(maybeRequest.get(), deployStatistics);

        int numScheduledTasks = scheduleTasks(stateCache, maybeRequest.get().getRequest(), requestState, deployStatistics, pendingRequest, matchingTaskIds);

        if (numScheduledTasks == 0 && !matchingTaskIds.isEmpty() && maybeRequest.get().getRequest().isScheduled()) {
          LOG.trace("Holding pending request {} because it is scheduled and has an active task", pendingRequest);
          heldForScheduledActiveTask++;
          continue;
        }

        LOG.debug("Pending request {} resulted in {} new scheduled tasks", pendingRequest, numScheduledTasks);

        totalNewScheduledTasks += numScheduledTasks;
      } else {
        LOG.debug("Pending request {} was obsolete (request {})", pendingRequest, SingularityRequestWithState.getRequestState(maybeRequest));

        obsoleteRequests++;
      }

      requestManager.deletePendingRequest(pendingRequest);
    }

    LOG.info("Scheduled {} new tasks ({} obsolete requests, {} held) in {}", totalNewScheduledTasks, obsoleteRequests, heldForScheduledActiveTask, JavaUtils.duration(start));
  }

  private RequestState checkCooldown(SingularityRequestWithState requestWithState, SingularityDeployStatistics deployStatistics) {
    if (requestWithState.getState() != RequestState.SYSTEM_COOLDOWN) {
      return requestWithState.getState();
    }

    if (cooldown.hasCooldownExpired(requestWithState.getRequest(), deployStatistics, Optional.<Integer> absent(), Optional.<Long> absent())) {
      requestManager.exitCooldown(requestWithState.getRequest());
      return RequestState.ACTIVE;
    }

    return requestWithState.getState();
  }

  private boolean shouldScheduleTasks(SingularityPendingRequest pendingRequest, Optional<SingularityRequestWithState> maybeRequest) {
    if (!isRequestActive(maybeRequest)) {
      return false;
    }

    Optional<SingularityRequestDeployState> maybeRequestDeployState = deployManager.getRequestDeployState(pendingRequest.getRequestId());

    return isDeployInUse(maybeRequestDeployState, pendingRequest.getDeployId(), false);
  }

  private void checkForBounceAndAddToCleaningTasks(SingularityPendingRequest pendingRequest, final List<SingularityTaskId> activeTaskIds, final List<SingularityTaskId> cleaningTasks) {
    if (pendingRequest.getPendingType() != PendingType.BOUNCE) {
      return;
    }

    final long now = System.currentTimeMillis();

    final List<SingularityTaskId> matchingTaskIds = SingularityTaskId.matchingAndNotIn(activeTaskIds, pendingRequest.getRequestId(), pendingRequest.getDeployId(), cleaningTasks);

    for (SingularityTaskId matchingTaskId : matchingTaskIds) {
      LOG.debug("Adding task {} to cleanup (bounce)", matchingTaskId.getId());

      taskManager.createCleanupTask(new SingularityTaskCleanup(pendingRequest.getUser(), TaskCleanupType.BOUNCING, now, matchingTaskId));
      cleaningTasks.add(matchingTaskId);
    }

    LOG.info("Added {} tasks for request {} to cleanup bounce queue in {}", matchingTaskIds.size(), pendingRequest.getRequestId(), JavaUtils.duration(now));
  }

  public List<SingularityTaskRequest> getDueTasks() {
    final List<SingularityPendingTask> tasks = taskManager.getPendingTasks();

    final long now = System.currentTimeMillis();

    final List<SingularityPendingTask> dueTasks = Lists.newArrayListWithCapacity(tasks.size());

    for (SingularityPendingTask task : tasks) {
      if (task.getPendingTaskId().getNextRunAt() <= now) {
        dueTasks.add(task);
      }
    }

    final List<SingularityTaskRequest> dueTaskRequests = taskRequestManager.getTaskRequests(dueTasks);

    return checkForStaleScheduledTasks(dueTasks, dueTaskRequests);
  }

  private List<SingularityTaskRequest> checkForStaleScheduledTasks(List<SingularityPendingTask> pendingTasks, List<SingularityTaskRequest> taskRequests) {
    final Set<String> foundPendingTaskId = Sets.newHashSetWithExpectedSize(taskRequests.size());
    final Set<String> requestIds = Sets.newHashSetWithExpectedSize(taskRequests.size());

    for (SingularityTaskRequest taskRequest : taskRequests) {
      foundPendingTaskId.add(taskRequest.getPendingTask().getPendingTaskId().getId());
      requestIds.add(taskRequest.getRequest().getId());
    }

    for (SingularityPendingTask pendingTask : pendingTasks) {
      if (!foundPendingTaskId.contains(pendingTask.getPendingTaskId().getId())) {
        LOG.info("Removing stale pending task {}", pendingTask.getPendingTaskId());
        taskManager.deletePendingTask(pendingTask.getPendingTaskId());
      }
    }

    // TODO this check isn't necessary if we keep track better during deploys
    final Map<String, SingularityRequestDeployState> deployStates = deployManager.getRequestDeployStatesByRequestIds(requestIds);
    final List<SingularityTaskRequest> taskRequestsWithValidDeploys = Lists.newArrayListWithCapacity(taskRequests.size());

    for (SingularityTaskRequest taskRequest : taskRequests) {
      SingularityRequestDeployState requestDeployState = deployStates.get(taskRequest.getRequest().getId());

      if (!matchesDeploy(requestDeployState, taskRequest)) {
        LOG.info("Removing stale pending task {} because the deployId did not match active/pending deploys {}", taskRequest.getPendingTask().getPendingTaskId(), requestDeployState);
        taskManager.deletePendingTask(taskRequest.getPendingTask().getPendingTaskId());
      } else {
        taskRequestsWithValidDeploys.add(taskRequest);
      }
    }

    return taskRequestsWithValidDeploys;
  }

  private boolean matchesDeploy(SingularityRequestDeployState requestDeployState, SingularityTaskRequest taskRequest) {
    if (requestDeployState == null) {
      return false;
    }
    return matchesDeployMarker(requestDeployState.getActiveDeploy(), taskRequest.getDeploy().getId())
        || matchesDeployMarker(requestDeployState.getPendingDeploy(), taskRequest.getDeploy().getId());
  }

  private boolean matchesDeployMarker(Optional<SingularityDeployMarker> deployMarker, String deployId) {
    return deployMarker.isPresent() && deployMarker.get().getDeployId().equals(deployId);
  }

  private void deleteScheduledTasks(final List<SingularityPendingTask> scheduledTasks, SingularityPendingRequest pendingRequest) {
    for (SingularityPendingTask task : Iterables.filter(scheduledTasks, Predicates.and(SingularityPendingTask.matchingRequest(pendingRequest.getRequestId()), SingularityPendingTask.matchingDeploy(pendingRequest.getDeployId())))) {
      LOG.debug("Deleting pending task {} in order to reschedule {}", task.getPendingTaskId().getId(), pendingRequest);
      taskManager.deletePendingTask(task.getPendingTaskId());
    }
  }

  private List<SingularityTaskId> getMatchingTaskIds(SingularitySchedulerStateCache stateCache, SingularityRequest request, SingularityPendingRequest pendingRequest) {
    if (!request.isScheduled()) {
      return SingularityTaskId.matchingAndNotIn(stateCache.getActiveTaskIds(), request.getId(), pendingRequest.getDeployId(), stateCache.getCleaningTasks());
    } else {
      return Lists.newArrayList(Iterables.filter(stateCache.getActiveTaskIds(), SingularityTaskId.matchingRequest(request.getId())));
    }
  }

  private int scheduleTasks(SingularitySchedulerStateCache stateCache, SingularityRequest request, RequestState state, SingularityDeployStatistics deployStatistics, SingularityPendingRequest pendingRequest, List<SingularityTaskId> matchingTaskIds) {
    deleteScheduledTasks(stateCache.getScheduledTasks(), pendingRequest);

    final int numMissingInstances = getNumMissingInstances(matchingTaskIds, request, pendingRequest);

    if (numMissingInstances > 0) {
      LOG.debug("Missing {} instances of request {} (matching tasks: {}), pending request: {}", numMissingInstances, request.getId(), matchingTaskIds, pendingRequest);

      final List<SingularityPendingTask> scheduledTasks = getScheduledTaskIds(numMissingInstances, matchingTaskIds, request, state, deployStatistics, pendingRequest.getDeployId(), pendingRequest);

      if (!scheduledTasks.isEmpty()) {
        LOG.trace("Scheduling tasks: {}", scheduledTasks);

        taskManager.createPendingTasks(scheduledTasks);
      } else {
        LOG.info("No new scheduled tasks found for {}, setting state to {}", request.getId(), RequestState.FINISHED);
        requestManager.finish(request);
      }

    } else if (numMissingInstances < 0) {
      LOG.debug("Missing instances is negative: {}, request {}, matching tasks: {}", numMissingInstances, request, matchingTaskIds);

      final long now = System.currentTimeMillis();

      for (int i = 0; i < Math.abs(numMissingInstances); i++) {
        final SingularityTaskId toCleanup = matchingTaskIds.get(i);

        LOG.info("Cleaning up task {} due to new request {} - scaling down to {} instances", toCleanup.getId(), request.getId(), request.getInstancesSafe());

        taskManager.createCleanupTask(new SingularityTaskCleanup(Optional.<String> absent(), TaskCleanupType.SCALING_DOWN, now, toCleanup));
      }
    }

    return numMissingInstances;
  }

  private boolean isRequestActive(Optional<SingularityRequestWithState> maybeRequestWithState) {
    return SingularityRequestWithState.isActive(maybeRequestWithState);
  }

  private boolean isDeployInUse(Optional<SingularityRequestDeployState> requestDeployState, String deployId, boolean mustMatchActiveDeploy) {
    if (!requestDeployState.isPresent()) {
      return false;
    }

    if (matchesDeployMarker(requestDeployState.get().getActiveDeploy(), deployId)) {
      return true;
    }

    if (mustMatchActiveDeploy) {
      return false;
    }

    return matchesDeployMarker(requestDeployState.get().getPendingDeploy(), deployId);
  }

  private Optional<PendingType> handleCompletedTaskWithStatistics(Optional<SingularityTask> maybeActiveTask, SingularityTaskId taskId, long timestamp, ExtendedTaskState state, SingularityDeployStatistics deployStatistics, SingularityCreateResult taskHistoryUpdateCreateResult, SingularitySchedulerStateCache stateCache) {
    final Optional<SingularityRequestWithState> maybeRequestWithState = requestManager.getRequest(taskId.getRequestId());

    if (!isRequestActive(maybeRequestWithState)) {
      LOG.warn("Not scheduling a new task, {} is {}", taskId.getRequestId(), SingularityRequestWithState.getRequestState(maybeRequestWithState));
      return Optional.absent();
    }

    RequestState requestState = maybeRequestWithState.get().getState();
    final SingularityRequest request = maybeRequestWithState.get().getRequest();

    final Optional<SingularityRequestDeployState> requestDeployState = deployManager.getRequestDeployState(request.getId());

    if (!isDeployInUse(requestDeployState, taskId.getDeployId(), true)) {
      LOG.debug("Task {} completed, but it didn't match active deploy state {} - ignoring", taskId.getId(), requestDeployState);
      return Optional.absent();
    }

    if (taskHistoryUpdateCreateResult == SingularityCreateResult.CREATED && requestState != RequestState.SYSTEM_COOLDOWN) {
      mailer.sendTaskCompletedMail(taskId, request, state);
    } else if (requestState == RequestState.SYSTEM_COOLDOWN) {
      LOG.debug("Not sending a task completed email because task {} is in SYSTEM_COOLDOWN", taskId);
    } else {
      LOG.debug("Not sending a task completed email for task {} because Singularity already processed this update", taskId);
    }

    if (!state.isSuccess() && taskHistoryUpdateCreateResult == SingularityCreateResult.CREATED && cooldown.shouldEnterCooldown(request, taskId, requestState, deployStatistics, timestamp)) {
      LOG.info("Request {} is entering cooldown due to task {}", request.getId(), taskId);
      requestState = RequestState.SYSTEM_COOLDOWN;
      requestManager.cooldown(request);
      mailer.sendRequestInCooldownMail(request);
    }

    if (request.isOneOff()) {
      return Optional.absent();
    }

    PendingType pendingType = PendingType.TASK_DONE;

    if (state.isSuccess()) {
      if (requestState == RequestState.SYSTEM_COOLDOWN) {
        // TODO send not cooldown anymore email
        LOG.info("Request {} succeeded a task, removing from cooldown", request.getId());
        requestState = RequestState.ACTIVE;
        requestManager.exitCooldown(request);
      }
    } else if (request.isScheduled()) {
      if (state.isFailed()) {
        if (shouldRetryImmediately(request, deployStatistics)) {
          pendingType = PendingType.RETRY;
        }
      } else {
        LOG.debug("Setting pendingType to retry for request {}, because it failed due to {}", request.getId(), state);
        pendingType = PendingType.RETRY;
      }
    }

    SingularityPendingRequest pendingRequest = new SingularityPendingRequest(request.getId(), requestDeployState.get().getActiveDeploy().get().getDeployId(), pendingType);

    scheduleTasks(stateCache, request, requestState, deployStatistics, pendingRequest, getMatchingTaskIds(stateCache, request, pendingRequest));

    return Optional.of(pendingType);
  }

  private SingularityDeployStatistics getDeployStatistics(String requestId, String deployId) {
    final Optional<SingularityDeployStatistics> maybeDeployStatistics = deployManager.getDeployStatistics(requestId, deployId);

    if (maybeDeployStatistics.isPresent()) {
      return maybeDeployStatistics.get();
    }

    return new SingularityDeployStatisticsBuilder(requestId, deployId).build();
  }

  public void handleCompletedTask(Optional<SingularityTask> maybeActiveTask, SingularityTaskId taskId, long timestamp, ExtendedTaskState state, SingularityCreateResult taskHistoryUpdateCreateResult, SingularitySchedulerStateCache stateCache) {
    final SingularityDeployStatistics deployStatistics = getDeployStatistics(taskId.getRequestId(), taskId.getDeployId());

    if (maybeActiveTask.isPresent()) {
      taskManager.deleteActiveTask(taskId.getId());
    }

    taskManager.createLBCleanupTask(taskId);

    final Optional<PendingType> scheduleResult = handleCompletedTaskWithStatistics(maybeActiveTask, taskId, timestamp, state, deployStatistics, taskHistoryUpdateCreateResult, stateCache);

    if (taskHistoryUpdateCreateResult == SingularityCreateResult.EXISTED) {
      return;
    }

    SingularityDeployStatisticsBuilder bldr = deployStatistics.toBuilder();

    if (!bldr.getLastFinishAt().isPresent() || timestamp > bldr.getLastFinishAt().get()) {
      bldr.setLastFinishAt(Optional.of(timestamp));
      bldr.setLastTaskState(Optional.of(state));
    }

    final ListMultimap<Integer, Long> instanceSequentialFailureTimestamps = bldr.getInstanceSequentialFailureTimestamps();
    final List<Long> sequentialFailureTimestamps = instanceSequentialFailureTimestamps.get(taskId.getInstanceNo());

    if (!state.isSuccess()) {
      if (SingularityTaskHistoryUpdate.getUpdate(taskManager.getTaskHistoryUpdates(taskId), ExtendedTaskState.TASK_CLEANING).isPresent()) {
        LOG.debug("{} failed with {} after cleaning - ignoring it for cooldown", taskId, state);
      } else {

        if (sequentialFailureTimestamps.size() < configuration.getCooldownAfterFailures()) {
          sequentialFailureTimestamps.add(timestamp);
        } else if (timestamp > sequentialFailureTimestamps.get(0)) {
          sequentialFailureTimestamps.set(0, timestamp);
        }

        Collections.sort(sequentialFailureTimestamps);
      }
    } else {
      bldr.setNumSuccess(bldr.getNumSuccess() + 1);
      sequentialFailureTimestamps.clear();
    }

    if (scheduleResult.isPresent() && scheduleResult.get() == PendingType.RETRY) {
      bldr.setNumSequentialRetries(bldr.getNumSequentialRetries() + 1);
    } else {
      bldr.setNumSequentialRetries(0);
    }

    final SingularityDeployStatistics newStatistics = bldr.build();

    LOG.trace("Saving new deploy statistics {}", newStatistics);

    deployManager.saveDeployStatistics(newStatistics);
  }

  private boolean shouldRetryImmediately(SingularityRequest request, SingularityDeployStatistics deployStatistics) {
    if (!request.getNumRetriesOnFailure().isPresent()) {
      return false;
    }

    final int numRetriesInARow = deployStatistics.getNumSequentialRetries();

    if (numRetriesInARow >= request.getNumRetriesOnFailure().get()) {
      LOG.debug("Request {} had {} retries in a row, not retrying again (num retries on failure: {})", request.getId(), numRetriesInARow, request.getNumRetriesOnFailure());
      return false;
    }

    LOG.debug("Request {} had {} retries in a row - retrying again (num retries on failure: {})", request.getId(), numRetriesInARow, request.getNumRetriesOnFailure());

    return true;
  }

  private int getNumMissingInstances(List<SingularityTaskId> matchingTaskIds, SingularityRequest request, SingularityPendingRequest pendingRequest) {
    if (request.isOneOff() && pendingRequest.getPendingType() == PendingType.ONEOFF) {
      return 1;
    }

    final int numInstances = request.getInstancesSafe();

    return numInstances - matchingTaskIds.size();
  }

  private List<SingularityPendingTask> getScheduledTaskIds(int numMissingInstances, List<SingularityTaskId> matchingTaskIds, SingularityRequest request, RequestState state, SingularityDeployStatistics deployStatistics, String deployId, SingularityPendingRequest pendingRequest) {
    final Optional<Long> nextRunAt = getNextRunAt(request, state, deployStatistics, pendingRequest.getPendingType());

    if (!nextRunAt.isPresent()) {
      return Collections.emptyList();
    }

    final Set<Integer> inuseInstanceNumbers = Sets.newHashSetWithExpectedSize(matchingTaskIds.size());

    for (SingularityTaskId matchingTaskId : matchingTaskIds) {
      inuseInstanceNumbers.add(matchingTaskId.getInstanceNo());
    }

    final List<SingularityPendingTask> newTasks = Lists.newArrayListWithCapacity(numMissingInstances);

    int nextInstanceNumber = 1;

    for (int i = 0; i < numMissingInstances; i++) {
      while (inuseInstanceNumbers.contains(nextInstanceNumber)) {
        nextInstanceNumber++;
      }

      newTasks.add(new SingularityPendingTask(new SingularityPendingTaskId(request.getId(), deployId, nextRunAt.get(), nextInstanceNumber, pendingRequest.getPendingType()), pendingRequest.getCmdLineArgs()));

      nextInstanceNumber++;
    }

    return newTasks;
  }

  private Optional<Long> getNextRunAt(SingularityRequest request, RequestState state, SingularityDeployStatistics deployStatistics, PendingType pendingType) {
    final long now = System.currentTimeMillis();

    long nextRunAt = now;

    if (request.isScheduled()) {
      if (pendingType == PendingType.IMMEDIATE || pendingType == PendingType.RETRY) {
        LOG.info("Scheduling requested immediate run of {}", request.getId());
      } else {
        try {
          Date scheduleFrom = new Date(now);

          CronExpression cronExpression = new CronExpression(request.getQuartzScheduleSafe());

          final Date nextRunAtDate = cronExpression.getNextValidTimeAfter(scheduleFrom);

          if (nextRunAtDate == null) {
            return Optional.absent();
          }

          LOG.trace("Calculating nextRunAtDate for {} (schedule: {}): {} (from: {})", request.getId(), request.getSchedule(), nextRunAtDate, scheduleFrom);

          nextRunAt = Math.max(nextRunAtDate.getTime(), now); // don't create a schedule that is overdue as this is used to indicate that singularity is not fulfilling requests.

          LOG.trace("Scheduling next run of {} (schedule: {}) at {} (from: {})", request.getId(), request.getSchedule(), nextRunAtDate, scheduleFrom);
        } catch (ParseException pe) {
          throw Throwables.propagate(pe);
        }
      }
    }

    if (state == RequestState.SYSTEM_COOLDOWN && pendingType != PendingType.NEW_DEPLOY) {
      final long prevNextRunAt = nextRunAt;
      nextRunAt = Math.max(nextRunAt, now + TimeUnit.SECONDS.toMillis(configuration.getCooldownMinScheduleSeconds()));
      LOG.trace("Adjusted next run of {} to {} (from: {}) due to cooldown", request.getId(), nextRunAt, prevNextRunAt);
    }

    return Optional.of(nextRunAt);
  }

}
