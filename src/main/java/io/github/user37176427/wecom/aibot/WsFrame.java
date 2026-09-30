package io.github.user37176427.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;

/** Immutable frame snapshot, including unknown envelope, header and body fields. */
public final class WsFrame {
  private final JsonNode raw;

  public WsFrame(JsonNode raw) {
    if (raw == null || !raw.isObject())
      throw new IllegalArgumentException("Frame must be an object");
    this.raw = raw.deepCopy();
  }

  public String command() {
    return Json.text(raw, "cmd");
  }

  public String requestId() {
    return Json.text(raw.path("headers"), "req_id");
  }

  public Integer errorCode() {
    JsonNode code = raw.path("errcode");
    return code.isIntegralNumber() && code.canConvertToInt() ? code.intValue() : null;
  }

  public String errorMessage() {
    return Json.text(raw, "errmsg");
  }

  public JsonNode body() {
    return raw.path("body").deepCopy();
  }

  public JsonNode headers() {
    return raw.path("headers").deepCopy();
  }

  public JsonNode raw() {
    return raw.deepCopy();
  }

  @Override
  public String toString() {
    return "WsFrame[command=" + command() + ", body=REDACTED]";
  }
}
