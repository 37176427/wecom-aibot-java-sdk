package io.github.user37176427.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Minimal RFC6455 peer to exercise the actual JDK HTTP upgrade, masking and fragmented frames. */
final class LocalWebSocketServer implements AutoCloseable {
  final ServerSocket listener;
  final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  final List<Peer> peers = new CopyOnWriteArrayList<>();
  final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
  volatile BiConsumer<Peer, JsonNode> handler = (p, n) -> p.ack(n, 0, null);
  volatile CountDownLatch handshakeGate = new CountDownLatch(0);
  volatile boolean closed;
  final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

  record Received(Peer peer, JsonNode frame) {}

  LocalWebSocketServer() throws IOException {
    listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    workers.submit(
        () -> {
          while (!closed)
            try {
              Socket socket = listener.accept();
              Peer peer = new Peer(socket);
              peers.add(peer);
              workers.submit(peer::run);
            } catch (IOException ignored) {
            }
        });
  }

  URI uri() {
    return URI.create("ws://localhost:" + listener.getLocalPort() + "/bot");
  }

  Received next(String cmd) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < end) {
      Received r = received.poll(100, TimeUnit.MILLISECONDS);
      if (r != null && cmd.equals(r.frame.path("cmd").asText())) return r;
    }
    throw new AssertionError("Missing command: " + cmd);
  }

  final class Peer {
    final Socket socket;
    final InputStream in;
    final OutputStream out;

    Peer(Socket socket) throws IOException {
      this.socket = socket;
      in = socket.getInputStream();
      out = socket.getOutputStream();
    }

    void run() {
      try {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
          h.write(b);
          if (h.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
          if (h.size() > 8192) throw new IOException("header too large");
        }
        String key =
            Arrays.stream(h.toString(StandardCharsets.US_ASCII).split("\r\n"))
                .filter(l -> l.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                .findFirst()
                .orElseThrow()
                .split(":", 2)[1]
                .trim();
        handshakeGate.await();
        String accept =
            Base64.getEncoder()
                .encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest(
                            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.US_ASCII)));
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: "
                    + accept
                    + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (!socket.isClosed()) {
          int first = in.read();
          if (first < 0) break;
          int second = in.read();
          int op = first & 15;
          long len = second & 127;
          if (len == 126) len = ((long) in.read() << 8) | in.read();
          else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
          }
          if (len > 80 * 1024 * 1024) throw new IOException("too large");
          byte[] mask = (second & 128) != 0 ? in.readNBytes(4) : null;
          byte[] payload = in.readNBytes((int) len);
          if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
          if (op == 8) break;
          if (op == 9) {
            frame(10, true, payload);
            continue;
          }
          if (op != 1 && op != 0) continue;
          message.writeBytes(payload);
          if ((first & 128) == 0) continue;
          JsonNode n = Json.MAPPER.readTree(message.toByteArray());
          message.reset();
          received.add(new Received(this, n));
          handler.accept(this, n);
        }
      } catch (SocketException ignored) {
      } catch (Throwable problem) {
        failures.add(problem);
      } finally {
        disconnect();
      }
    }

    void ack(JsonNode request, int code, JsonNode body) {
      var ack = Json.object().put("errcode", code).put("errmsg", code == 0 ? "ok" : "error");
      ack.set("headers", request.path("headers"));
      if (body != null) ack.set("body", body);
      send(ack.toString());
    }

    void callback(String id, String body) {
      send(
          "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\""
              + id
              + "\"},\"body\":"
              + body
              + "}");
    }

    void event(String id, String event) {
      send(
          "{\"cmd\":\"aibot_event_callback\",\"headers\":{\"req_id\":\""
              + id
              + "\"},\"body\":{\"msgtype\":\"event\",\"event\":"
              + event
              + "}}");
    }

    void send(String text) {
      frame(1, true, text.getBytes(StandardCharsets.UTF_8));
    }

    synchronized void frame(int op, boolean last, byte[] data) {
      try {
        out.write((last ? 128 : 0) | op);
        if (data.length < 126) out.write(data.length);
        else if (data.length <= 65535) {
          out.write(126);
          out.write(data.length >>> 8);
          out.write(data.length);
        } else {
          out.write(127);
          out.write(
              new byte[] {
                0,
                0,
                0,
                0,
                (byte) (data.length >>> 24),
                (byte) (data.length >>> 16),
                (byte) (data.length >>> 8),
                (byte) data.length
              });
        }
        out.write(data);
        out.flush();
      } catch (IOException ignored) {
      }
    }

    void disconnect() {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    listener.close();
    handshakeGate.countDown();
    for (var p : peers) p.disconnect();
    workers.shutdownNow();
    if (!failures.isEmpty()) throw new AssertionError("Local peer failed", failures.peek());
  }
}
