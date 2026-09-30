package io.github.user37176427.wecom.aibot;

/** Callbacks are ordered on a separate bounded executor. Return promptly; use asynchronous APIs. */
public interface BotListener {
  default void onState(ConnectionState state) {}

  default void onMessage(IncomingMessage message) {}

  default void onEvent(IncomingEvent event) {}

  default void onUnknownFrame(WsFrame frame) {}

  default void onError(BotException error) {}

  default void onReconnecting(
      int attempt, java.time.Duration delay, boolean authenticationFailure) {}
}
