package io.github.moment.wecom.aibot;

/** Connection-scoped reply token. Obtained from a callback, not constructed by applications. */
public final class ReplyContext {
  final Object owner;
  final long generation, receivedNanos;
  final String requestId, eventType, taskId;
  volatile boolean uncertain;

  ReplyContext(Object owner, long generation, String requestId, String eventType, String taskId) {
    this.owner = owner;
    this.generation = generation;
    this.requestId = Json.required(requestId, "req_id");
    this.eventType = eventType;
    this.taskId = taskId;
    receivedNanos = System.nanoTime();
  }

  public String requestId() {
    return requestId;
  }

  public String eventType() {
    return eventType;
  }
}
