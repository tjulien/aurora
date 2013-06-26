package com.twitter.mesos.scheduler.base;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Strings;

import com.twitter.mesos.gen.JobConfiguration;
import com.twitter.mesos.gen.JobKey;
import com.twitter.mesos.gen.TaskQuery;

import static com.google.common.base.Preconditions.checkArgument;

import static com.twitter.mesos.gen.Constants.DEFAULT_ENVIRONMENT;

/**
 * Utility class providing convenience functions relating to JobKeys.
 */
public final class JobKeys {
  private JobKeys() {
    // Utility class.
  }

  public static final Function<JobKey, JobKey> DEEP_COPY = new Function<JobKey, JobKey>() {
    @Override public JobKey apply(JobKey jobKey) {
      return jobKey.deepCopy();
    }
  };

  /**
   * Check that a jobKey struct is valid.
   *
   * @param jobKey The jobKey to validate.
   * @return {@code true} if the jobKey validates.
   */
  public static boolean isValid(@Nullable JobKey jobKey) {
    return jobKey != null
        && !Strings.isNullOrEmpty(jobKey.getRole())
        && !Strings.isNullOrEmpty(jobKey.getEnvironment())
        && !Strings.isNullOrEmpty(jobKey.getName());
  }

  /**
   * Assert that a jobKey struct is valid.
   *
   * @param jobKey The key struct to validate.
   * @return The validated jobKey argument.
   * @throws IllegalArgumentException if the key struct fails to validate.
   */
  public static JobKey assertValid(JobKey jobKey) {
    checkArgument(isValid(jobKey));

    return jobKey;
  }

  /**
   * Attempt to create a JobKey from Nullable Thrift parameters. Uses jobKey if it's present,
   * then uses role, name, and a default environment if they're present, otherwise throws.
   *
   * @param jobKey The jobKey that appeared in the Thrift call.
   * @param role The role that appeared in the Thrift call.
   * @param name The job name that appeared in the Thrift call.
   * @return A valid JobKey if one can be synthesized.
   * @throws IllegalArgumentException if no valid JobKey could be synthesized.
   */
  public static JobKey fromRequestParameters(
      @Nullable JobKey jobKey,
      @Nullable String role,
      @Nullable String name) {

    if (isValid(jobKey)) {
      return jobKey.deepCopy();
    } else {
      return from(role, DEFAULT_ENVIRONMENT, name);
    }
  }

  /**
   * Attempt to create a valid JobKey from the given (role, environment, name) triple.
   *
   * @param role The job's role.
   * @param environment The job's environment.
   * @param name The job's name.
   * @return A valid JobKey if it can be created.
   * @throws IllegalArgumentException if the key fails to validate.
   */
  public static JobKey from(String role, String environment, String name) {
    JobKey job = new JobKey()
        .setRole(role)
        .setEnvironment(environment)
        .setName(name);

    return assertValid(job);
  }

  /**
   * Create a "/"-delimited String representation of {@code jobKey}, suitable for logging but not
   * necessarily suitable for use as a unique identifier.
   *
   * @param jobKey Key to represent.
   * @return "/"-delimited representation of the key.
   */
  public static String toPath(JobKey jobKey) {
    return jobKey.getRole() + "/" + jobKey.getEnvironment() + "/" + jobKey.getName();
  }

  /**
   * Create a "/"-delimited String representation of {@code jobKey}, suitable for logging but not
   * necessarily suitable for use as a unique identifier.
   *
   * @param job Job to represent.
   * @return "/"-delimited representation of the job's key.
   */
  public static String toPath(JobConfiguration job) {
    return toPath(job.getKey());
  }

  /**
   * Attempt to extract a {@link JobKey} from the given query if it is scoped to a single job.
   *
   * @param query Query to extract the key from.
   * @return A present if one can be extracted, absent otherwise.
   */
  public static Optional<JobKey> from(TaskQuery query) {
    if (Query.isJobScoped(query)
        && query.isSetOwner()
        && query.getOwner().isSetRole()
        && query.isSetEnvironment()
        && query.isSetJobName()) {

      return Optional.of(
          from(query.getOwner().getRole(), query.getEnvironment(), query.getJobName()));

    } else {
      return Optional.absent();
    }
  }
}