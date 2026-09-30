package io.github.user37176427.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class Json {
  static final ObjectMapper MAPPER =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .propertyNamingStrategy(
              com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
          .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          .build();

  private Json() {}

  static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  static ObjectNode object(Object value) {
    return MAPPER.valueToTree(value);
  }

  static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  static String limited(String value, int bytes, String name) {
    Objects.requireNonNull(value, name);
    if (value.getBytes(StandardCharsets.UTF_8).length > bytes)
      throw new IllegalArgumentException(name + " exceeds " + bytes + " UTF-8 bytes");
    return value;
  }

  static String text(JsonNode node, String field) {
    return node.path(field).asText(null);
  }
}
