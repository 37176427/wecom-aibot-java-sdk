package io.github.moment.wecom.aibot;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reserves completion capacity before network admission, including synchronous user continuations.
 */
final class CompletionDispatcher implements AutoCloseable {
  private final Semaphore slots;
  private final ThreadPoolExecutor owned;
  private final Executor target;

  CompletionDispatcher(int threads, int capacity, Executor target) {
    slots = new Semaphore(capacity);
    owned =
        new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(capacity),
            Thread.ofPlatform()
                .daemon()
                .inheritInheritableThreadLocals(false)
                .name("wecom-completion-", 0)
                .factory());
    owned.allowCoreThreadTimeOut(true);
    this.target = target;
  }

  <T> CompletableFuture<T> newFuture() {
    if (!slots.tryAcquire())
      throw new BotException(
          BotException.Code.QUEUE_FULL,
          BotException.Delivery.NOT_SENT,
          "Completion task capacity reached");
    return new ReservedFuture<>();
  }

  <T> void succeed(CompletableFuture<T> future, T value) {
    finish(future, () -> future.complete(value));
  }

  void fail(CompletableFuture<?> future, Throwable error) {
    finish(future, () -> future.completeExceptionally(error));
  }

  private void finish(CompletableFuture<?> future, Runnable completion) {
    if (future == null) return;
    var reserved = (ReservedFuture<?>) future;
    if (!reserved.dispatched.compareAndSet(false, true)) return;
    Runnable task =
        () -> {
          if (!reserved.started.compareAndSet(false, true)) return;
          try {
            completion.run();
          } finally {
            slots.release();
          }
        };
    // The owned dispatcher also shields the protocol lock from direct/caller-runs executors.
    owned.execute(
        () -> {
          if (target == null) task.run();
          else {
            try {
              target.execute(task);
            } catch (RuntimeException rejected) {
              task.run();
            }
          }
        });
  }

  @Override
  public void close() {
    owned.shutdown();
  }

  private static final class ReservedFuture<T> extends CompletableFuture<T> {
    private final AtomicBoolean dispatched = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
  }
}
