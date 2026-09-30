package io.github.user37176427.wecom.aibot;

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
