package com.twitter.mesos.scheduler;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.twitter.common.base.Closure;
import com.twitter.common.base.ExceptionalClosure;
import com.twitter.common.base.MorePreconditions;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.mesos.Message;
import com.twitter.mesos.Tasks;
import com.twitter.mesos.codec.ThriftBinaryCodec;
import com.twitter.mesos.gen.AssignedTask;
import com.twitter.mesos.gen.ExecutorMessage;
import com.twitter.mesos.gen.JobConfiguration;
import com.twitter.mesos.gen.LiveTask;
import com.twitter.mesos.gen.LiveTaskInfo;
import com.twitter.mesos.gen.NonVolatileSchedulerState;
import com.twitter.mesos.gen.RegisteredTaskUpdate;
import com.twitter.mesos.gen.ResourceConsumption;
import com.twitter.mesos.gen.RestartExecutor;
import com.twitter.mesos.gen.ScheduleStatus;
import com.twitter.mesos.gen.TaskEvent;
import com.twitter.mesos.gen.TaskQuery;
import com.twitter.mesos.gen.ScheduledTask;
import com.twitter.mesos.gen.TwitterTaskInfo;
import com.twitter.mesos.scheduler.configuration.ConfigurationManager;
import com.twitter.mesos.scheduler.persistence.PersistenceLayer;
import org.apache.commons.lang.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.twitter.mesos.gen.ScheduleStatus.*;

/**
 * Implementation of the scheduler core.
 *
 * @author wfarner
 */
public class SchedulerCoreImpl implements SchedulerCore {
  private static final Logger LOG = Logger.getLogger(SchedulerCore.class.getName());

  // Schedulers that are responsible for triggering execution of jobs.
  private final List<JobManager> jobManagers;

  private final AtomicInteger nextTaskId = new AtomicInteger(0);

  // The mesos framework ID of the scheduler, set to null until the framework is registered.
  private final AtomicReference<String> frameworkId = new AtomicReference<String>(null);

  // Stores the configured tasks.
  private final TaskStore taskStore = new TaskStore();

  // Work queue that stores pending asynchronous tasks.
  private final WorkQueue workQueue;

  private final PersistenceLayer<NonVolatileSchedulerState> persistenceLayer;
  // TODO(wfarner): Remove this once the old state is overwritten.
  private final ExecutorTracker executorTracker;

  // Scheduler driver used for communication with other nodes in the cluster.
  private final AtomicReference<Driver> schedulerDriver = new AtomicReference<Driver>();

  @Inject
  public SchedulerCoreImpl(CronJobManager cronScheduler, ImmediateJobManager immediateScheduler,
      PersistenceLayer<NonVolatileSchedulerState> persistenceLayer,
      ExecutorTracker executorTracker, WorkQueue workQueue) {
    // The immediate scheduler will accept any job, so it's important that other schedulers are
    // placed first.
    jobManagers = Arrays.asList(cronScheduler, immediateScheduler);
    this.persistenceLayer = Preconditions.checkNotNull(persistenceLayer);
    this.executorTracker = Preconditions.checkNotNull(executorTracker);
    this.workQueue = Preconditions.checkNotNull(workQueue);

    restore();
  }

  @Override
  public void registered(Driver driver, String frameworkId) {
    this.schedulerDriver.set(Preconditions.checkNotNull(driver));
    this.frameworkId.set(Preconditions.checkNotNull(frameworkId));
    persist();

    executorTracker.start(new Closure<String>() {
      @Override public void execute(String slaveId) throws RuntimeException {
        try {
          LOG.info("Sending restart request to executor " + slaveId);
          ExecutorMessage message = new ExecutorMessage();
          message.setRestartExecutor(new RestartExecutor());

          sendExecutorMessage(slaveId, message);
        } catch (ThriftBinaryCodec.CodingException e) {
          LOG.log(Level.WARNING, "Failed to send executor status.", e);
        }
      }
    });
  }

  private void sendExecutorMessage(String slaveId, ExecutorMessage message)
      throws ThriftBinaryCodec.CodingException {
    Driver driverRef = schedulerDriver.get();
    if (driverRef == null) {
      LOG.info("No driver available, unable to send executor status.");
      return;
    }

    int result = driverRef.sendMessage(new Message(slaveId, message));
    if (result != 0) {
      LOG.warning("Executor message failed to send, return code " + result);
    }
  }

  @Override
  public synchronized Iterable<ScheduledTask> getTasks(final TaskQuery query) {
    return getTasks(query, Predicates.<ScheduledTask>alwaysTrue());
  }

  @Override
  public synchronized Iterable<ScheduledTask> getTasks(final TaskQuery query,
      Predicate<ScheduledTask>... filters) {
    return taskStore.fetch(query, filters);
  }

  @Override
  public synchronized Iterable<LiveTask> getLiveTasks(final TaskQuery query) {
    return taskStore.getLiveTasks(taskStore.fetch(query));
  }

  @Override
  public synchronized boolean hasActiveJob(final String owner, final String jobName) {
    TaskQuery query = new TaskQuery().setOwner(owner).setJobName(jobName)
        .setStatuses(Tasks.ACTIVE_STATES);
    return !Iterables.isEmpty(getTasks(query)) || Iterables.any(jobManagers,
        new Predicate<JobManager>() {
          @Override public boolean apply(JobManager manager) {
            return manager.hasJob(owner, jobName);
          }
    });
  }

  private int generateTaskId() {
    return nextTaskId.incrementAndGet();
  }

  // TODO(wfarner): This is does not currently clear out tasks when a host is decommissioned.
  //    Figure out a solution that will work.  Might require mesos support for fetching the list
  //    of slaves.
  @Override
  public synchronized void updateRegisteredTasks(RegisteredTaskUpdate update) {
    Preconditions.checkNotNull(update);
    Preconditions.checkNotNull(update.getTaskInfos());

    try {
      final Map<Integer, LiveTaskInfo> taskInfoMap = Maps.newHashMap();

      for (LiveTaskInfo taskInfo : update.getTaskInfos()) {
        taskInfoMap.put(taskInfo.getTaskId(), taskInfo);
      }

      // TODO(wfarner): Have the scheduler only retain configurations for live jobs,
      //    and acquire all other state from slaves.
      //    This will allow the scheduler to only persist active tasks.

      // Look for any tasks that we don't know about, or this slave should not be modifying.
      final Set<Integer> recognizedTasks = Sets.newHashSet(Iterables.transform(
          taskStore.fetch(
              new TaskQuery().setTaskIds(taskInfoMap.keySet()).setSlaveHost(update.getSlaveHost())),
          Tasks.GET_TASK_ID));
      Set<Integer> unknownTasks = Sets.difference(taskInfoMap.keySet(), recognizedTasks);
      if (!unknownTasks.isEmpty()) {
        LOG.severe("Received task info update from executor " + update.getSlaveHost()
                   + " for tasks unknown or belonging to a different host: " + unknownTasks);
      }

      // Remove unknown tasks from the request to prevent badness later.
      taskInfoMap.keySet().removeAll(unknownTasks);

      // Update the resource information for the tasks that we currently have on record.
      for (int taskId : recognizedTasks) {
        final LiveTaskInfo taskUpdate = taskInfoMap.get(taskId);
        taskStore.mutateVolatileState(taskId,
            new Closure<VolatileTaskState>() {
                @Override public void execute(VolatileTaskState state) {
                  if (taskUpdate.getResources() != null) {
                    state.resources = new ResourceConsumption(taskUpdate.getResources());
                  }
                }
              });
      }

      Predicate<LiveTaskInfo> getKilledTasks = new Predicate<LiveTaskInfo>() {
        @Override public boolean apply(LiveTaskInfo update) {
          return update.getStatus() == KILLED;
        }
      };

      Function<LiveTaskInfo, Integer> getTaskId = new Function<LiveTaskInfo, Integer>() {
        @Override public Integer apply(LiveTaskInfo update) {
          return update.getTaskId();
        }
      };

      // Find any tasks that we believe to be running, but the slave reports as dead.
      Set<Integer> reportedDeadTasks = Sets.newHashSet(
          Iterables.transform(Iterables.filter(taskInfoMap.values(), getKilledTasks), getTaskId));
      Set<Integer> deadTasks = Sets.newHashSet(
          Iterables.transform(
              taskStore.fetch(new TaskQuery().setTaskIds(reportedDeadTasks)
                  .setStatuses(Sets.newHashSet(RUNNING))),
              Tasks.GET_TASK_ID));
      if (!deadTasks.isEmpty()) {
        LOG.info("Found tasks that were recorded as RUNNING but slave " + update.getSlaveHost()
                 + " reports as KILLED: " + deadTasks);
        setTaskStatus(new TaskQuery().setTaskIds(deadTasks), KILLED);
      }

      // Find any tasks assigned to this slave but the slave does not report.
      Predicate<ScheduledTask> isTaskReported = new Predicate<ScheduledTask>() {
        @Override public boolean apply(ScheduledTask task) {
          return recognizedTasks.contains(task.getAssignedTask().getTaskId());
        }
      };
      Predicate<ScheduledTask> lastEventBeyondGracePeriod = new Predicate<ScheduledTask>() {
        @Override public boolean apply(ScheduledTask task) {
          long taskAgeMillis = System.currentTimeMillis()
              - Iterables.getLast(task.getTaskEvents()).getTimestamp();
          return taskAgeMillis > Amount.of(10, Time.MINUTES).as(Time.MILLISECONDS);
        }
      };

      TaskQuery slaveAssignedTaskQuery = new TaskQuery().setSlaveHost(update.getSlaveHost());
      Set<ScheduledTask> missingNotRunningTasks = Sets.newHashSet(
          taskStore.fetch(slaveAssignedTaskQuery,
              Predicates.not(isTaskReported),
              Predicates.not(Tasks.makeStatusFilter(RUNNING)),
              lastEventBeyondGracePeriod));
      if (!missingNotRunningTasks.isEmpty()) {
        LOG.info("Removing non-running tasks no longer reported by slave " + update.getSlaveHost()
                 + ": " + Iterables.transform(missingNotRunningTasks, Tasks.GET_TASK_ID));
        taskStore.remove(missingNotRunningTasks);
        persist();
      }

      Set<ScheduledTask> missingRunningTasks = Sets.newHashSet(
          taskStore.fetch(slaveAssignedTaskQuery,
              Predicates.not(isTaskReported),
              Tasks.makeStatusFilter(RUNNING)));
      if (!missingRunningTasks.isEmpty()) {
        Set<Integer> missingIds = Sets.newHashSet(
            Iterables.transform(missingRunningTasks, Tasks.GET_TASK_ID));
        LOG.info("Slave " + update.getSlaveHost() + " no longer reports running tasks: "
                 + missingIds + ", reporting as LOST.");
        setTaskStatus(new TaskQuery().setTaskIds(missingIds), LOST);
      }
    } catch (Throwable t) {
      LOG.log(Level.WARNING, "Uncaught exception.", t);
    }
  }

  @Override
  public synchronized void createJob(JobConfiguration job) throws ScheduleException,
      ConfigurationManager.TaskDescriptionException {
    Preconditions.checkNotNull(job);

    for (TwitterTaskInfo config : job.getTaskConfigs()) {
      ConfigurationManager.populateFields(job, config);
    }

    if (hasActiveJob(job.getOwner(), job.getName())) {
      throw new ScheduleException(String.format("Job already exists: %s/%s",
          job.getOwner(), job.getName()));
    }

    boolean accepted = false;
    for (JobManager manager : jobManagers) {
      if (manager.receiveJob(job)) {
        accepted = true;
        LOG.info("Job accepted by scheduler: " + manager.getClass().getName());
        persist();
        break;
      }
    }

    if (!accepted) {
      LOG.severe("Job was not accepted by any of the configured schedulers, discarding.");
      LOG.severe("Discarded job: " + job);
      throw new ScheduleException("Job not accepted, discarding.");
    }
  }

  @Override
  public synchronized void runJob(JobConfiguration job) {
    List<ScheduledTask> newTasks = Lists.newArrayList();

    int shardId = 0;
    for (TwitterTaskInfo task : Preconditions.checkNotNull(job.getTaskConfigs())) {
      AssignedTask assigned = new AssignedTask()
          .setTaskId(generateTaskId())
          .setShardId(shardId++)
          .setTask(task);
      ScheduledTask ScheduledTask = changeTaskStatus(new ScheduledTask()
          .setAssignedTask(assigned), PENDING);
      newTasks.add(ScheduledTask);
    }

    taskStore.add(newTasks);
    persist();
  }

  @Override
  public synchronized TwitterTask offer(final String slaveId, final String slaveHost,
      Map<String, String> offerParams) throws ScheduleException {
    MorePreconditions.checkNotBlank(slaveId);
    MorePreconditions.checkNotBlank(slaveHost);
    Preconditions.checkNotNull(offerParams);

    final TwitterTaskInfo offer;
    try {
      offer = ConfigurationManager.makeConcrete(offerParams);
    } catch (ConfigurationManager.TaskDescriptionException e) {
      LOG.log(Level.SEVERE, "Invalid slave offer", e);
      return null;
    }

    TaskQuery query = new TaskQuery();
    query.addToStatuses(PENDING);

    Predicate<ScheduledTask> satisfiedFilter = new Predicate<ScheduledTask>() {
      @Override public boolean apply(ScheduledTask task) {
        return ConfigurationManager.satisfied(task.getAssignedTask().getTask(), offer);
      }
    };

    Iterable<ScheduledTask> candidates = taskStore.fetch(query, satisfiedFilter);
    if (Iterables.isEmpty(candidates)) return null;

    LOG.info("Found " + Iterables.size(candidates) + " candidates for offer.");

    ScheduledTask task = Iterables.get(candidates, 0);
    task = taskStore.mutate(task, new ExceptionalClosure<ScheduledTask, RuntimeException>() {
        @Override public void execute(ScheduledTask mutable) {
          mutable.getAssignedTask().setSlaveId(slaveId).setSlaveHost(slaveHost);
          changeTaskStatus(mutable, STARTING);
        }
    });

    if (task == null) {
      LOG.log(Level.SEVERE, "Unable to find matching mutable task!");
      return null;
    }

    // TODO(wfarner): Remove this hack once mesos core does not read parameters.
    Map<String, String> params = ImmutableMap.of(
      "cpus", String.valueOf((int) task.getAssignedTask().getTask().getNumCpus()),
      "mem", String.valueOf(task.getAssignedTask().getTask().getRamMb())
    );

    AssignedTask assignedTask = task.getAssignedTask();
    LOG.info(String.format("Offer on slave %s (id %s) is being assigned task for %s/%s.",
        slaveHost, assignedTask.getSlaveId(), assignedTask.getTask().getOwner(),
        assignedTask.getTask().getJobName()));

    persist();
    return new TwitterTask(assignedTask.getTaskId(), slaveId,
        assignedTask.getTask().getJobName() + "-" + assignedTask.getTaskId(), params,
        assignedTask);
  }

  /**
   * Schedules {@code tasks}, which are expected to be copies of existing tasks.  The tasks provided
   * will be modified.
   *
   * @param tasks Copies of other tasks, to be scheduled.
   */
  private void scheduleTaskCopies(List<ScheduledTask> tasks) {
    for (ScheduledTask task : tasks) {
      // The shard ID in the assigned task is left unchanged.
      task.getAssignedTask().unsetSlaveId();
      task.getAssignedTask().unsetSlaveHost();
      task.unsetTaskEvents();
      task.setAncestorId(task.getAssignedTask().getTaskId());
      task.getAssignedTask().setTaskId(generateTaskId());
      changeTaskStatus(task, PENDING);
    }

    LOG.info("Tasks being rescheduled: " + tasks);

    taskStore.add(tasks);
  }

  /**
   * Sets the current status for a task, and records the status change into the task events
   * audit log.
   *
   * @param task Task whose status is changing.
   * @param status New status for the task.
   * @return A reference to the task.
   */
  private ScheduledTask changeTaskStatus(ScheduledTask task, ScheduleStatus status) {
    task.setStatus(status);
    task.addToTaskEvents(new TaskEvent()
        .setTimestamp(System.currentTimeMillis())
        .setStatus(status));
    return task;
  }

  @Override
  public synchronized void setTaskStatus(TaskQuery query, final ScheduleStatus status) {
    Preconditions.checkNotNull(query);
    Preconditions.checkNotNull(status);

    // Only allow state transition from non-terminal state.
    Iterable<ScheduledTask> modifiedTasks =
        taskStore.fetch(query, Predicates.not(Tasks.TERMINATED_FILTER));

    final List<ScheduledTask> newTasks = Lists.newLinkedList();

    switch (status) {
      case PENDING:
      case STARTING:
      case RUNNING:
        // Simply assign the new state.
        taskStore.mutate(modifiedTasks, new ExceptionalClosure<ScheduledTask, RuntimeException>() {
          @Override public void execute(ScheduledTask mutable) {
            changeTaskStatus(mutable, status);
          }
        });

        break;

      case FINISHED:
        // Assign the FINISHED state to non-daemon tasks, move daemon tasks to PENDING.
        taskStore.mutate(modifiedTasks, new ExceptionalClosure<ScheduledTask, RuntimeException>() {
          @Override public void execute(ScheduledTask mutable) {
            if (mutable.getAssignedTask().getTask().isIsDaemon()) {
              LOG.info("Rescheduling daemon task " + mutable.getAssignedTask().getTaskId());
              newTasks.add(new ScheduledTask(mutable));
            }

            changeTaskStatus(mutable, FINISHED);
          }
        });

        break;

      case FAILED:
        // Increment failure count, move to pending state, unless failure limit has been reached.
        taskStore.mutate(modifiedTasks, new ExceptionalClosure<ScheduledTask, RuntimeException>() {
          @Override public void execute(ScheduledTask mutable) {
            mutable.setFailureCount(mutable.getFailureCount() + 1);

            boolean failureLimitReached =
                (mutable.getAssignedTask().getTask().getMaxTaskFailures() != -1)
                && (mutable.getFailureCount()
                    >= mutable.getAssignedTask().getTask().getMaxTaskFailures());

            if (!failureLimitReached) {
              LOG.info("Rescheduling failed task below failure limit: "
                       + mutable.getAssignedTask().getTaskId());
              newTasks.add(new ScheduledTask(mutable));
            }

            changeTaskStatus(mutable, FAILED);
          }
        });

        break;

      case KILLED:
        taskStore.mutate(modifiedTasks, new ExceptionalClosure<ScheduledTask, RuntimeException>() {
          @Override public void execute(ScheduledTask mutable) {
            // This can happen when the executor is killed, or the task process itself is killed.
            LOG.info("Rescheduling " + status + " task: " + mutable.getAssignedTask().getTaskId());
            newTasks.add(new ScheduledTask(mutable));
            changeTaskStatus(mutable, status);
          }
        });

        break;

      case LOST:
      case NOT_FOUND:
        // Move to pending state.
        taskStore.mutate(modifiedTasks, new ExceptionalClosure<ScheduledTask, RuntimeException>() {
          @Override public void execute(ScheduledTask mutable) {
            LOG.info("Rescheduling " + status + " task: " + mutable.getAssignedTask().getTaskId());
            newTasks.add(new ScheduledTask(mutable));
            changeTaskStatus(mutable, status);
          }
        });

        break;

      default:
        LOG.severe("Unknown schedule status " + status + " cannot be applied to query " + query);
    }

    if (newTasks.isEmpty()) return;
    scheduleTaskCopies(newTasks);
    persist();
  }

  @Override
  public synchronized void killTasks(final TaskQuery query) throws ScheduleException {
    Preconditions.checkNotNull(query);

    LOG.info("Killing tasks matching " + query);

    // If this looks like a query for all tasks in a job, instruct the scheduler modules to delete
    // the job.
    boolean matchingScheduler = false;
    if (!StringUtils.isEmpty(query.getOwner()) && !StringUtils.isEmpty(query.getJobName())
        && query.getStatusesSize() == 0
        && query.getTaskIdsSize() == 0) {
      for (JobManager manager : jobManagers) {
        if (manager.deleteJob(query.getOwner(), query.getJobName())) matchingScheduler = true;
      }
    }

    // KillTasks will not change state of terminated tasks.
    query.setStatuses(Tasks.ACTIVE_STATES);

    Iterable<ScheduledTask> toKill = taskStore.fetch(query);

    if (!matchingScheduler && Iterables.isEmpty(toKill)) {
      throw new ScheduleException("No tasks matching query found.");
    }

    List<ScheduledTask> toRemove = Lists.newArrayList();
    for (final ScheduledTask task : toKill) {
      if (task.getStatus() == PENDING) {
        toRemove.add(task);
      } else {
        taskStore.mutate(task, new Closure<ScheduledTask>() {
          @Override public void execute(ScheduledTask mutable) {
            changeTaskStatus(mutable, KILLED_BY_CLIENT);
          }
        });

        doWorkWithDriver(new Function<Driver, Integer>() {
          @Override public Integer apply(Driver driver) {
            return driver.killTask(task.getAssignedTask().getTaskId());
          }
        });
      }
    }

    taskStore.remove(toRemove);
    persist();
  }

  /**
   * Executes a unit of work that uses the scheduler driver.  This exists as a convenience function
   * for any tasks that require use of the {@link Driver}, for automatic retrying in the
   * event that the driver is not available.
   *
   * @param work The work to execute.  Should return the status code provided by the driver
   *    (0 denotes success, non-zero denotes a failure that should be retried).
   */
  private void doWorkWithDriver(final Function<Driver, Integer> work) {
    workQueue.doWork(new Callable<Boolean>() {
      @Override public Boolean call() {
        if (frameworkId.get() == null) {
          LOG.info("Unable to send framework messages, framework not registered.");
          return false;
        }

        Driver driver = schedulerDriver.get();
        return driver != null && work.apply(driver) == 0;
      }
    });
  }

  @Override
  public synchronized Set<Integer> restartTasks(Set<Integer> taskIds) {
    MorePreconditions.checkNotBlank(taskIds);
    LOG.info("Restart requested for tasks " + taskIds);

    Iterable<ScheduledTask> tasks = taskStore.fetch(new TaskQuery().setTaskIds(taskIds));
    if (Iterables.size(tasks) != taskIds.size()) {
      Set<Integer> unknownTasks = Sets.difference(taskIds,
          Sets.newHashSet(Iterables.transform(tasks, Tasks.GET_TASK_ID)));

      LOG.warning("Restart requested for unknown tasks " + unknownTasks);
    }

    Iterable<ScheduledTask> activeTasks = Iterables.filter(tasks, Tasks.ACTIVE_FILTER);
    Iterable<ScheduledTask> inactiveTasks = Iterables.filter(tasks,
        Predicates.not(Tasks.ACTIVE_FILTER));
    if (!Iterables.isEmpty(inactiveTasks)) {
      LOG.warning("Restart request rejected for inactive tasks "
                  + Iterables.transform(inactiveTasks, Tasks.GET_TASK_ID));
    }

    for (final ScheduledTask task : activeTasks) {
      ScheduledTask copy = new ScheduledTask(task);
      taskStore.mutate(task, new Closure<ScheduledTask>() {
        @Override public void execute(ScheduledTask mutable) {
          changeTaskStatus(mutable, KILLED_BY_CLIENT);
        }
      });

      scheduleTaskCopies(Arrays.asList(copy));

      if (task.status != PENDING) {
        doWorkWithDriver(new Function<Driver, Integer>() {
          @Override public Integer apply(Driver driver) {
            return driver.killTask(task.getAssignedTask().getTaskId());
          }
        });
      }
    }

    persist();

    return Sets.newHashSet(Iterables.transform(activeTasks, Tasks.GET_TASK_ID));
  }

  private void persist() {
    LOG.info("Saving scheduler state.");
    NonVolatileSchedulerState state = new NonVolatileSchedulerState();
    state.setFrameworkId(frameworkId.get());
    state.setNextTaskId(nextTaskId.get());
    state.setTasks(Lists.newArrayList(taskStore.fetch(new TaskQuery())));
    Map<String, List<JobConfiguration>> moduleState = Maps.newHashMap();
    for (JobManager manager : jobManagers) {
      // TODO(wfarner): This is fragile - stored state will not survive a code refactor.
      moduleState.put(manager.getUniqueKey(), Lists.newArrayList(manager.getState()));
    }
    state.setModuleJobs(moduleState);

    try {
      persistenceLayer.commit(state);
    } catch (PersistenceLayer.PersistenceException e) {
      LOG.log(Level.SEVERE, "Failed to persist scheduler state.", e);
    }
  }

  @Override
  public String getFrameworkId() {
    return frameworkId.get();
  }

  private void restore() {
    LOG.info("Attempting to recover persisted state.");

    NonVolatileSchedulerState state;
    try {
      state = persistenceLayer.fetch();
      if (state == null) {
        LOG.info("No persisted state found for restoration.");
        return;
      }
    } catch (PersistenceLayer.PersistenceException e) {
      LOG.log(Level.SEVERE, "Failed to fetch persisted state.", e);
      return;
    }

    frameworkId.set(state.getFrameworkId());
    nextTaskId.set((int) state.getNextTaskId());
    taskStore.add(state.getTasks());

    for (final Map.Entry<String, List<JobConfiguration>> entry : state.getModuleJobs().entrySet()) {
      JobManager manager = Iterables.find(jobManagers, new Predicate<JobManager>() {
        @Override public boolean apply(JobManager manager) {
          return manager.getUniqueKey().equals(entry.getKey());
        }
      });

      for (JobConfiguration job : entry.getValue()) {
        try {
          manager.receiveJob(job);
        } catch (ScheduleException e) {
          LOG.log(Level.SEVERE, "While trying to restore state, scheduler module failed.", e);
        }
      }
    }
  }
}