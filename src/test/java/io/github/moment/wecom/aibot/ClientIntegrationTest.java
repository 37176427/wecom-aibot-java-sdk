package io.github.moment.wecom.aibot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ClientIntegrationTest {
  static ClientOptions.Builder options(LocalWebSocketServer s) {
    return ClientOptions.builder("test-bot", "test-secret")
        .endpoint(s.uri())
        .logger(BotLogger.silent())
        .ackTimeout(Duration.ofMillis(300))
        .reconnectDelay(Duration.ofMillis(30));
  }

  static void await(BooleanSupplier b) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!b.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(b.getAsBoolean());
  }

  static BotException failure(CompletableFuture<?> f) {
    var e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
    assertInstanceOf(BotException.class, e.getCause());
    return (BotException) e.getCause();
  }

  static final class Events implements BotListener {
    final BlockingQueue<IncomingMessage> messages = new LinkedBlockingQueue<>();
    final BlockingQueue<IncomingEvent> events = new LinkedBlockingQueue<>();
    final BlockingQueue<BotException> errors = new LinkedBlockingQueue<>();

    public void onMessage(IncomingMessage m) {
      messages.add(m);
    }

    public void onEvent(IncomingEvent e) {
      events.add(e);
    }

    public void onError(BotException e) {
      errors.add(e);
    }

    IncomingMessage message() throws Exception {
      var m = messages.poll(5, TimeUnit.SECONDS);
      assertNotNull(m);
      return m;
    }

    IncomingEvent event() throws Exception {
      var e = events.poll(5, TimeUnit.SECONDS);
      assertNotNull(e);
      return e;
    }
  }

  @Test
  void authenticationAndEveryIncomingTypeIncludingUnknownAndQuote() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).scene(7).pluginVersion("1.2").build())) {
      Events events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      var auth = server.next("aibot_subscribe");
      assertEquals("test-secret", auth.frame().at("/body/secret").asText());
      assertEquals(7, auth.frame().at("/body/scene").asInt());
      assertEquals("1.2", auth.frame().at("/body/plug_version").asText());
      for (String type : List.of("text", "image", "mixed", "voice", "file", "video", "future")) {
        auth.peer()
            .callback(
                "r-" + type,
                "{\"msgtype\":\""
                    + type
                    + "\",\"msgid\":\"m\",\"from\":{\"userid\":\"u\"},\"future_field\":42,\"quote\":{\"msgtype\":\"text\",\"text\":{\"content\":\"quoted\"}}}");
        var m = events.message();
        assertEquals(type, m.typeName());
        assertEquals("u", m.userId());
        assertEquals(42, m.body().path("future_field").asInt());
        assertEquals("quoted", m.quote().text());
        assertEquals(
            type.equals("future")
                ? IncomingMessage.Type.UNKNOWN
                : IncomingMessage.Type.valueOf(type.toUpperCase()),
            m.type());
      }
      for (String event :
          List.of("enter_chat", "template_card_event", "feedback_event", "future_event")) {
        auth.peer()
            .event(
                event,
                "{\"eventtype\":\""
                    + event
                    + "\",\"task_id\":\"task\",\"event_key\":\"yes\",\"future\":123}");
        var e = events.event();
        assertEquals(event, e.typeName());
        assertEquals(123, e.data().path("future").asInt());
      }
    }
  }

  @Test
  void authFailureAndMissingAuthAckExhaustSeparateBudget() throws Exception {
    for (boolean reject : List.of(true, false))
      try (var server = new LocalWebSocketServer();
          var client = new WecomAiBotClient(options(server).maxAuthRetries(1).build())) {
        server.handler =
            (p, n) -> {
              if (reject) p.ack(n, 40014, null);
            };
        assertEquals(BotException.Code.AUTH_EXHAUSTED, failure(client.connect()).code());
        assertEquals(2, server.peers.size());
        assertEquals(ConnectionState.FAILED, client.state());
      }
  }

  @Test
  void reconnectHeartbeatStopAndOldContext() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client =
            new WecomAiBotClient(
                options(server)
                    .heartbeatInterval(Duration.ofMillis(30))
                    .maxMissedHeartbeats(2)
                    .build())) {
      server.handler =
          (p, n) -> {
            if (!"ping".equals(n.path("cmd").asText())) p.ack(n, 0, null);
          };
      Events events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      server.peers.getFirst().callback("old", "{\"msgtype\":\"text\"}");
      var context = events.message().context();
      await(() -> server.peers.size() >= 2);
      await(client::isConnected);
      assertEquals(
          BotException.Code.STALE_CONTEXT, failure(client.replyText(context, "old")).code());
      client.disconnect();
      int count = server.peers.size();
      Thread.sleep(150);
      assertEquals(count, server.peers.size());
      assertEquals(ConnectionState.DISCONNECTED, client.state());
    }
  }

  @Test
  void serverReplacementNeverReconnects() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      Events events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      server.peers.getFirst().event("kick", "{\"eventtype\":\"disconnected_event\"}");
      assertEquals(IncomingEvent.Type.DISCONNECTED_EVENT, events.event().type());
      await(() -> client.state() == ConnectionState.DISCONNECTED);
      Thread.sleep(150);
      assertEquals(1, server.peers.size());
    }
  }

  @Test
  void closeWhileHandshakePendingCannotResurrect() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      server.handshakeGate = new CountDownLatch(1);
      var ready = client.connect();
      await(() -> !server.peers.isEmpty());
      client.close();
      server.handshakeGate.countDown();
      assertEquals(BotException.Code.CLOSED, failure(ready).code());
      Thread.sleep(150);
      assertEquals(ConnectionState.CLOSED, client.state());
      assertEquals(BotException.Code.CLOSED, failure(client.connect()).code());
      assertTrue(server.received.isEmpty());
    }
  }

  @Test
  void queuesSerializeSameRequestAndAllowOtherRequests() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client =
            new WecomAiBotClient(
                options(server).ackTimeout(Duration.ofSeconds(3)).maxQueuePerRequest(2).build())) {
      Events events = new Events();
      client.addListener(events);
      server.handler =
          (p, n) -> {
            if ("aibot_subscribe".equals(n.path("cmd").asText())) p.ack(n, 0, null);
          };
      client.connect().get(5, TimeUnit.SECONDS);
      server.peers.getFirst().callback("r", "{\"msgtype\":\"text\"}");
      var c = events.message().context();
      var one = client.replyStream(c, new StreamContent("s", "first", false));
      var two = client.replyStream(c, new StreamContent("s", "final", true));
      assertEquals(BotException.Code.QUEUE_FULL, failure(client.replyText(c, "overflow")).code());
      var first = server.next("aibot_respond_msg");
      assertEquals("first", first.frame().at("/body/stream/content").asText());
      var independent = client.sendMarkdown("chat", "notice");
      var sent = server.next("aibot_send_msg");
      sent.peer().ack(sent.frame(), 0, null);
      independent.get(5, TimeUnit.SECONDS);
      assertFalse(two.isDone());
      first.peer().ack(first.frame(), 0, null);
      one.get(5, TimeUnit.SECONDS);
      var second = server.next("aibot_respond_msg");
      assertEquals("final", second.frame().at("/body/stream/content").asText());
      assertTrue(second.frame().at("/body/stream/finish").asBoolean());
      second.peer().ack(second.frame(), 0, null);
      two.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void lateAckCannotCompleteNextReplyAndNothingIsReplayed() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      Events events = new Events();
      client.addListener(events);
      server.handler =
          (p, n) -> {
            if ("aibot_subscribe".equals(n.path("cmd").asText())) p.ack(n, 0, null);
          };
      client.connect().get(5, TimeUnit.SECONDS);
      server.peers.getFirst().callback("r", "{\"msgtype\":\"text\"}");
      var c = events.message().context();
      var a = client.replyText(c, "a");
      var b = client.replyText(c, "b");
      var sent = server.next("aibot_respond_msg");
      assertEquals(BotException.Delivery.UNKNOWN, failure(a).delivery());
      assertEquals(BotException.Delivery.NOT_SENT, failure(b).delivery());
      sent.peer().ack(sent.frame(), 0, null);
      assertEquals(BotException.Code.ACK_TIMEOUT, failure(client.replyText(c, "c")).code());
      Thread.sleep(80);
      assertTrue(server.received.isEmpty());
    }
  }

  @Test
  void remoteAckErrorAndConcurrentRequests() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client =
            new WecomAiBotClient(options(server).ackTimeout(Duration.ofSeconds(3)).build())) {
      client.connect().get(5, TimeUnit.SECONDS);
      server.handler =
          (p, n) ->
              p.ack(n, n.at("/body/markdown/content").asText().equals("reject") ? 45009 : 0, null);
      var e = failure(client.sendMarkdown("chat", "reject"));
      assertEquals(45009, e.remoteCode());
      assertEquals(BotException.Delivery.REJECTED, e.delivery());
      List<CompletableFuture<Ack>> sends = new ArrayList<>();
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<CompletableFuture<Ack>>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++)
          tasks.add(pool.submit(() -> client.sendMarkdown("chat", WecomAiBotClient.newId())));
        for (var task : tasks) sends.add(task.get());
      }
      CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
      assertEquals(50, sends.stream().map(f -> f.join().requestId()).distinct().count());
    }
  }

  @Test
  void reconnectExhaustionAndDisconnectCancelsPendingReconnect() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).maxReconnectAttempts(1).build())) {
      server.handler = (p, n) -> p.disconnect();
      assertEquals(BotException.Code.RECONNECT_EXHAUSTED, failure(client.connect()).code());
      assertEquals(2, server.peers.size());
    }
    try (var server = new LocalWebSocketServer();
        var client =
            new WecomAiBotClient(options(server).reconnectDelay(Duration.ofMillis(150)).build())) {
      client.connect().get(5, TimeUnit.SECONDS);
      server.peers.getFirst().disconnect();
      await(() -> client.state() == ConnectionState.RECONNECTING);
      client.disconnect();
      Thread.sleep(250);
      assertEquals(1, server.peers.size());
    }
  }

  @Test
  void malformedAndFragmentedFramesAndListenerFailureAreIsolated() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      Events events = new Events();
      client
          .addListener(
              new BotListener() {
                public void onMessage(IncomingMessage m) {
                  throw new IllegalStateException();
                }
              })
          .addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      var p = server.peers.getFirst();
      p.send("{bad");
      await(() -> !events.errors.isEmpty());
      assertEquals(BotException.Code.PROTOCOL, events.errors.take().code());
      String json =
          "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"frag\"},\"body\":{\"msgtype\":\"text\",\"text\":{\"content\":\"中文\"}}}";
      byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      p.frame(1, false, Arrays.copyOfRange(bytes, 0, bytes.length - 5));
      p.frame(0, true, Arrays.copyOfRange(bytes, bytes.length - 5, bytes.length));
      assertEquals("中文", events.message().content().text());
      assertTrue(client.isConnected());
    }
  }
}
