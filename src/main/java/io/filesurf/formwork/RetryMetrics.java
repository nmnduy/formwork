package io.filesurf.formwork;

/**
 * Interface for tracking retry metrics in LlmFormwork operations. This allows the library to
 * report metrics without being coupled to specific monitoring systems.
 */
public interface RetryMetrics {

  /**
   * Called when a construction attempt starts.
   *
   * @param targetClass The class being constructed
   * @param attempt The attempt number (1-based)
   * @param maxRetries Maximum number of retries configured
   */
  void onAttemptStart(Class<?> targetClass, int attempt, int maxRetries);

  /**
   * Called when a construction attempt succeeds.
   *
   * @param targetClass The class being constructed
   * @param attempt The attempt number on which success occurred
   * @param maxRetries Maximum number of retries configured
   */
  void onAttemptSuccess(Class<?> targetClass, int attempt, int maxRetries);

  /**
   * Called when a construction attempt fails and will be retried.
   *
   * @param targetClass The class being constructed
   * @param attempt The attempt number that failed
   * @param maxRetries Maximum number of retries configured
   * @param exception The exception that caused the failure
   */
  void onAttemptRetry(Class<?> targetClass, int attempt, int maxRetries, Exception exception);

  /**
   * Called when all retry attempts are exhausted and construction fails completely.
   *
   * @param targetClass The class being constructed
   * @param totalAttempts Total number of attempts made
   * @param finalException The final exception that caused failure
   */
  void onFinalFailure(Class<?> targetClass, int totalAttempts, Exception finalException);
}
