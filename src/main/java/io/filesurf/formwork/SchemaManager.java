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

public class SchemaManager {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaManager.class);
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public String getEnumConstraintsPrompt(Class<?> targetClass) {
    LOG.debug("Starting enum constraints collection for class: {}", targetClass.getSimpleName());
    StringBuilder prompt = new StringBuilder();
    prompt.append("ENUM CONSTRAINTS:\n\n");

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

  private boolean isModelClass(Class<?> clazz) {
    return !clazz.isPrimitive()
        && !clazz.getName().startsWith("java.")
        && !clazz.getName().startsWith("javax.")
        && !clazz.getName().startsWith("jakarta.")
        && !clazz.isEnum()
        && !clazz.equals(String.class);
  }

  public boolean isValidEnumValue(Class<? extends Enum<?>> enumClass, String value) {
    if (value == null) return false;

    try {
      Enum.valueOf((Class<? extends Enum>) enumClass, value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Generate JSON Schema for target class.
   */
  public String toJsonSchema(Class<?> targetClass) {
    try {
      SchemaFactoryWrapper visitor = new SchemaFactoryWrapper();
      objectMapper.acceptJsonFormatVisitor(objectMapper.constructType(targetClass), visitor);
      JsonSchema schema = visitor.finalSchema();
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
    } catch (Exception e) {
      LOG.error("Failed to generate JSON schema for {}: {}", targetClass.getSimpleName(), e.getMessage());
      throw new RuntimeException("Failed to generate JSON schema for " + targetClass.getSimpleName(), e);
    }
  }

  /**
   * Recursively collect enum field values from model classes.
   */
  private void collectEnumFields(Class<?> clazz, StringBuilder prompt, String path, Set<Class<?>> visited) {
    if (!visited.add(clazz)) {
      return;
    }
    for (Field field : clazz.getDeclaredFields()) {
      Class<?> fieldType = field.getType();
      if (fieldType.isEnum()) {
        prompt.append(path).append(field.getName()).append(": ");
        String enums = Arrays.stream(fieldType.getEnumConstants())
            .map(Object::toString)
            .collect(Collectors.joining(", "));
        prompt.append(enums).append("\n");
      } else if (isModelClass(fieldType)) {
        collectEnumFields(fieldType, prompt, path + field.getName() + ".", visited);
      }
    }
  }
}
