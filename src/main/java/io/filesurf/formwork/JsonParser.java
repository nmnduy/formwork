package io.filesurf.formwork;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class JsonParser {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  static {
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public JsonNode extractJsonFromLlmOutput(String text) {
    String working = text.strip();

    if (working.startsWith("```json")) {
      working = working.substring(7);
    } else if (working.startsWith("```")) {
      working = working.substring(3);
    }

    if (working.endsWith("```")) {
      working = working.substring(0, working.length() - 3);
    }

    working = working.strip();

    int start = -1;
    char openChar = 0, closeChar = 0;

    for (int i = 0; i < working.length(); i++) {
      char ch = working.charAt(i);
      if (ch == '{') {
        start = i;
        openChar = '{';
        closeChar = '}';
        break;
      } else if (ch == '[') {
        start = i;
        openChar = '[';
        closeChar = ']';
        break;
      }
    }

    if (start == -1) {
      throw new IllegalArgumentException("No JSON object or array found in input");
    }

    int depth = 0;
    int end = -1;

    for (int i = start; i < working.length(); i++) {
      char ch = working.charAt(i);

      if (ch == openChar) {
        depth++;
      } else if (ch == closeChar) {
        depth--;
        if (depth == 0) {
          end = i + 1;
          break;
        }
      }
    }

    if (end == -1) {
      throw new IllegalArgumentException("Unmatched brackets in JSON");
    }

    String jsonStr = working.substring(start, end);

    try {
      return objectMapper.readTree(jsonStr);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Error parsing JSON: " + e.getMessage(), e);
    }
  }

  public <T> T convertMapToObject(Map<String, Object> map, Class<T> targetClass) {
    try {
      return objectMapper.convertValue(map, targetClass);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Error converting Map to target class: " + e.getMessage(), e);
    }
  }

  public <T> T convertObject(Object obj, Class<T> targetClass) {
    try {
      return objectMapper.convertValue(obj, targetClass);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Error converting object to target class: " + e.getMessage(), e);
    }
  }
}
