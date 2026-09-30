package io.github.user37176427.wecom.aibot;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public record MediaMessage(MediaType type, String mediaId, String title, String description) {
  public MediaMessage {
    Objects.requireNonNull(type);
    Json.required(mediaId, "mediaId");
    if (type != MediaType.VIDEO && (title != null || description != null))
      throw new IllegalArgumentException("Only video supports title/description");
    if (title != null) Json.limited(title, 128, "title");
    if (description != null) Json.limited(description, 512, "description");
  }

  public MediaMessage(MediaType type, String mediaId) {
    this(type, mediaId, null, null);
  }

  public ObjectNode toJson() {
    ObjectNode item = Json.object().put("media_id", mediaId);
    if (title != null) item.put("title", title);
    if (description != null) item.put("description", description);
    ObjectNode body = Json.object().put("msgtype", type.wireName());
    body.set(type.wireName(), item);
    return body;
  }
}
