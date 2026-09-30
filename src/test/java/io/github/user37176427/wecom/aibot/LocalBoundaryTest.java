package io.github.user37176427.wecom.aibot;

import static io.github.user37176427.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LocalBoundaryTest {
  @Test
  void largeConfiguredQueueDrainsWithImmediateSendCompletions() throws Exception {
    var http = new LifecycleRaceTest.ControlledHttp();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .logger(BotLogger.silent())
                .maxOutstanding(7000)
                .maxCompletionTasks(7000)
                .ackTimeout(Duration.ofSeconds(30))
                .queueTimeout(Duration.ofSeconds(30))
                .build())) {
      var ready = client.connect();
      var ws = http.sessions.getFirst();
      ws.open();
      ws.ackLast();
      ready.get(5, TimeUnit.SECONDS);
      var gate = new CompletableFuture<WebSocket>();
      ws.nextWrite = gate;
      List<CompletableFuture<Ack>> requests = new ArrayList<>();
      for (int i = 0; i < 6000; i++) requests.add(client.sendMarkdown("test", "message-" + i));
      gate.complete(ws);
      await(() -> ws.frames.size() == 6001);
      for (var frame : ws.frames.subList(1, ws.frames.size()))
        ws.text("{\"headers\":" + frame.path("headers") + ",\"errcode\":0}");
      CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void unrepresentableQueueDurationIsRejectedBeforeClientCreation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder("id", "secret").queueTimeout(Duration.ofDays(200_000)).build());
  }

  @Test
  void admissionRejectionDoesNotConsumeStreamFeedbackOrFinalState() throws Exception {
    var http = new LifecycleRaceTest.ControlledHttp();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .maxOutstanding(1)
                .logger(BotLogger.silent())
                .build())) {
      var ready = client.connect();
      var ws = http.sessions.getFirst();
      ws.open();
      ws.ackLast();
      ready.get(5, TimeUnit.SECONDS);
      var events = new Events();
      client.addListener(events);
      ws.text(
          "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"stream\"},\"body\":{\"msgtype\":\"text\"}}");
      var stream = client.stream(events.message().context());
      var busy = client.sendMarkdown("test", "busy");
      assertEquals(
          BotException.Code.QUEUE_FULL, failure(stream.send("final", true, "feedback")).code());
      ws.ackLast();
      busy.get(5, TimeUnit.SECONDS);
      var retry = stream.send("final", true, "feedback");
      assertFalse(retry.isCompletedExceptionally());
      assertEquals("feedback", ws.frames.getLast().at("/body/stream/feedback/id").asText());
      ws.ackLast();
      retry.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void representableLargeQueueTimeoutDoesNotExpireImmediately() throws Exception {
    var http = new LifecycleRaceTest.ControlledHttp();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .queueTimeout(Duration.ofNanos(Long.MAX_VALUE))
                .logger(BotLogger.silent())
                .build())) {
      var ready = client.connect();
      var ws = http.sessions.getFirst();
      ws.open();
      ws.ackLast();
      ready.get(5, TimeUnit.SECONDS);
      var message = client.sendMarkdown("test", "large timeout");
      assertEquals(2, ws.frames.size());
      ws.ackLast();
      message.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void streamDeadlineStillCapsWrappedQueueDeadline() throws Exception {
    var http = new LifecycleRaceTest.ControlledHttp();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .queueTimeout(Duration.ofNanos(Long.MAX_VALUE))
                .logger(BotLogger.silent())
                .build())) {
      var ready = client.connect();
      var ws = http.sessions.getFirst();
      ws.open();
      ws.ackLast();
      ready.get(5, TimeUnit.SECONDS);
      var events = new Events();
      client.addListener(events);
      ws.text(
          "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"capped\"},\"body\":{\"msgtype\":\"text\"}}");
      var context = events.message().context();
      var gate = new CompletableFuture<WebSocket>();
      ws.nextWrite = gate;
      client.sendMarkdown("test", "hold transport");
      var capped =
          client.replyStreamBefore(
              context,
              new StreamContent("s", "final", true),
              System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40));
      assertEquals(BotException.Code.QUEUE_TIMEOUT, failure(capped).code());
      assertEquals(2, ws.frames.size());
    }
  }
}
