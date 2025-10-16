package io.filesurf.formwork;

import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FormWork {

  private static final Logger LOG = LoggerFactory.getLogger(FormWork.class);

  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final long DEFAULT_RETRY_DELAY_MS = 1000;

  private final SchemaManager schemaManager;
  private final JsonParser jsonParser;

  public FormWork(SchemaManager schemaManager, JsonParser jsonParser) {
    this.schemaManager = schemaManager;
    this.jsonParser = jsonParser;
  }

  public <T> T construct(
      Class<T> targetClass, String basePrompt, Function<String, String> llmCaller) {
    return construct(targetClass, basePrompt, llmCaller, DEFAULT_MAX_RETRIES);
  }

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

        if (config.retryMetrics() != null) {
          config.retryMetrics().onAttemptStart(config.targetClass(), attempt, config.maxRetries());
        }

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

        if (config.retryMetrics() != null) {
          config
              .retryMetrics()
              .onAttemptSuccess(config.targetClass(), attempt, config.maxRetries());
        }

        return result;

      } catch (IllegalArgumentException e) {
        lastException = e;
        LOG.warn(
            "Construction attempt {}/{} failed for {}: {}",
            attempt,
            config.maxRetries(),
            config.targetClass().getSimpleName(),
            e.getMessage());

        if (config.errorCallback() != null) {
          try {
            config.errorCallback().accept(e);
          } catch (Exception callbackException) {
            LOG.warn("Error callback threw exception: {}", callbackException.getMessage());
          }
        }

        if (attempt < config.maxRetries()) {
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
          LOG.info(
              "Error-correction retries exhausted for {} after {} attempts - final error: {}",
              config.targetClass().getSimpleName(),
              config.maxRetries(),
              e.getMessage());

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

    throw new FormWorkException("Unexpected end of retry loop");
  }

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

  public String buildFullPrompt(Class<?> targetClass, String basePrompt) {
    StringBuilder prompt = new StringBuilder();

    prompt.append(basePrompt).append("\n");

    prompt.append("# Output format\n");
    prompt.append("Your response MUST be a valid JSON string that matches this exact schema:\n\n");

    try {
      String schema = schemaManager.toJsonSchema(targetClass);
      prompt.append("JSON Schema for ").append(targetClass.getSimpleName()).append(":\n");
      prompt.append(schema).append("\n\n");
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to generate schema for {}",
          targetClass.getSimpleName(),
          e.getMessage());
      prompt.append("Target class: ").append(targetClass.getSimpleName()).append("\n\n");
    }

    if (hasEnumFields(targetClass)) {
      try {
        prompt.append(schemaManager.getEnumConstraintsPrompt(targetClass)).append("\n\n");
      } catch (Exception e) {
        LOG.warn("Failed to get enum constraints: {}", e.getMessage());
      }
    }

    return prompt.toString();
  }

  private boolean hasEnumFields(Class<?> clazz) {
    try {
      return java.util.Arrays.stream(clazz.getDeclaredFields())
          .anyMatch(field -> field.getType().isEnum());
    } catch (Exception e) {
      return false;
    }
  }

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
