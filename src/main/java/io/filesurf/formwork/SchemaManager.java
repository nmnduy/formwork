package io.filesurf.formwork;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.factories.SchemaFactoryWrapper;

/**
 * Manages JSON schema generation for LLM formwork. Provides schema generation from Java classes
 * and enum constraint handling.
 */
public class SchemaManager {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaManager.class);
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** Generate JSON schema for a given class using Jackson schema generation. */
  public String toJsonSchema(Class<?> klazz) {
    try {
      SchemaFactoryWrapper visitor = new SchemaFactoryWrapper();
      objectMapper.acceptJsonFormatVisitor(objectMapper.constructType(klazz), visitor);
      JsonSchema schema = visitor.finalSchema();
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
    } catch (Exception e) {
      LOG.error(
          "Failed to generate schema for class {}: {}", klazz.getSimpleName(), e.getMessage());
      return "{}"; // Return empty schema on error
    }
  }

  /** Get enum constraints formatted for LLM prompts for all enum fields in a class. */
  public String getEnumConstraintsPrompt(Class<?> targetClass) {
    LOG.debug("Starting enum constraints collection for class: {}", targetClass.getSimpleName());
    StringBuilder prompt = new StringBuilder();
    prompt.append("ENUM CONSTRAINTS:\n\n");

    // Find all enum fields in the target class and its nested classes
    collectEnumFields(targetClass, prompt, "", new HashSet<>());
    LOG.debug(
        "Completed enum constraints collection, found {} characters of constraints",
        prompt.length());

    if (prompt.length() > "ENUM CONSTRAINTS:\n\n".length()) {
      prompt.append(
          "\nAlways use the exact values as specified above. Do not use variations, different cases, or custom values.");
    }

    return prompt.toString();
  }

  /** Recursively collect enum fields from a class and its nested classes. */
  private void collectEnumFields(
      Class<?> clazz, StringBuilder prompt, String prefix, Set<Class<?>> visited) {
    // Avoid infinite recursion from circular references
    if (visited.contains(clazz)) {
      LOG.debug(
          "Skipping already visited class to avoid circular reference: {}", clazz.getSimpleName());
      return;
    }
    LOG.debug("Processing class for enum fields: {} (prefix: {})", clazz.getSimpleName(), prefix);
    visited.add(clazz);

    try {
      Field[] fields = clazz.getDeclaredFields();
      for (Field field : fields) {
        Class<?> fieldType = field.getType();
        String fieldName = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();

        if (fieldType.isEnum()) {
          Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) fieldType;
          String enumValues = getEnumValues(enumClass);
          LOG.debug("Found enum field: {} of type {}", fieldName, enumClass.getSimpleName());
          prompt
              .append("  - ")
              .append(fieldName)
              .append(" (")
              .append(enumClass.getSimpleName())
              .append("): ")
              .append(enumValues)
              .append("\n");
        } else if (isModelClass(fieldType)) {
          // Recursively check nested model classes
          collectEnumFields(fieldType, prompt, fieldName, visited);
        }
      }
    } catch (Exception e) {
      LOG.error(
          "Could not collect enum fields from class {}: {}", clazz.getSimpleName(), e.getMessage());
    }
  }

  /** Check if a class is likely a model class (not primitive, not from java.* packages, etc.) */
  private boolean isModelClass(Class<?> clazz) {
    return !clazz.isPrimitive()
        && !clazz.getName().startsWith("java.")
        && !clazz.getName().startsWith("javax.")
        && !clazz.getName().startsWith("jakarta.")
        && !clazz.isEnum()
        && !clazz.equals(String.class);
  }

  /** Get string representation of all enum values. */
  private String getEnumValues(Class<? extends Enum<?>> enumClass) {
    return Arrays.stream(enumClass.getEnumConstants())
        .map(Enum::name)
        .collect(Collectors.joining(" | "));
  }

  /** Validate that a given enum value is valid for the specified enum type. */
  public boolean isValidEnumValue(Class<? extends Enum<?>> enumClass, String value) {
    if (value == null) return false;

    try {
      Enum.valueOf((Class<? extends Enum>) enumClass, value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Get all valid enum values for a given enum class. */
  public List<String> getValidEnumValues(Class<? extends Enum<?>> enumClass) {
    return Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).collect(Collectors.toList());
  }
}
