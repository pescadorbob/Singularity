package com.hubspot.singularity.executor.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.hubspot.singularity.runner.base.config.SingularityRunnerBaseConfigurationLoader;

public class SingularityExecutorConfiguration {

  private final String executorJavaLog;
  private final String executorBashLog;
  private final String serviceLog;
  private final String defaultRunAsUser;
  private final String cacheDirectory;
  private final String taskAppDirectory;
  private final long shutdownTimeoutWaitMillis;
  private final long idleExecutorShutdownWaitMillis;
  private final long stopDriverAfterMillis;
  
  private final long hardKillAfterMillis;
  private final int killThreads;

  private final int maxTaskMessageLength;
  
  private final Path logMetadataDirectory;
  private final String logMetadataSuffix;
  
  @Inject
  public SingularityExecutorConfiguration(@Named(SingularityExecutorConfigurationLoader.ARTIFACT_CACHE_DIRECTORY) String cacheDirectory, 
      @Named(SingularityExecutorConfigurationLoader.TASK_APP_DIRECTORY) String taskAppDirectory,
      @Named(SingularityExecutorConfigurationLoader.TASK_EXECUTOR_BASH_LOG_PATH) String executorBashLog, 
      @Named(SingularityExecutorConfigurationLoader.TASK_EXECUTOR_JAVA_LOG_PATH) String executorJavaLog, 
      @Named(SingularityExecutorConfigurationLoader.TASK_SERVICE_LOG_PATH) String serviceLog, 
      @Named(SingularityExecutorConfigurationLoader.DEFAULT_USER) String defaultRunAsUser, 
      @Named(SingularityExecutorConfigurationLoader.SHUTDOWN_STOP_DRIVER_AFTER_MILLIS) String stopDriverAfterMillis, 
      @Named(SingularityExecutorConfigurationLoader.SHUTDOWN_TIMEOUT_MILLIS) String shutdownTimeoutWaitMillis, 
      @Named(SingularityExecutorConfigurationLoader.IDLE_EXECUTOR_SHUTDOWN_AFTER_MILLIS) String idleExecutorShutdownWaitMillis, 
      @Named(SingularityExecutorConfigurationLoader.HARD_KILL_AFTER_MILLIS) String hardKillAfterMillis,
      @Named(SingularityExecutorConfigurationLoader.NUM_CORE_KILL_THREADS) String killThreads, 
      @Named(SingularityExecutorConfigurationLoader.MAX_TASK_MESSAGE_LENGTH) String maxTaskMessageLength,
      @Named(SingularityExecutorConfigurationLoader.LOG_METADATA_DIRECTORY) String logMetadataDirectory,
      @Named(SingularityExecutorConfigurationLoader.LOG_METADATA_SUFFIX) String logMetadataSuffix
      ) {
    this.executorBashLog = executorBashLog;
    this.taskAppDirectory = taskAppDirectory;
    this.executorJavaLog = executorJavaLog;
    this.cacheDirectory = cacheDirectory;
    this.serviceLog = serviceLog;
    this.defaultRunAsUser = defaultRunAsUser;
    this.shutdownTimeoutWaitMillis = Long.parseLong(shutdownTimeoutWaitMillis);
    this.idleExecutorShutdownWaitMillis = Long.parseLong(idleExecutorShutdownWaitMillis);
    this.stopDriverAfterMillis = Long.parseLong(stopDriverAfterMillis);
    this.hardKillAfterMillis = Long.parseLong(hardKillAfterMillis);
    this.killThreads = Integer.parseInt(killThreads);
    this.maxTaskMessageLength = Integer.parseInt(maxTaskMessageLength); 
    this.logMetadataDirectory = SingularityRunnerBaseConfigurationLoader.getValidDirectory(logMetadataDirectory, SingularityExecutorConfigurationLoader.LOG_METADATA_DIRECTORY);
    this.logMetadataSuffix = logMetadataSuffix;
  }
  
  public Path getLogMetadataDirectory() {
    return logMetadataDirectory;
  }

  public String getLogMetadataSuffix() {
    return logMetadataSuffix;
  }

  public long getHardKillAfterMillis() {
    return hardKillAfterMillis;
  }

  public int getKillThreads() {
    return killThreads;
  }

  public int getMaxTaskMessageLength() {
    return maxTaskMessageLength;
  }

  public long getStopDriverAfterMillis() {
    return stopDriverAfterMillis;
  }

  public long getIdleExecutorShutdownWaitMillis() {
    return idleExecutorShutdownWaitMillis;
  }

  public long getShutdownTimeoutWaitMillis() {
    return shutdownTimeoutWaitMillis;
  }

  public String getExecutorJavaLog() {
    return executorJavaLog;
  }

  public String getExecutorBashLog() {
    return executorBashLog;
  }

  public String getServiceLog() {
    return serviceLog;
  }

  public String getDefaultRunAsUser() {
    return defaultRunAsUser;
  }
  
  public String getTaskAppDirectory() {
    return taskAppDirectory;
  }

  public String getCacheDirectory() {
    return cacheDirectory;
  }
  
  public Path getTaskDirectoryPath(String taskId) {
    return Paths.get(getSafeTaskIdForDirectory(taskId));
  }
  
  private String getSafeTaskIdForDirectory(String taskId) {
    return taskId.replace(":", "_");
  }
  
  public Path getTaskAppDirectoryPath(String taskId) {
    return getTaskDirectoryPath(taskId).resolve(taskAppDirectory);
  }
  
  public Path getExecutorBashLogPath(String taskId) { 
    return getTaskDirectoryPath(taskId).resolve(getExecutorBashLog());
  }
  
  public Path getExecutorJavaLogPath(String taskId) { 
    return getTaskDirectoryPath(taskId).resolve(getExecutorJavaLog());
  }

  @Override
  public String toString() {
    return "SingularityExecutorConfiguration [executorJavaLog=" + executorJavaLog + ", executorBashLog=" + executorBashLog + ", serviceLog=" + serviceLog + ", defaultRunAsUser=" + defaultRunAsUser + ", cacheDirectory=" + cacheDirectory
        + ", taskAppDirectory=" + taskAppDirectory + ", shutdownTimeoutWaitMillis=" + shutdownTimeoutWaitMillis + ", idleExecutorShutdownWaitMillis=" + idleExecutorShutdownWaitMillis + ", stopDriverAfterMillis=" + stopDriverAfterMillis
        + ", hardKillAfterMillis=" + hardKillAfterMillis + ", killThreads=" + killThreads + ", maxTaskMessageLength=" + maxTaskMessageLength + ", logMetadataDirectory=" + logMetadataDirectory + ", logMetadataSuffix=" + logMetadataSuffix
        + "]";
  }

}
