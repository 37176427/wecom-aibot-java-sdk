package io.github.moment.wecom.aibot;

/** A failure with explicit delivery certainty; UNKNOWN must not be blindly retried. */
public final class BotException extends RuntimeException {
  public enum Code {
    CLOSED,
    NOT_CONNECTED,
    STALE_CONTEXT,
    QUEUE_FULL,
    QUEUE_TIMEOUT,
    ACK_TIMEOUT,
    REMOTE_ERROR,
    TRANSPORT,
    PROTOCOL,
    AUTH_EXHAUSTED,
    RECONNECT_EXHAUSTED,
    HEARTBEAT_TIMEOUT,
    SERVER_DISCONNECTED,
    STREAM_FINISHED,
    STREAM_EXPIRED,
    UNSUPPORTED,
    DOWNLOAD,
    CRYPTO
  }

  public enum Delivery {
    NOT_SENT,
    UNKNOWN,
    REJECTED
  }

  private final Code code;
  private final Delivery delivery;
  private final String requestId;
  private final Integer remoteCode;
  private final WsFrame response;

  public BotException(Code code, Delivery delivery, String message) {
    this(code, delivery, message, null, null, null);
  }

  public BotException(
      Code code,
      Delivery delivery,
      String message,
      String requestId,
      Integer remoteCode,
      Throwable cause) {
    this(code, delivery, message, requestId, remoteCode, cause, null);
  }

  public BotException(
      Code code,
      Delivery delivery,
      String message,
      String requestId,
      Integer remoteCode,
      Throwable cause,
      WsFrame response) {
    super(message, cause);
    this.response = response;
    this.code = code;
    this.delivery = delivery;
    this.requestId = requestId;
    this.remoteCode = remoteCode;
  }

  public WsFrame response() {
    return response;
  }

  public Code code() {
    return code;
  }

  public Delivery delivery() {
    return delivery;
  }

  public String requestId() {
    return requestId;
  }

  public Integer remoteCode() {
    return remoteCode;
  }
}
