package io.github.moment.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;

public record IncomingEvent(ReplyContext context, WsFrame frame) {
  public enum Type {
    ENTER_CHAT,
    TEMPLATE_CARD_EVENT,
    FEEDBACK_EVENT,
    DISCONNECTED_EVENT,
    UNKNOWN
  }

  public String typeName() {
    return Json.text(data(), "eventtype");
  }

  public Type type() {
    try {
      return Type.valueOf(typeName().toUpperCase(java.util.Locale.ROOT));
    } catch (Exception e) {
      return Type.UNKNOWN;
    }
  }

  public String eventKey() {
    return Json.text(details(), "event_key");
  }

  public String taskId() {
    return Json.text(details(), "task_id");
  }

  public String userId() {
    return Json.text(frame.body().path("from"), "userid");
  }

  public String corpId() {
    return Json.text(frame.body().path("from"), "corpid");
  }

  public String messageId() {
    return Json.text(frame.body(), "msgid");
  }

  public JsonNode data() {
    return frame.body().path("event");
  }
  /** Event-specific payload; data() retains the complete event envelope. */
  public JsonNode details() {
    return details(frame);
  }

  static JsonNode details(WsFrame frame) {
    JsonNode event = frame.body().path("event");
    String type = Json.text(event, "eventtype");
    JsonNode nested = type == null ? null : event.get(type);
    return nested != null && nested.isObject() ? nested : event;
  }
}
