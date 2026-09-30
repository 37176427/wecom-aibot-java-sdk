package io.github.moment.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;

/** Typed access to every baseline message, with raw JSON available for extensions. */
public record IncomingMessage(ReplyContext context, WsFrame frame) {
  public enum Type {
    TEXT,
    IMAGE,
    MIXED,
    VOICE,
    FILE,
    VIDEO,
    UNKNOWN
  }

  public Type type() {
    try {
      return Type.valueOf(typeName().toUpperCase(java.util.Locale.ROOT));
    } catch (Exception e) {
      return Type.UNKNOWN;
    }
  }

  public String typeName() {
    return Json.text(frame.body(), "msgtype");
  }

  public String messageId() {
    return Json.text(frame.body(), "msgid");
  }

  public String botId() {
    return Json.text(frame.body(), "aibotid");
  }

  public String chatId() {
    return Json.text(frame.body(), "chatid");
  }

  public String chatType() {
    return Json.text(frame.body(), "chattype");
  }

  public String userId() {
    return Json.text(frame.body().path("from"), "userid");
  }

  public Long createdAt() {
    JsonNode n = frame.body().path("create_time");
    return n.isNumber() ? n.longValue() : null;
  }

  public String responseUrl() {
    return Json.text(frame.body(), "response_url");
  }

  public Content content() {
    return new Content(frame.body());
  }

  public Content quote() {
    return frame.body().has("quote") ? new Content(frame.body().path("quote")) : null;
  }

  public JsonNode body() {
    return frame.body();
  }

  public static final class Content {
    private final JsonNode raw;

    public Content(JsonNode raw) {
      this.raw = raw.deepCopy();
    }

    public String type() {
      return Json.text(raw, "msgtype");
    }

    public String text() {
      return Json.text(raw.path("voice".equals(type()) ? "voice" : "text"), "content");
    }

    public String url() {
      return Json.text(raw.path(type() == null ? "" : type()), "url");
    }

    public String aesKey() {
      return Json.text(raw.path(type() == null ? "" : type()), "aeskey");
    }

    public List<Content> items() {
      return StreamSupport.stream(raw.path("mixed").path("msg_item").spliterator(), false)
          .map(Content::new)
          .toList();
    }

    public JsonNode raw() {
      return raw.deepCopy();
    }

    @Override
    public String toString() {
      return "Content[type=" + type() + ", data=REDACTED]";
    }
  }
}
