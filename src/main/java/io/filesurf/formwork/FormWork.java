package io.filesurf.formwork;

import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FormWork provides a utility function for LLM-based object construction. It calls an LLM
 * with a prompt that includes JSON schema information and parses the response into instances of
 * Java data classes.
 *
 * <p>This utility combines schema generation, LLM invocation, and JSON parsing with retry logic for
 * robust object construction from LLM responses.
 */
public class FormWork {

  private static final Logger LOG = LoggerFactory.getLogger(FormWork.class);

  // Default retry configuration
  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final long DEFAULT_RETRY_DELAY_MS = 1000;

  private final SchemaManager schemaManager;
  private final JsonParser jsonParser;

  /** Default constructor - creates instances of SchemaManager and JsonParser. */
  public FormWork() {
    this.schemaManager = new SchemaManager();
    this.jsonParser = new JsonParser();
  }

  /** Constructor with injected dependencies for testing or custom configurations. */
  public FormWork(SchemaManager schemaManager, JsonParser jsonParser) {
    this.schemaManager = schemaManager;
    this.jsonParser = jsonParser;
  }

  /**
   * Constructs an instance of the specified class using an LLM.
   *
   * @param <T> The target type to construct
   * @param targetClass The class of the object to construct
   * @param basePrompt The base prompt to send to the LLM
   * @param llmCaller A function that calls the LLM and returns a string response
   * @return An instance of the target class constructed from LLM output
   * @throws LlmFormworkException if construction fails after all retries
   */
  public <T> T construct(
      Class<T> targetClass, String basePrompt, Function<String, String> llmCaller) {
    return construct(targetClass, basePrompt, llmCaller, DEFAULT_MAX_RETRIES);
  }

  /**
   * Constructs an instance of the specified class using an LLM with custom retry count.
   *
   * @param <T> The target type to construct
   * @param targetClass The class of the object to construct
   * @param basePrompt The base prompt to send to the LLM
   * @param llmCaller A function that calls the LLM and returns a string response
   * @param maxRetries Maximum number of retry attempts
   * @return An instance of the target class constructed from LLM output
   * @throws FormWorkException if construction fails after all retries
   */
  public <T> T construct(
      Class<T> targetClass, String basePrompt, Function<String, String> llmCaller, int maxRetries) {
    return constructWithConfig(
        ConstructionConfig.<T>builder()
            .targetClass(targetClass)
            .basePrompt(basePrompt)
            .llmCaller(llmCaller)
            .maxRetries(maxRetries)
            .retryDelayMs(DEFAULT_RETRY_DELAY_MS)
            .build());
  }

  /**
   * Constructs an instance using detailed configuration.
   *
   * @param <T> The target type to construct
   * @param config Construction configuration
   * @return An instance of the target class constructed from LLM output
   * @throws FormWorkException if construction fails after all retries
   */
  public <T> T constructWithConfig(ConstructionConfig<T> config) {
    String fullPrompt = buildFullPrompt(config.targetClass(), config.basePrompt());

    Exception lastException = null;
    String lastLlmResponse = null;

    for (int attempt = 1; attempt <= config.maxRetries(); attempt++) {
      try {
        LOG.debug(
            "Attempting to construct {} (attempt {}/{})",
            config.targetClass().getSimpleName(),
            attempt,
            config.maxRetries());

        // Report attempt start to metrics
        if (config.retryMetrics() != null) {
          config.retryMetrics().onAttemptStart(config.targetClass(), attempt, config.maxRetries());
        }

        // Use error-enhanced prompt for retry attempts
        String promptToUse;
        if (attempt == 1) {
          promptToUse = fullPrompt;
        } else {
          LOG.info(
              "Retrying {} construction with error-correction prompt (attempt {}/{}): {}",
              config.targetClass().getSimpleName(),
              attempt,
              config.maxRetries(),
              lastException.getMessage());
          promptToUse =
              buildRetryPrompt(
                  config.targetClass(), config.basePrompt(), lastException, lastLlmResponse);
        }

        String llmResponse = config.llmCaller().apply(promptToUse);

        if (llmResponse == null || llmResponse.trim().isEmpty()) {
          throw new IllegalArgumentException("LLM returned empty response");
        }

        lastLlmResponse = llmResponse;

        T result = jsonParser.extractJsonFromLlmOutput(llmResponse, config.targetClass());

        if (attempt == 1) {
          LOG.debug(
              "Successfully constructed {} on attempt {}",
              config.targetClass().getSimpleName(),
              attempt);
        } else {
          LOG.info(
              "Error-correction retry succeeded for {} on attempt {}",
              config.targetClass().getSimpleName(),
              attempt);
        }

        // Report success to metrics
        if (config.retryMetrics() != null) {
          config
              .retryMetrics()
              .onAttemptSuccess(config.targetClass(), attempt, config.maxRetries());
        }

        return result;

      } catch (IllegalArgumentException e) {
        // Only retry on JSON parsing failures (empty response, malformed JSON,
        // conversion errors)
        lastException = e;
        LOG.warn(
            "Construction attempt {}/{} failed for {}: {}",
            attempt,
            config.maxRetries(),
            config.targetClass().getSimpleName(),
            e.getMessage());

        // Call error callback if provided
        if (config.errorCallback() != null) {
          try {
            config.errorCallback().accept(e);
          } catch (Exception callbackException) {
            LOG.warn("Error callback threw exception: {}", callbackException.getMessage());
          }
        }

        if (attempt < config.maxRetries()) {
          // Report retry to metrics
          if (config.retryMetrics() != null) {
            config
                .retryMetrics()
                .onAttemptRetry(config.targetClass(), attempt, config.maxRetries(), e);
          }

          try {
            Thread.sleep(config.retryDelayMs());
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new FormWorkException("Construction interrupted during retry delay", ie);
          }
        } else {
          // Last attempt failed, throw the parsing exception
          LOG.info(
              "Error-correction retries exhausted for {} after {} attempts - final error: {}",
              config.targetClass().getSimpleName(),
              config.maxRetries(),
              e.getMessage());

          // Report final failure to metrics
          if (config.retryMetrics() != null) {
            config.retryMetrics().onFinalFailure(config.targetClass(), config.maxRetries(), e);
          }

          throw new FormWorkException(
              String.format(
                  "Failed to construct %s after %d attempts due to JSON parsing errors",
                  config.targetClass().getSimpleName(), config.maxRetries()),
              e);
        }
      } catch (Exception e) {
        // For non-parsing exceptions (LLM service failures, network issues, etc.),
        // don't retry and fail immediately
        LOG.error(
            "Non-retryable error during construction of {}: {}",
            config.targetClass().getSimpleName(),
            e.getMessage());
              throw new FormWorkException(
            String.format(
                "Failed to construct %s due to non-retryable error",
                config.targetClass().getSimpleName()),
            e);
      }
    }

    // This should never be reached due to exception handling above
    throw new FormWorkException("Unexpected end of retry loop");
  }

  /**
   * Builds a retry prompt that includes error context from the previous attempt.
   *
   * @param targetClass The class to generate schema for
   * @param basePrompt The user's original base prompt
   * @param lastException The exception from the previous attempt
   * @param lastLlmResponse The LLM response that caused the error
   * @return Enhanced prompt with error context and correction instructions
   */
  private String buildRetryPrompt(
      Class<?> targetClass, String basePrompt, Exception lastException, String lastLlmResponse) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("<original_request>\n");
    prompt.append(basePrompt);
    prompt.append("\n</original_request>\n\n");

    prompt.append("<error>\n");
    prompt.append("Your previous response failed with this error:\n");
    prompt.append(lastException.getMessage());
    prompt.append("\n</error>\n\n");

    if (lastLlmResponse != null && !lastLlmResponse.trim().isEmpty()) {
      prompt.append("<previous_response>\n");
      prompt.append(lastLlmResponse);
      prompt.append("\n</previous_response>\n\n");
    }

    prompt.append("<instructions>\n");
    prompt
        .append(
            "CRITICAL: Carefully review the desired output format in the <original_request>. Fix the specific error mentioned above. Return ONLY valid JSON that can be parsed into a ")
        .append(targetClass.getSimpleName())
        .append(" object. Do not include explanations, markdown formatting, or additional text.");
    prompt.append("\n</instructions>");

    return prompt.toString();
  }

  /**
   * Builds the full prompt including schema information for the target class. This is useful for
   * both internal construction and external use with streaming or custom processing.
   *
   * @param targetClass The class to generate schema for
   * @param basePrompt The user's base prompt
   * @return Complete prompt with schema information
   */
  public String buildFullPrompt(Class<?> targetClass, String basePrompt) {
    StringBuilder prompt = new StringBuilder();

    prompt.append(basePrompt).append("\n\n");

    prompt.append("=== OUTPUT FORMAT ===\n\n");
    prompt.append("You MUST respond with valid JSON that matches this exact schema:\n\n");

    try {
      String schema = schemaManager.toJsonSchema(targetClass);
      prompt.append("JSON Schema for ").append(targetClass.getSimpleName()).append(":\n");
      prompt.append(schema).append("\n\n");
    } catch (Exception e) {
      LOG.warn(
          "Failed to generate schema for {}, using class name only: {}",
          targetClass.getSimpleName(),
          e.getMessage());
      prompt.append("Target class: ").append(targetClass.getSimpleName()).append("\n\n");
    }

    // Add enum constraints if applicable
    if (hasEnumFields(targetClass)) {
      try {
        prompt.append(schemaManager.getEnumConstraintsPrompt(targetClass)).append("\n\n");
      } catch (Exception e) {
        LOG.warn("Failed to get enum constraints: {}", e.getMessage());
      }
    }

    prompt
        .append("IMPORTANT: Return ONLY valid JSON that can be parsed into a ")
        .append(targetClass.getSimpleName())
        .append(" object. Do not include explanations, markdown formatting, or additional text.\n");

    return prompt.toString();
  }

  /**
   * Checks if a class likely has enum fields (basic heuristic). This is used to decide whether to
   * include enum constraints in the prompt.
   */
  private boolean hasEnumFields(Class<?> clazz) {
    try {
      return java.util.Arrays.stream(clazz.getDeclaredFields())
          .anyMatch(field -> field.getType().isEnum());
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Configuration record for LLM construction parameters. Uses Java 17 records for immutable
   * configuration.
   */
  public record ConstructionConfig<T>(
      Class<T> targetClass,
      String basePrompt,
      Function<String, String> llmCaller,
      int maxRetries,
      long retryDelayMs,
      Consumer<Exception> errorCallback,
      RetryMetrics retryMetrics) {

    public static <T> Builder<T> builder() {
      return new Builder<>();
    }

    public static class Builder<T> {
      private Class<T> targetClass;
      private String basePrompt;
      private Function<String, String> llmCaller;
      private int maxRetries = DEFAULT_MAX_RETRIES;
      private long retryDelayMs = DEFAULT_RETRY_DELAY_MS;
      private Consumer<Exception> errorCallback;
      private RetryMetrics retryMetrics;

      public Builder<T> targetClass(Class<T> targetClass) {
        this.targetClass = targetClass;
        return this;
      }

      public Builder<T> basePrompt(String basePrompt) {
        this.basePrompt = basePrompt;
        return this;
      }

      public Builder<T> llmCaller(Function<String, String> llmCaller) {
        this.llmCaller = llmCaller;
        return this;
      }

      public Builder<T> maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
      }

      public Builder<T> retryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
        return this;
      }

      public Builder<T> errorCallback(Consumer<Exception> errorCallback) {
        this.errorCallback = errorCallback;
        return this;
      }

      public Builder<T> retryMetrics(RetryMetrics retryMetrics) {
        this.retryMetrics = retryMetrics;
        return this;
      }

      public ConstructionConfig<T> build() {
        if (targetClass == null) {
          throw new IllegalStateException("targetClass is required");
        }
        if (basePrompt == null) {
          throw new IllegalStateException("basePrompt is required");
        }
        if (llmCaller == null) {
          throw new IllegalStateException("llmCaller is required");
        }

        return new ConstructionConfig<>(
            targetClass,
            basePrompt,
            llmCaller,
            maxRetries,
            retryDelayMs,
            errorCallback,
            retryMetrics);
      }
    }
  }
}
