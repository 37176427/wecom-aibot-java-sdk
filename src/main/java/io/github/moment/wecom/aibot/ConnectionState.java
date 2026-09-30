package io.github.moment.wecom.aibot;

public enum ConnectionState {
  NEW,
  CONNECTING,
  AUTHENTICATING,
  READY,
  RECONNECTING,
  DISCONNECTED,
  FAILED,
  CLOSED
}
