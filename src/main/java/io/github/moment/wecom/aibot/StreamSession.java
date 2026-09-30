package io.github.moment.wecom.aibot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Per-message stream state. Any failed admitted send terminates this session conservatively. */
public final class StreamSession {
  private final WecomAiBotClient client;
  private final ReplyContext context;
  private final String id;
  private static final long WINDOW_NANOS = java.time.Duration.ofMinutes(10).toNanos();
  private final java.util.function.LongSupplier nanoTime;
  private boolean started, finished;
  private long startedAt;

  StreamSession(WecomAiBotClient client, ReplyContext context, String id) {
    this(client, context, id, System::nanoTime);
  }

  StreamSession(
      WecomAiBotClient client,
      ReplyContext context,
      String id,
      java.util.function.LongSupplier nanoTime) {
    this.client = client;
    this.context = context;
    this.id = id;
    this.nanoTime = nanoTime;
  }

  public String id() {
    return id;
  }

  public synchronized CompletableFuture<Ack> update(String content) {
    return send(content, false, List.of(), null, null);
  }

  public synchronized CompletableFuture<Ack> finish(String content) {
    return send(content, true, List.of(), null, null);
  }

  /** Sends supported stream text/Markdown with optional first-frame feedback. */
  public synchronized CompletableFuture<Ack> send(
      String content, boolean finish, String feedbackId) {
    return send(content, finish, List.of(), feedbackId, null);
  }

  /**
   * Compatibility signature; nonempty images or a card fail with UNSUPPORTED without transmission.
   */
  public synchronized CompletableFuture<Ack> send(
      String content,
      boolean finish,
      List<StreamContent.InlineImage> images,
      String feedbackId,
      TemplateCard card) {
    if (finished)
      return CompletableFuture.failedFuture(
          new BotException(
              BotException.Code.STREAM_FINISHED,
              BotException.Delivery.NOT_SENT,
              "Stream finished or failed"));
    if (started && feedbackId != null)
      throw new IllegalArgumentException("Feedback is allowed only in the first stream frame");
    if (card != null || (images != null && !images.isEmpty()))
      return CompletableFuture.failedFuture(WecomAiBotClient.unsupportedStreamFeatures());
    if (started && nanoTime.getAsLong() - startedAt >= WINDOW_NANOS) {
      finished = true;
      return CompletableFuture.failedFuture(
          new BotException(
              BotException.Code.STREAM_EXPIRED,
              BotException.Delivery.NOT_SENT,
              "Stream exceeded the 10 minute protocol window"));
    }
    StreamContent body = new StreamContent(id, content, finish, images, feedbackId);
    long firstSubmission = started ? startedAt : nanoTime.getAsLong();
    CompletableFuture<Ack> future =
        client.replyStreamBefore(context, body, firstSubmission + WINDOW_NANOS);
    // Admission backpressure must not consume a stream's first feedback or final update.
    if (future.isCompletedExceptionally()
        && future.exceptionNow() instanceof BotException error
        && error.code() == BotException.Code.QUEUE_FULL
        && error.delivery() == BotException.Delivery.NOT_SENT) return future.copy();
    if (!started) startedAt = firstSubmission;
    started = true;
    finished = finish;
    return future
        .whenComplete(
            (ack, error) -> {
              if (error != null)
                synchronized (this) {
                  finished = true;
                }
            })
        .copy();
  }

  public synchronized CompletableFuture<Optional<Ack>> tryUpdate(String content) {
    if (finished)
      return CompletableFuture.failedFuture(
          new BotException(
              BotException.Code.STREAM_FINISHED,
              BotException.Delivery.NOT_SENT,
              "Stream finished or failed"));
    if (started && nanoTime.getAsLong() - startedAt >= WINDOW_NANOS)
      return update(content).thenApply(Optional::of);
    if (client.hasPendingReplyAck(context))
      return CompletableFuture.completedFuture(Optional.empty());
    return update(content).thenApply(Optional::of);
  }
}
