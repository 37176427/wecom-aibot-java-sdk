package io.github.user37176427.wecom.aibot;

import static io.github.user37176427.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;

class LifecycleRaceTest {
  @Test
  void lateOpenAndCloseFromOldGenerationCannotAffectNewSocket() throws Exception {
    var http = new ControlledHttp();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .logger(BotLogger.silent())
                .build())) {
      var first = client.connect();
      var old = http.sessions.getFirst();
      client.disconnect();
      assertEquals(BotException.Code.NOT_CONNECTED, failure(first).code());
      var next = client.connect();
      var current = http.sessions.getLast();
      old.open();
      assertTrue(old.aborted);
      assertTrue(old.frames.isEmpty());
      current.open();
      current.ackLast();
      next.get(5, TimeUnit.SECONDS);
      old.listener.onClose(old, 1006, "late close");
      old.listener.onError(old, new RuntimeException("late error"));
      assertTrue(client.isConnected());
      client.close();
      current.listener.onOpen(current);
      assertEquals(ConnectionState.CLOSED, client.state());
      assertTrue(current.aborted);
      assertFalse(http.closed);
    }
  }

  @Test
  void closeClassifiesSentAndUnsentAndIgnoresLateAck() throws Exception {
    var http = new ControlledHttp();
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
      ready.get(5, TimeUnit.SECONDS);
      Events events = new Events();
      client.addListener(events);
      ws.text(
          "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"r\"},\"body\":{\"msgtype\":\"text\"}}");
      var c = events.message().context();
      var a = client.replyText(c, "a");
      var b = client.replyText(c, "b");
      client.close();
      assertEquals(BotException.Delivery.UNKNOWN, failure(a).delivery());
      assertEquals(BotException.Delivery.NOT_SENT, failure(b).delivery());
      ws.ackLast();
      assertEquals(ConnectionState.CLOSED, client.state());
      assertEquals(2, ws.frames.size());
    }
  }

  @Test
  void reconnectConnectFutureWaitsForNewAuthentication() throws Exception {
    var http = new ControlledHttp();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .logger(BotLogger.silent())
                .reconnectDelay(Duration.ofMillis(30))
                .build())) {
      var initial = client.connect();
      var old = http.sessions.getFirst();
      old.open();
      old.ackLast();
      initial.get(5, TimeUnit.SECONDS);
      old.listener.onError(old, new RuntimeException());
      var readyAgain = client.connect();
      assertFalse(readyAgain.isDone());
      await(() -> http.sessions.size() == 2);
      var current = http.sessions.getLast();
      current.open();
      assertFalse(readyAgain.isDone());
      current.ackLast();
      readyAgain.get(5, TimeUnit.SECONDS);
      assertTrue(client.isConnected());
    }
  }

  @Test
  void authAcknowledgementMustMatchExactRequestId() throws Exception {
    var http = new ControlledHttp();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .logger(BotLogger.silent())
                .build())) {
      var ready = client.connect();
      var ws = http.sessions.getFirst();
      ws.open();
      ws.text("{\"headers\":{\"req_id\":\"subscribe_forged\"},\"errcode\":0}");
      assertFalse(client.isConnected());
      assertFalse(ready.isDone());
      ws.ackLast();
      ready.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void synchronousTransportCompletionsDrainBoundedQueueWithoutRecursion() throws Exception {
    var http = new ControlledHttp();
    try (var client =
        new WecomAiBotClient(
            ClientOptions.builder("id", "secret")
                .httpClient(http)
                .logger(BotLogger.silent())
                .ackTimeout(Duration.ofSeconds(10))
                .build())) {
      var ready = client.connect();
      var ws = http.sessions.getFirst();
      ws.open();
      ws.ackLast();
      ready.get(5, TimeUnit.SECONDS);
      var gate = new CompletableFuture<WebSocket>();
      ws.nextWrite = gate;
      var requests = new ArrayList<CompletableFuture<Ack>>();
      for (int i = 0; i < 1500; i++) requests.add(client.sendMarkdown("chat", "message " + i));
      gate.complete(ws);
      await(() -> ws.frames.size() == 1501);
      for (var frame : ws.frames.subList(1, ws.frames.size()))
        ws.text("{\"headers\":" + frame.path("headers") + ",\"errcode\":0}");
      CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
    }
  }

  static class ControlledHttp extends HttpClient {
    final List<Socket> sessions = new CopyOnWriteArrayList<>();
    boolean closed;

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
      return new WebSocket.Builder() {
        public WebSocket.Builder header(String n, String v) {
          return this;
        }

        public WebSocket.Builder connectTimeout(Duration d) {
          return this;
        }

        public WebSocket.Builder subprotocols(String a, String... b) {
          return this;
        }

        public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
          Socket s = new Socket(listener);
          sessions.add(s);
          return s.connection;
        }
      };
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      try {
        return SSLContext.getDefault();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> b) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> b) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> b, HttpResponse.PushPromiseHandler<T> p) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdownNow() {
      closed = true;
    }
  }

  static final class Socket implements WebSocket {
    final WebSocket.Listener listener;
    final CompletableFuture<WebSocket> connection = new CompletableFuture<>();
    final List<com.fasterxml.jackson.databind.JsonNode> frames = new CopyOnWriteArrayList<>();
    boolean aborted;
    CompletableFuture<WebSocket> nextWrite;

    Socket(WebSocket.Listener listener) {
      this.listener = listener;
    }

    void open() {
      listener.onOpen(this);
      connection.complete(this);
    }

    void ackLast() {
      text("{\"headers\":" + frames.getLast().path("headers") + ",\"errcode\":0}");
    }

    void text(String value) {
      listener.onText(this, value, true);
    }

    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
      try {
        frames.add(Json.MAPPER.readTree(data.toString()));
        if (nextWrite != null) {
          var pending = nextWrite;
          nextWrite = null;
          return pending;
        }
        return CompletableFuture.completedFuture(this);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    public CompletableFuture<WebSocket> sendBinary(ByteBuffer d, boolean last) {
      return CompletableFuture.completedFuture(this);
    }

    public CompletableFuture<WebSocket> sendPing(ByteBuffer d) {
      return CompletableFuture.completedFuture(this);
    }

    public CompletableFuture<WebSocket> sendPong(ByteBuffer d) {
      return CompletableFuture.completedFuture(this);
    }

    public CompletableFuture<WebSocket> sendClose(int status, String reason) {
      return CompletableFuture.completedFuture(this);
    }

    public void request(long n) {}

    public String getSubprotocol() {
      return "";
    }

    public boolean isOutputClosed() {
      return aborted;
    }

    public boolean isInputClosed() {
      return aborted;
    }

    public void abort() {
      aborted = true;
    }
  }
}
