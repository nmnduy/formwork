package io.filesurf.formwork;

public interface RetryMetrics {

  void onAttemptStart(Class<?> targetClass, int attempt, int maxRetries);

  void onAttemptSuccess(Class<?> targetClass, int attempt, int maxRetries);

  void onAttemptRetry(Class<?> targetClass, int attempt, int maxRetries, Exception exception);

  void onFinalFailure(Class<?> targetClass, int totalAttempts, Exception finalException);
}
