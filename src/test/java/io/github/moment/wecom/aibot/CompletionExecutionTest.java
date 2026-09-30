package io.github.moment.wecom.aibot;

import static io.github.moment.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CompletionExecutionTest {
  @Test
  void defaultCompletionUsesPlatformThread() throws Exception {
    try (var dispatcher = new CompletionDispatcher(1, 1, null)) {
      var future = dispatcher.<String>newFuture();
      var thread = new CompletableFuture<Boolean>();
      future.thenRun(() -> thread.complete(Thread.currentThread().isVirtual()));
      dispatcher.succeed(future, "ok");
      assertFalse(thread.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void blockedContinuationKeepsCapacityReserved() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var dispatcher = new CompletionDispatcher(1, 1, null)) {
      var first = dispatcher.newFuture();
      first.thenRun(
          () -> {
            entered.countDown();
            awaitLatch(release);
          });
      dispatcher.succeed(first, null);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try {
        assertEquals(
            BotException.Code.QUEUE_FULL,
            assertThrows(BotException.class, dispatcher::newFuture).code());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void callerOwnedVirtualExecutorIsUsedAndRemainsOpen() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try (var dispatcher = new CompletionDispatcher(1, 2, executor)) {
        var future = dispatcher.newFuture();
        var virtual = new CompletableFuture<Boolean>();
        future.thenRun(() -> virtual.complete(Thread.currentThread().isVirtual()));
        dispatcher.succeed(future, null);
        assertTrue(virtual.get(5, TimeUnit.SECONDS));
      }
      assertFalse(executor.isShutdown());
      assertEquals(7, executor.submit(() -> 7).get());
    }
  }

  @Test
  void rejectingOrDirectExecutorNeverCompletesOnProtocolThread() throws Exception {
    for (Executor target :
        new Executor[] {
          Runnable::run,
          r -> {
            throw new RejectedExecutionException();
          }
        }) {
      try (var dispatcher = new CompletionDispatcher(1, 1, target)) {
        Thread protocol = Thread.currentThread();
        var future = dispatcher.newFuture();
        var thread = new CompletableFuture<Thread>();
        future.thenRun(() -> thread.complete(Thread.currentThread()));
        dispatcher.succeed(future, null);
        assertNotSame(protocol, thread.get(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void clientRejectsBeforeTransmissionWhenCompletionCapacityIsFull() throws Exception {
    var http = new LifecycleRaceTest.ControlledHttp();
    var tasks = new LinkedBlockingQueue<Runnable>();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .completionExecutor(tasks::add)
                .maxCompletionTasks(1)
                .logger(BotLogger.silent())
                .build())) {
      var ready = client.connect();
      var ws = http.sessions.getFirst();
      ws.open();
      ws.ackLast();
      Runnable authenticated = tasks.poll(5, TimeUnit.SECONDS);
      assertNotNull(authenticated);
      authenticated.run();
      ready.join();
      var first = client.sendMarkdown("chat", "one");
      ws.ackLast();
      Runnable ack = tasks.poll(5, TimeUnit.SECONDS);
      assertNotNull(ack);
      var second = client.sendMarkdown("chat", "two");
      assertEquals(BotException.Code.QUEUE_FULL, failure(second).code());
      assertEquals(2, ws.frames.size());
      ack.run();
      first.join();
      var third = client.sendMarkdown("chat", "three");
      ws.ackLast();
      Runnable thirdAck = tasks.poll(5, TimeUnit.SECONDS);
      assertNotNull(thirdAck);
      thirdAck.run();
      third.join();
    }
  }

  @Test
  void closingFromContinuationStillCompletesOtherPendingRequests() throws Exception {
    var http = new LifecycleRaceTest.ControlledHttp();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .logger(BotLogger.silent())
                .build())) {
      var ready = client.connect();
      var ws = http.sessions.getFirst();
      ws.open();
      ws.ackLast();
      ready.join();
      var first = client.sendMarkdown("chat", "one");
      var closing = first.thenRun(client::close);
      var second = client.sendMarkdown("chat", "two");
      ws.text("{\"headers\":" + ws.frames.get(1).path("headers") + ",\"errcode\":0}");
      closing.get(5, TimeUnit.SECONDS);
      assertEquals(BotException.Code.CLOSED, failure(second).code());
      assertEquals(ConnectionState.CLOSED, client.state());
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
