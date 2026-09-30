package io.github.user37176427.wecom.aibot;
/** Successful server acknowledgement, retaining any response body and unknown fields. */
public record Ack(WsFrame frame) {
  public String requestId() {
    return frame.requestId();
  }

  public com.fasterxml.jackson.databind.JsonNode body() {
    return frame.body();
  }
}
