package io.github.moment.wecom.aibot;

import static io.github.moment.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
  @Test
  void invalidResourceAndRetrySettingsFailAtConstruction() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder("id", "s").maxUploads(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder("id", "s").maxCallbackQueueSize(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder("id", "s").uploadChunkRetries(-1).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder("id", "s").uploadMaxConcurrency(5).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder("id", "s").uploadMaxConcurrency(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder("id", "s").uploadRetryDelay(Duration.ZERO).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder("id", "s").reconnectMaxDelay(Duration.ofMillis(5)).build());
  }

  @Test
  void configuredWholeUploadAndChunkLimitsAreEnforced() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client =
            new WecomAiBotClient(
                options(server)
                    .maxUploads(1)
                    .uploadMaxConcurrency(1)
                    .ackTimeout(Duration.ofSeconds(5))
                    .build())) {
      server.handler =
          (p, n) -> {
            switch (n.path("cmd").asText()) {
              case "aibot_upload_media_init" -> p.ack(n, 0, Json.object().put("upload_id", "u"));
              case "aibot_upload_media_chunk" -> {}
              case "aibot_upload_media_finish" -> p.ack(n, 0, Json.object().put("media_id", "m"));
              default -> p.ack(n, 0, null);
            }
          };
      client.connect().get(5, TimeUnit.SECONDS);
      var upload =
          client.uploadMedia(new byte[MediaUpload.CHUNK_BYTES + 1], MediaType.FILE, "two.bin");
      var chunk = server.next("aibot_upload_media_chunk");
      assertEquals(
          BotException.Code.QUEUE_FULL,
          failure(client.uploadMedia(new byte[] {1}, MediaType.FILE, "other")).code());
      Thread.sleep(30);
      assertTrue(server.received.isEmpty());
      chunk.peer().ack(chunk.frame(), 0, null);
      var second = server.next("aibot_upload_media_chunk");
      assertEquals(1, second.frame().at("/body/chunk_index").asInt());
      second.peer().ack(second.frame(), 0, null);
      assertEquals("m", upload.get(5, TimeUnit.SECONDS).mediaId());
    }
  }

  @Test
  void chunkRetriesCanBeDisabledOrConfigured() throws Exception {
    for (int retries : List.of(0, 1, 3)) {
      try (var server = new LocalWebSocketServer();
          var client =
              new WecomAiBotClient(
                  options(server)
                      .uploadChunkRetries(retries)
                      .uploadRetryDelay(Duration.ofMillis(20))
                      .build())) {
        AtomicInteger calls = new AtomicInteger();
        List<Long> times = new CopyOnWriteArrayList<>();
        server.handler =
            (p, n) -> {
              switch (n.path("cmd").asText()) {
                case "aibot_upload_media_init" -> p.ack(n, 0, Json.object().put("upload_id", "u"));
                case "aibot_upload_media_chunk" -> {
                  calls.incrementAndGet();
                  times.add(System.nanoTime());
                  p.ack(n, -1, null);
                }
                default -> p.ack(n, 0, null);
              }
            };
        client.connect().get(5, TimeUnit.SECONDS);
        failure(client.uploadMedia(new byte[] {1}, MediaType.FILE, "one"));
        assertEquals(retries + 1, calls.get());
        for (int i = 1; i < times.size(); i++)
          assertTrue(times.get(i) - times.get(i - 1) >= TimeUnit.MILLISECONDS.toNanos(20L * i));
      }
    }
  }

  @Test
  void callbackQueueCapacityIsIndependentOfOutboundCapacity() throws Exception {
    var blocked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var server = new LocalWebSocketServer();
        var client =
            new WecomAiBotClient(
                options(server).maxOutstanding(1).maxCallbackQueueSize(8).build())) {
      var messages = new AtomicInteger();
      client.addListener(
          new BotListener() {
            public void onMessage(IncomingMessage m) {
              messages.incrementAndGet();
              blocked.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          });
      client.connect().get(5, TimeUnit.SECONDS);
      var peer = server.peers.getFirst();
      peer.callback("one", "{\"msgtype\":\"text\"}");
      assertTrue(blocked.await(5, TimeUnit.SECONDS));
      for (int i = 0; i < 3; i++) peer.callback("queued" + i, "{\"msgtype\":\"text\"}");
      // A protocol ACK is processed while the user callback is blocked.
      client.sendMarkdown("chat", "barrier").get(5, TimeUnit.SECONDS);
      assertTrue(client.isConnected());
      release.countDown();
      await(() -> messages.get() == 4);
    } finally {
      release.countDown();
    }
  }

  @Test
  void reconnectBackoffHonorsConfiguredMaximum() throws Exception {
    var http = new LifecycleRaceTest.ControlledHttp();
    var delays = new LinkedBlockingQueue<Long>();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "s")
                .httpClient(http)
                .logger(BotLogger.silent())
                .reconnectDelay(Duration.ofMillis(10))
                .reconnectMaxDelay(Duration.ofMillis(25))
                .build())) {
      client.addListener(
          new BotListener() {
            public void onReconnecting(int n, Duration delay, boolean auth) {
              delays.add(delay.toMillis());
            }
          });
      client.connect();
      for (int i = 0; i < 3; i++) {
        int count = i + 1;
        await(() -> http.sessions.size() == count);
        var ws = http.sessions.getLast();
        ws.listener.onError(ws, new IllegalStateException());
        assertEquals(List.of(10L, 20L, 25L).get(i), delays.poll(5, TimeUnit.SECONDS));
      }
    }
  }
}
