package io.github.moment.wecom.aibot;

import static io.github.moment.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class UploadConcurrencyTest {
  @Test
  void uploadConcurrencyMatchesOfficialAdaptiveWindows() throws Exception {
    for (int count : List.of(4, 6, 11))
      try (var server = new LocalWebSocketServer();
          var client =
              new WecomAiBotClient(options(server).ackTimeout(Duration.ofSeconds(5)).build())) {
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
            client.uploadMedia(
                new byte[(count - 1) * MediaUpload.CHUNK_BYTES + 1], MediaType.FILE, "x");
        int expected = count <= 4 ? count : count <= 10 ? 3 : 2;
        List<LocalWebSocketServer.Received> window = new java.util.ArrayList<>();
        for (int i = 0; i < expected; i++) window.add(server.next("aibot_upload_media_chunk"));
        Thread.sleep(60);
        assertTrue(server.received.isEmpty());
        for (var r : window) r.peer().ack(r.frame(), 0, null);
        for (int i = expected; i < count; i++) {
          var r = server.next("aibot_upload_media_chunk");
          r.peer().ack(r.frame(), 0, null);
        }
        assertEquals("m", upload.get(5, TimeUnit.SECONDS).mediaId());
      }
  }

  @Test
  void initFailureDoesNotRetryAndUploadsAreGloballyBounded() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      server.handler =
          (p, n) -> {
            if (n.path("cmd").asText().equals("aibot_subscribe")) p.ack(n, 0, null);
          };
      client.connect().get(5, TimeUnit.SECONDS);
      var a = client.uploadMedia(new byte[] {1}, MediaType.FILE, "a");
      var b = client.uploadMedia(new byte[] {2}, MediaType.FILE, "b");
      assertEquals(
          BotException.Code.QUEUE_FULL,
          failure(client.uploadMedia(new byte[] {3}, MediaType.FILE, "c")).code());
      assertEquals(BotException.Code.ACK_TIMEOUT, failure(a).code());
      failure(b);
      long count =
          server.received.stream()
              .filter(r -> r.frame().path("cmd").asText().equals("aibot_upload_media_init"))
              .count();
      assertEquals(2, count);
    }
  }
}
