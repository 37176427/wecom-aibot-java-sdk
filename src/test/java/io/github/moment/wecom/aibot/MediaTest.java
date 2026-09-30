package io.github.moment.wecom.aibot;

import static io.github.moment.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MediaTest {
  @Test
  void chunkUploadZeroBasedReassemblyRetryAndFinish() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client =
            new WecomAiBotClient(options(server).ackTimeout(Duration.ofSeconds(3)).build())) {
      byte[] bytes = new byte[MediaUpload.CHUNK_BYTES * 5 + 7];
      new Random(1).nextBytes(bytes);
      Map<Integer, byte[]> chunks = new ConcurrentHashMap<>();
      AtomicInteger failures = new AtomicInteger(),
          initCalls = new AtomicInteger(),
          finishCalls = new AtomicInteger();
      server.handler =
          (p, n) -> {
            String cmd = n.path("cmd").asText();
            var b = n.path("body");
            switch (cmd) {
              case "aibot_upload_media_init" -> {
                initCalls.incrementAndGet();
                assertEquals(6, b.path("total_chunks").asInt());
                assertEquals(StreamContent.digest(bytes), b.path("md5").asText());
                p.ack(n, 0, Json.object().put("upload_id", "u"));
              }
              case "aibot_upload_media_chunk" -> {
                int i = b.path("chunk_index").asInt();
                assertEquals("u", b.path("upload_id").asText());
                if (i == 1 && failures.getAndIncrement() == 0) p.ack(n, -1, null);
                else {
                  chunks.put(i, Base64.getDecoder().decode(b.path("base64_data").asText()));
                  p.ack(n, 0, null);
                }
              }
              case "aibot_upload_media_finish" -> {
                finishCalls.incrementAndGet();
                p.ack(
                    n,
                    0,
                    Json.object()
                        .put("media_id", "media")
                        .put("type", "file")
                        .put("created_at", "123"));
              }
              default -> p.ack(n, 0, null);
            }
          };
      client.connect().get(5, TimeUnit.SECONDS);
      var result = client.uploadMedia(bytes, MediaType.FILE, "test.bin").get(8, TimeUnit.SECONDS);
      assertEquals("media", result.mediaId());
      assertEquals("123", result.createdAt());
      assertEquals(1, initCalls.get());
      assertEquals(1, finishCalls.get());
      assertEquals(6, chunks.size());
      var output = new java.io.ByteArrayOutputStream();
      for (int i = 0; i < 6; i++) output.writeBytes(chunks.get(i));
      assertArrayEquals(bytes, output.toByteArray());
      assertEquals(2, failures.get());
    }
  }

  @Test
  void exactChunkBoundaryAndFailedFinishAreNotRetried() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      AtomicInteger chunks = new AtomicInteger(), finishes = new AtomicInteger();
      server.handler =
          (p, n) -> {
            switch (n.path("cmd").asText()) {
              case "aibot_upload_media_init" -> {
                assertEquals(1, n.at("/body/total_chunks").asInt());
                p.ack(n, 0, Json.object().put("upload_id", "u"));
              }
              case "aibot_upload_media_chunk" -> {
                chunks.incrementAndGet();
                assertEquals(0, n.at("/body/chunk_index").asInt());
                p.ack(n, 0, null);
              }
              case "aibot_upload_media_finish" -> {
                finishes.incrementAndGet();
                p.ack(n, -1, null);
              }
              default -> p.ack(n, 0, null);
            }
          };
      client.connect().get(5, TimeUnit.SECONDS);
      assertEquals(
          BotException.Code.REMOTE_ERROR,
          failure(
                  client.uploadMedia(
                      new byte[MediaUpload.CHUNK_BYTES], MediaType.IMAGE, "image.png"))
              .code());
      assertEquals(1, chunks.get());
      assertEquals(1, finishes.get());
      assertThrows(
          IllegalArgumentException.class,
          () -> client.uploadMedia(new byte[0], MediaType.FILE, "empty"));
    }
  }

  @Test
  void chunkRetryLimitAndDisconnectStopUpload() throws Exception {
    for (boolean disconnect : List.of(false, true))
      try (var server = new LocalWebSocketServer();
          var client = new WecomAiBotClient(options(server).build())) {
        AtomicInteger attempts = new AtomicInteger(), finish = new AtomicInteger();
        server.handler =
            (p, n) -> {
              switch (n.path("cmd").asText()) {
                case "aibot_upload_media_init" -> p.ack(n, 0, Json.object().put("upload_id", "u"));
                case "aibot_upload_media_chunk" -> {
                  attempts.incrementAndGet();
                  if (disconnect) p.disconnect();
                  else p.ack(n, -1, null);
                }
                case "aibot_upload_media_finish" -> finish.incrementAndGet();
                default -> p.ack(n, 0, null);
              }
            };
        client.connect().get(5, TimeUnit.SECONDS);
        failure(client.uploadMedia(new byte[] {1, 2, 3}, MediaType.FILE, "a"));
        assertEquals(disconnect ? 1 : 3, attempts.get());
        assertEquals(0, finish.get());
      }
  }

  static byte[] encrypt(byte[] raw, byte[] key) throws Exception {
    Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
    c.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new IvParameterSpec(Arrays.copyOf(key, 16)));
    return c.doFinal(WecomCrypto.pad(raw, 32));
  }

  @Test
  void realHttpDownloadDecryptFilenameLimitsStatusAndTimeout() throws Exception {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 7);
    byte[] plaintext = "中文 file".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        encrypted = encrypt(plaintext, key);
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    var workers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(workers);
    server.createContext(
        "/file",
        x -> {
          x.getResponseHeaders()
              .add("Content-Disposition", "attachment; filename*=UTF-8''%E6%96%87%E4%BB%B6+a.bin");
          x.sendResponseHeaders(200, encrypted.length);
          x.getResponseBody().write(encrypted);
          x.close();
        });
    server.createContext(
        "/large",
        x -> {
          x.sendResponseHeaders(200, 1024);
          x.getResponseBody().write(new byte[1024]);
          x.close();
        });
    server.createContext(
        "/bad",
        x -> {
          x.sendResponseHeaders(403, -1);
          x.close();
        });
    server.createContext(
        "/slow",
        x -> {
          x.sendResponseHeaders(200, 100);
          try {
            Thread.sleep(700);
            x.getResponseBody().write(new byte[100]);
          } catch (Exception ignored) {
          }
          x.close();
        });
    server.start();
    try (var http = HttpClient.newHttpClient();
        var downloader = new MediaDownloader(http, Duration.ofMillis(250), 512, 1)) {
      URI base = URI.create("http://localhost:" + server.getAddress().getPort());
      var d =
          downloader
              .download(
                  base.resolve("/file"), Base64.getEncoder().withoutPadding().encodeToString(key))
              .get(5, TimeUnit.SECONDS);
      assertArrayEquals(plaintext, d.bytes());
      assertEquals("文件+a.bin", d.filename());
      assertEquals(
          BotException.Code.DOWNLOAD,
          failure(downloader.download(base.resolve("/large"), null)).code());
      assertEquals(
          BotException.Code.DOWNLOAD,
          failure(downloader.download(base.resolve("/bad"), null)).code());
      var slow = downloader.download(base.resolve("/slow"), null);
      assertEquals(
          BotException.Code.QUEUE_FULL,
          failure(downloader.download(base.resolve("/file"), null)).code());
      assertEquals(BotException.Code.DOWNLOAD, failure(slow).code());
    } finally {
      server.stop(0);
      workers.shutdownNow();
    }
  }

  @Test
  void paddingAndWrongKeyRejected() throws Exception {
    byte[] key = new byte[32];
    for (int len : List.of(0, 1, 16, 31, 32, 33, 64)) {
      byte[] plain = new byte[len];
      Arrays.fill(plain, (byte) 'a');
      assertArrayEquals(
          plain,
          WecomCrypto.decryptFile(encrypt(plain, key), Base64.getEncoder().encodeToString(key)));
    }
    assertThrows(BotException.class, () -> WecomCrypto.unpad(new byte[] {1, 2, 3, 2}, 32));
    assertThrows(BotException.class, () -> WecomCrypto.unpad(new byte[] {0}, 32));
    assertThrows(IllegalArgumentException.class, () -> WecomCrypto.decodeKey("abc"));
    byte[] cipher = encrypt(new byte[] {1}, key);
    byte[] wrong = new byte[32];
    Arrays.fill(wrong, (byte) 8);
    assertThrows(
        BotException.class,
        () -> WecomCrypto.decryptFile(cipher, Base64.getEncoder().encodeToString(wrong)));
  }
}
