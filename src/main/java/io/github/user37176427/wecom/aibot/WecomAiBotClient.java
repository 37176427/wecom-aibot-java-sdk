package io.github.user37176427.wecom.aibot;

import static io.github.user37176427.wecom.aibot.BotException.Code.*;
import static io.github.user37176427.wecom.aibot.BotException.Delivery.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Thread-safe asynchronous long-connection client. close() is permanent; disconnect() permits
 * explicit reconnect.
 */
public final class WecomAiBotClient implements AutoCloseable {
  private final Object lock = new Object();
  private final ClientOptions options;
  private final HttpClient http;
  private final ScheduledThreadPoolExecutor timers =
      new ScheduledThreadPoolExecutor(
          1,
          Thread.ofPlatform()
              .daemon()
              .inheritInheritableThreadLocals(false)
              .name("wecom-timer")
              .factory());
  private final CompletionDispatcher completions;
  private final ThreadPoolExecutor callbacks;
  private final CopyOnWriteArrayList<BotListener> listeners = new CopyOnWriteArrayList<>();
  private final Map<String, ArrayDeque<Pending>> queues = new HashMap<>();
  private final ArrayDeque<Pending> wireQueue = new ArrayDeque<>();
  private final Set<String> poisoned = new HashSet<>();
  private final LinkedHashSet<String> heartbeats = new LinkedHashSet<>();
  private final MediaDownloader downloader;
  private final Semaphore uploadSlots;
  private final Map<CompletableFuture<Void>, ScheduledFuture<?>> uploadDelays = new HashMap<>();
  private WebSocket socket;
  private CompletableFuture<WebSocket> handshake;
  private CompletableFuture<Void> connected;
  private ScheduledFuture<?> reconnectTask, heartbeatTask, authTask;
  private volatile ConnectionState state = ConnectionState.NEW;
  private boolean running, closed, writing, pumping;
  private long generation;
  private int reconnectAttempts, authFailures, outstanding;
  private String authId;

  public WecomAiBotClient(ClientOptions options) {
    this.options = Objects.requireNonNull(options);
    uploadSlots = new Semaphore(options.maxUploads);
    completions =
        new CompletionDispatcher(
            options.completionThreads, options.maxCompletionTasks, options.completionExecutor);
    http =
        options.httpClient != null
            ? options.httpClient
            : HttpClient.newBuilder()
                .connectTimeout(options.connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    timers.setRemoveOnCancelPolicy(true);
    callbacks =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(options.maxCallbackQueueSize),
            Thread.ofPlatform()
                .daemon()
                .inheritInheritableThreadLocals(false)
                .name("wecom-callback")
                .factory());
    downloader =
        new MediaDownloader(
            http, options.downloadTimeout, options.maxDownloadBytes, options.maxDownloads);
  }

  public WecomAiBotClient addListener(BotListener listener) {
    listeners.add(Objects.requireNonNull(listener));
    return this;
  }

  public void removeListener(BotListener listener) {
    listeners.remove(listener);
  }

  public ConnectionState state() {
    return state;
  }

  public boolean isConnected() {
    return state == ConnectionState.READY;
  }

  public MediaDownloader downloader() {
    return downloader;
  }

  public static String newId() {
    return UUID.randomUUID().toString();
  }

  /** Completes on authentication, or exceptionally when stopped or retries are exhausted. */
  public CompletableFuture<Void> connect() {
    synchronized (lock) {
      if (closed)
        return CompletableFuture.failedFuture(failure(CLOSED, NOT_SENT, "Client is closed"));
      if (running) return connected.copy();
      try {
        connected = completions.newFuture();
      } catch (BotException full) {
        return CompletableFuture.failedFuture(full);
      }
      running = true;
      reconnectAttempts = 0;
      authFailures = 0;
      open();
      return connected.copy();
    }
  }

  private void open() {
    if (!running || closed) return;
    long g = ++generation;
    poisoned.clear();
    heartbeats.clear();
    writing = false;
    authId = null;
    changeState(ConnectionState.CONNECTING);
    try {
      handshake =
          http.newWebSocketBuilder()
              .connectTimeout(options.connectTimeout)
              .buildAsync(options.endpoint, new SocketListener(g));
      handshake.whenComplete(
          (ws, e) -> {
            synchronized (lock) {
              if (g != generation || !running) {
                if (ws != null) ws.abort();
                return;
              }
              if (e != null)
                lost(g, false, failure(TRANSPORT, NOT_SENT, "WebSocket handshake failed"));
            }
          });
    } catch (RuntimeException e) {
      lost(g, false, failure(TRANSPORT, NOT_SENT, "WebSocket handshake failed"));
    }
  }

  private void opened(long g, WebSocket ws) {
    synchronized (lock) {
      if (g != generation || !running || closed) {
        ws.abort();
        return;
      }
      socket = ws;
      changeState(ConnectionState.AUTHENTICATING);
      authId = "subscribe_" + newId();
      ObjectNode body = Json.object().put("bot_id", options.botId).put("secret", options.secret);
      if (options.scene != null) body.put("scene", options.scene);
      if (options.pluginVersion != null) body.put("plug_version", options.pluginVersion);
      authTask =
          timers.schedule(
              () -> {
                synchronized (lock) {
                  if (g == generation && state == ConnectionState.AUTHENTICATING)
                    lost(g, true, failure(ACK_TIMEOUT, UNKNOWN, "Authentication timed out"));
                }
              },
              options.ackTimeout.toMillis(),
              TimeUnit.MILLISECONDS);
      control("aibot_subscribe", authId, body, g);
      ws.request(1);
    }
  }

  private void receive(long g, String text) {
    synchronized (lock) {
      if (g != generation || !running || closed) return;
      WsFrame frame;
      try {
        frame = new WsFrame(Json.MAPPER.readTree(text));
      } catch (Exception e) {
        report(failure(PROTOCOL, NOT_SENT, "Invalid JSON frame"));
        return;
      }
      String cmd = frame.command(), id = frame.requestId();
      if ("aibot_msg_callback".equals(cmd) || "aibot_event_callback".equals(cmd)) {
        if (state != ConnectionState.READY
            || id == null
            || id.isBlank()
            || !frame.body().isObject()) {
          report(failure(PROTOCOL, NOT_SENT, "Invalid callback envelope"));
          return;
        }
        String event = Json.text(frame.body().path("event"), "eventtype");
        ReplyContext context =
            new ReplyContext(
                this, g, id, event, Json.text(IncomingEvent.details(frame), "task_id"));
        if ("disconnected_event".equals(event) && "aibot_event_callback".equals(cmd)) {
          stop(
              ConnectionState.DISCONNECTED,
              failure(SERVER_DISCONNECTED, UNKNOWN, "Server replaced this connection"));
          notifyListeners(l -> l.onEvent(new IncomingEvent(context, frame)));
          return;
        }
        boolean delivered =
            notifyListeners(
                l -> {
                  synchronized (lock) {
                    if (g != generation || !running) return;
                  }
                  if ("aibot_msg_callback".equals(cmd))
                    l.onMessage(new IncomingMessage(context, frame));
                  else l.onEvent(new IncomingEvent(context, frame));
                });
        if (!delivered)
          lost(
              g,
              false,
              failure(
                  QUEUE_FULL,
                  NOT_SENT,
                  "Callback queue full; connection stopped to apply backpressure"));
        return;
      }
      if (id != null && id.equals(authId) && state == ConnectionState.AUTHENTICATING) {
        if (!Integer.valueOf(0).equals(frame.errorCode())) {
          lost(g, true, remote(frame));
          return;
        }
        cancel(authTask);
        authId = null;
        reconnectAttempts = 0;
        authFailures = 0;
        changeState(ConnectionState.READY);
        succeed(connected, null);
        heartbeatTask =
            timers.scheduleWithFixedDelay(
                () -> heartbeat(g),
                options.heartbeatInterval.toMillis(),
                options.heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        return;
      }
      if (id != null && heartbeats.contains(id)) {
        if (Integer.valueOf(0).equals(frame.errorCode())) heartbeats.clear();
        return;
      }
      ArrayDeque<Pending> queue = queues.get(id);
      if (queue != null && !queue.isEmpty() && queue.peek().sent) {
        Pending p = queue.peek();
        if (frame.errorCode() == null) {
          report(failure(PROTOCOL, UNKNOWN, "ACK missing integer errcode"));
          return;
        }
        remove(p);
        if (frame.errorCode() == 0) succeed(p.result, new Ack(frame));
        else fail(p.result, remote(frame));
        startHead(id);
        return;
      }
      notifyListeners(l -> l.onUnknownFrame(frame));
    }
  }

  private void heartbeat(long g) {
    synchronized (lock) {
      if (g != generation || state != ConnectionState.READY || !running) return;
      if (heartbeats.size() >= options.maxMissedHeartbeats) {
        lost(g, false, failure(HEARTBEAT_TIMEOUT, UNKNOWN, "Heartbeat acknowledgement missing"));
        return;
      }
      String id = "ping_" + newId();
      heartbeats.add(id);
      control("ping", id, null, g);
    }
  }

  private void lost(long g, boolean authFailure, BotException error) {
    if (g != generation || !running || closed) return;
    if (state == ConnectionState.READY) {
      try {
        connected = completions.newFuture();
      } catch (BotException full) {
        stop(ConnectionState.FAILED, full);
        report(full);
        return;
      }
    }
    tearDown(error);
    report(error);
    int attempt = authFailure ? ++authFailures : ++reconnectAttempts;
    int max = authFailure ? options.maxAuthRetries : options.maxReconnectAttempts;
    if (max != -1 && attempt > max) {
      running = false;
      changeState(ConnectionState.FAILED);
      BotException exhausted =
          failure(
              authFailure ? AUTH_EXHAUSTED : RECONNECT_EXHAUSTED,
              NOT_SENT,
              "Connection retry limit exhausted");
      fail(connected, exhausted);
      report(exhausted);
      return;
    }
    changeState(ConnectionState.RECONNECTING);
    long delay =
        (long)
            Math.min(
                options.reconnectMaxDelay.toMillis(),
                options.reconnectDelay.toMillis() * Math.pow(2, Math.min(attempt - 1, 62)));
    notifyListeners(l -> l.onReconnecting(attempt, Duration.ofMillis(delay), authFailure));
    long expected = generation;
    reconnectTask =
        timers.schedule(
            () -> {
              synchronized (lock) {
                if (running && !closed && generation == expected) open();
              }
            },
            delay,
            TimeUnit.MILLISECONDS);
  }

  private void tearDown(BotException error) {
    ++generation;
    cancel(reconnectTask);
    cancel(heartbeatTask);
    cancel(authTask);
    if (handshake != null) {
      handshake.cancel(true);
      handshake = null;
    }
    if (socket != null) {
      socket.abort();
      socket = null;
    }
    for (var q : queues.values())
      for (Pending p : q) {
        cancel(p.timeout);
        cancel(p.queueTimer);
        if (p.context != null && p.sent) p.context.uncertain = true;
        fail(
            p.result,
            new BotException(
                error.code(), p.sent ? UNKNOWN : NOT_SENT, error.getMessage(), p.id, null, null));
      }
    for (var entry : uploadDelays.entrySet()) {
      cancel(entry.getValue());
      fail(entry.getKey(), failure(STALE_CONTEXT, NOT_SENT, "Upload connection changed"));
    }
    uploadDelays.clear();
    queues.clear();
    wireQueue.clear();
    outstanding = 0;
    writing = false;
    heartbeats.clear();
    authId = null;
  }

  private void stop(ConnectionState target, BotException error) {
    running = false;
    tearDown(error);
    fail(connected, error);
    changeState(target);
  }

  public void disconnect() {
    synchronized (lock) {
      if (!closed)
        stop(ConnectionState.DISCONNECTED, failure(NOT_CONNECTED, NOT_SENT, "Client disconnected"));
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      stop(ConnectionState.CLOSED, failure(CLOSED, NOT_SENT, "Client closed"));
      timers.shutdownNow();
      downloader.close();
      callbacks.shutdown();
      completions.close();
      if (options.httpClient == null) http.shutdownNow();
    }
  }

  private void changeState(ConnectionState next) {
    state = next;
    safeLog(System.Logger.Level.DEBUG, "Connection state: " + next);
    notifyListeners(l -> l.onState(next));
  }

  private void safeLog(System.Logger.Level level, String message) {
    try {
      options.logger.log(level, message);
    } catch (RuntimeException ignored) {
    }
  }

  private void report(BotException e) {
    safeLog(System.Logger.Level.WARNING, "SDK error: " + e.code());
    notifyListeners(l -> l.onError(e));
  }

  private boolean notifyListeners(Consumer<BotListener> call) {
    try {
      callbacks.execute(
          () -> {
            for (BotListener l : listeners)
              try {
                call.accept(l);
              } catch (RuntimeException e) {
                safeLog(System.Logger.Level.WARNING, "Listener threw an exception");
              }
          });
      return true;
    } catch (RejectedExecutionException e) {
      safeLog(System.Logger.Level.WARNING, "Callback queue full or closed; callback not delivered");
      return false;
    }
  }

  private <T> void succeed(CompletableFuture<T> f, T value) {
    completions.succeed(f, value);
  }

  private void fail(CompletableFuture<?> f, Throwable e) {
    completions.fail(f, e);
  }

  private static void cancel(Future<?> f) {
    if (f != null) f.cancel(false);
  }

  private static BotException failure(
      BotException.Code code, BotException.Delivery delivery, String text) {
    return new BotException(code, delivery, text);
  }

  private static BotException remote(WsFrame f) {
    return new BotException(
        REMOTE_ERROR,
        REJECTED,
        "Server rejected request (errcode=" + f.errorCode() + ")",
        f.requestId(),
        f.errorCode(),
        null,
        f);
  }

  private static final class Pending {
    final String id, command;
    final ObjectNode body;
    final ReplyContext context;
    final CompletableFuture<Ack> result;
    final long generation, deadline;
    boolean sent;
    ScheduledFuture<?> timeout, queueTimer;

    Pending(
        String command,
        String id,
        ObjectNode body,
        ReplyContext context,
        long generation,
        long deadline,
        CompletableFuture<Ack> result) {
      this.result = result;
      this.command = command;
      this.id = id;
      this.body = body;
      this.context = context;
      this.generation = generation;
      this.deadline = deadline;
    }
  }

  private void control(String command, String id, ObjectNode body, long g) {
    wireQueue.add(new Pending(command, id, body, null, g, Long.MAX_VALUE, null));
    pump();
  }

  private void pump() {
    if (pumping) return;
    pumping = true;
    try {
      // sendText may complete inline; nested completion callbacks must not grow the call stack.
      while (!writing && socket != null && running) {
        Pending p = wireQueue.poll();
        if (p == null) return;
        if (p.generation != generation) continue;
        boolean tracked = queues.containsKey(p.id) && queues.get(p.id).peek() == p;
        if (tracked && System.nanoTime() - p.deadline >= 0) {
          expireQueued(p);
          continue;
        }
        ObjectNode frame = Json.object().put("cmd", p.command);
        frame.putObject("headers").put("req_id", p.id);
        if (p.body != null) frame.set("body", p.body);
        writing = true;
        p.sent = true;
        cancel(p.queueTimer);
        if (tracked)
          p.timeout =
              timers.schedule(
                  () -> {
                    synchronized (lock) {
                      if (isHead(p)) {
                        poison(
                            p,
                            failure(
                                ACK_TIMEOUT,
                                UNKNOWN,
                                "Acknowledgement timed out; delivery unknown"));
                      }
                    }
                  },
                  options.ackTimeout.toMillis(),
                  TimeUnit.MILLISECONDS);
        try {
          socket
              .sendText(frame.toString(), true)
              .whenComplete(
                  (ws, error) -> {
                    synchronized (lock) {
                      if (p.generation != generation) return;
                      writing = false;
                      if (error != null) {
                        lost(
                            p.generation,
                            false,
                            failure(TRANSPORT, UNKNOWN, "WebSocket send failed; delivery unknown"));
                        return;
                      }
                      pump();
                    }
                  });
        } catch (RuntimeException e) {
          writing = false;
          lost(
              p.generation,
              false,
              failure(TRANSPORT, UNKNOWN, "WebSocket send failed; delivery unknown"));
        }
      }
    } finally {
      pumping = false;
    }
  }

  private boolean isHead(Pending p) {
    return generation == p.generation && queues.containsKey(p.id) && queues.get(p.id).peek() == p;
  }

  private void startHead(String id) {
    var q = queues.get(id);
    if (q != null && !q.isEmpty()) {
      Pending p = q.peek();
      if (!p.sent && !wireQueue.contains(p)) {
        wireQueue.add(p);
        pump();
      }
    }
  }

  private void remove(Pending p) {
    cancel(p.timeout);
    cancel(p.queueTimer);
    wireQueue.remove(p);
    var q = queues.get(p.id);
    if (q != null && q.remove(p)) {
      outstanding--;
      if (q.isEmpty()) queues.remove(p.id);
    }
  }

  private void expireQueued(Pending p) {
    if (p.sent) return;
    boolean head = isHead(p);
    remove(p);
    fail(
        p.result,
        new BotException(
            QUEUE_TIMEOUT, NOT_SENT, "Message expired before transmission", p.id, null, null));
    if (head) startHead(p.id);
  }

  private void poison(Pending p, BotException reason) {
    poisoned.add(p.id);
    var q = queues.get(p.id);
    if (q != null)
      for (Pending item : new ArrayList<>(q)) {
        if (item.context != null) item.context.uncertain = true;
        remove(item);
        fail(
            item.result,
            new BotException(
                reason.code(),
                item.sent ? UNKNOWN : NOT_SENT,
                reason.getMessage(),
                item.id,
                null,
                null));
      }
    if (poisoned.size() >= options.maxOutstanding)
      lost(
          generation,
          false,
          failure(
              ACK_TIMEOUT, UNKNOWN, "Too many uncertain requests; reconnecting without replay"));
  }

  private BotException validate(ReplyContext c) {
    if (closed) return failure(CLOSED, NOT_SENT, "Client closed");
    if (state != ConnectionState.READY)
      return failure(NOT_CONNECTED, NOT_SENT, "Client not authenticated");
    if (c != null && (c.owner != this || c.generation != generation))
      return failure(STALE_CONTEXT, NOT_SENT, "Reply context belongs to another connection");
    if (c != null && (c.uncertain || poisoned.contains(c.requestId)))
      return failure(
          ACK_TIMEOUT, NOT_SENT, "Reply context has uncertain delivery and cannot be reused");
    return null;
  }

  private CompletableFuture<Ack> submit(
      String cmd, String id, ObjectNode body, ReplyContext context, long deadline) {
    Objects.requireNonNull(body, "body");
    synchronized (lock) {
      BotException invalid = validate(context);
      if (invalid != null) return CompletableFuture.failedFuture(invalid);
      var q = queues.get(id);
      if (outstanding >= options.maxOutstanding
          || (q != null && q.size() >= options.maxQueuePerRequest))
        return CompletableFuture.failedFuture(
            failure(QUEUE_FULL, NOT_SENT, "Outbound queue capacity reached"));
      CompletableFuture<Ack> result;
      try {
        result = completions.newFuture();
      } catch (BotException full) {
        return CompletableFuture.failedFuture(full);
      }
      long now = System.nanoTime();
      long remaining = options.queueTimeout.toNanos();
      if (deadline != Long.MAX_VALUE) remaining = Math.min(remaining, deadline - now);
      Pending p =
          new Pending(cmd, id, body.deepCopy(), context, generation, now + remaining, result);
      queues.computeIfAbsent(id, k -> new ArrayDeque<>()).add(p);
      outstanding++;
      p.queueTimer =
          timers.schedule(
              () -> {
                synchronized (lock) {
                  if (generation == p.generation) expireQueued(p);
                }
              },
              Math.max(0, p.deadline - System.nanoTime()),
              TimeUnit.NANOSECONDS);
      startHead(id);
      return p.result.copy();
    }
  }
  /** Advanced protocol extension; caller supplies a valid ordinary reply body. */
  public CompletableFuture<Ack> replyRaw(ReplyContext c, ObjectNode body) {
    Objects.requireNonNull(c);
    Objects.requireNonNull(body);
    if ("stream_with_template_card".equals(body.path("msgtype").asText())
        || body.path("stream").has("msg_item"))
      return CompletableFuture.failedFuture(unsupportedStreamFeatures());
    return submit("aibot_respond_msg", c.requestId, body, c, Long.MAX_VALUE);
  }

  public CompletableFuture<Ack> replyText(ReplyContext c, String text) {
    return replyStream(c, new StreamContent(newId(), text, true));
  }

  public CompletableFuture<Ack> replyMarkdown(ReplyContext c, String markdown) {
    return replyText(c, markdown);
  }

  public CompletableFuture<Ack> replyStream(ReplyContext c, StreamContent stream) {
    var b = Json.object().put("msgtype", "stream");
    b.set("stream", stream.toJson());
    return replyRaw(c, b);
  }

  /** @deprecated Long connections do not support stream + template card combination messages. */
  @Deprecated(since = "0.1.0", forRemoval = true)
  public CompletableFuture<Ack> replyStreamWithCard(
      ReplyContext c, StreamContent stream, TemplateCard card) {
    return CompletableFuture.failedFuture(unsupportedStreamFeatures());
  }

  static BotException unsupportedStreamFeatures() {
    return failure(
        UNSUPPORTED,
        NOT_SENT,
        "Long connections do not support stream.msg_item or stream_with_template_card; send media"
            + " or cards separately");
  }

  CompletableFuture<Ack> replyStreamBefore(ReplyContext c, StreamContent stream, long deadline) {
    if (!stream.images().isEmpty())
      return CompletableFuture.failedFuture(unsupportedStreamFeatures());
    var body = Json.object().put("msgtype", "stream");
    body.set("stream", stream.toJson());
    return submit("aibot_respond_msg", c.requestId, body, c, deadline);
  }

  public CompletableFuture<Ack> replyCard(ReplyContext c, TemplateCard card) {
    return replyRaw(c, cardBody(card));
  }

  public CompletableFuture<Ack> replyMedia(ReplyContext c, MediaMessage media) {
    return replyRaw(c, media.toJson());
  }

  public CompletableFuture<Ack> replyWelcomeText(ReplyContext c, String text) {
    var b = Json.object().put("msgtype", "text");
    b.putObject("text").put("content", Objects.requireNonNull(text));
    return eventReply(c, "enter_chat", "aibot_respond_welcome_msg", b);
  }

  public CompletableFuture<Ack> replyWelcomeCard(ReplyContext c, TemplateCard card) {
    return eventReply(c, "enter_chat", "aibot_respond_welcome_msg", cardBody(card));
  }

  public CompletableFuture<Ack> updateCard(
      ReplyContext c, TemplateCard card, List<String> userIds) {
    if (c.taskId == null || !c.taskId.equals(card.taskId()))
      throw new IllegalArgumentException("Card task_id must match callback");
    var b = Json.object().put("response_type", "update_template_card");
    b.set("template_card", card.toJson());
    if (userIds != null && !userIds.isEmpty())
      b.set("userids", Json.MAPPER.valueToTree(List.copyOf(userIds)));
    return eventReply(c, "template_card_event", "aibot_respond_update_msg", b);
  }

  private CompletableFuture<Ack> eventReply(
      ReplyContext c, String event, String cmd, ObjectNode body) {
    if (!event.equals(c.eventType))
      throw new IllegalArgumentException("Expected " + event + " callback");
    return submit(cmd, c.requestId, body, c, c.receivedNanos + Duration.ofSeconds(5).toNanos());
  }

  public CompletableFuture<Ack> sendMarkdown(String chatId, String markdown) {
    var b = Json.object().put("msgtype", "markdown");
    b.putObject("markdown").put("content", Objects.requireNonNull(markdown));
    return send(chatId, b);
  }

  public CompletableFuture<Ack> sendCard(String chatId, TemplateCard card) {
    return send(chatId, cardBody(card));
  }

  public CompletableFuture<Ack> sendMedia(String chatId, MediaMessage media) {
    return send(chatId, media.toJson());
  }

  private CompletableFuture<Ack> send(String chatId, ObjectNode body) {
    body.put("chatid", Json.required(chatId, "chatId"));
    return submit("aibot_send_msg", newId(), body, null, Long.MAX_VALUE);
  }

  private static ObjectNode cardBody(TemplateCard card) {
    var b = Json.object().put("msgtype", "template_card");
    b.set("template_card", card.toJson());
    return b;
  }

  public boolean hasPendingReplyAck(ReplyContext c) {
    synchronized (lock) {
      var q = queues.get(c.requestId);
      return c.owner == this && c.generation == generation && q != null && !q.isEmpty();
    }
  }
  /**
   * Empty Optional means an intermediate update was skipped; final updates always use the bounded
   * queue.
   */
  public CompletableFuture<Optional<Ack>> replyStreamNonBlocking(
      ReplyContext c, StreamContent stream) {
    synchronized (lock) {
      BotException invalid = validate(c);
      if (invalid != null) return CompletableFuture.failedFuture(invalid);
      if (!stream.finish() && hasPendingReplyAck(c))
        return CompletableFuture.completedFuture(Optional.empty());
      return replyStream(c, stream).thenApply(Optional::of);
    }
  }

  public StreamSession stream(ReplyContext c) {
    return new StreamSession(this, c, newId());
  }

  /** @deprecated Long connections do not support combination messages. */
  @Deprecated(since = "0.1.0", forRemoval = true)
  public StreamSession streamWithCard(ReplyContext c) {
    throw unsupportedStreamFeatures();
  }

  public CompletableFuture<MediaUpload> uploadMedia(byte[] data, MediaType type, String filename) {
    if (!uploadSlots.tryAcquire())
      return CompletableFuture.failedFuture(
          failure(QUEUE_FULL, NOT_SENT, "Concurrent media upload capacity reached"));
    try {
      var result =
          MediaUpload.upload(
              this,
              data,
              type,
              filename,
              options.uploadChunkRetries,
              options.uploadRetryDelay.toMillis(),
              options.uploadMaxConcurrency);
      result.whenComplete((r, e) -> uploadSlots.release());
      return result.copy();
    } catch (RuntimeException e) {
      uploadSlots.release();
      throw e;
    }
  }

  long currentGeneration() {
    synchronized (lock) {
      BotException e = validate(null);
      if (e != null) throw e;
      return generation;
    }
  }

  CompletableFuture<Ack> uploadRequest(long expected, String cmd, ObjectNode body) {
    synchronized (lock) {
      if (expected != generation)
        return CompletableFuture.failedFuture(
            failure(STALE_CONTEXT, NOT_SENT, "Upload connection changed"));
      return submit(cmd, newId(), body, null, Long.MAX_VALUE);
    }
  }

  CompletableFuture<Void> delay(long expected, long millis) {
    synchronized (lock) {
      if (expected != generation || closed)
        return CompletableFuture.failedFuture(
            failure(STALE_CONTEXT, NOT_SENT, "Upload connection changed"));
      CompletableFuture<Void> f;
      try {
        f = completions.newFuture();
      } catch (BotException full) {
        return CompletableFuture.failedFuture(full);
      }
      ScheduledFuture<?> timer =
          timers.schedule(
              () -> {
                synchronized (lock) {
                  uploadDelays.remove(f);
                  if (expected != generation || closed)
                    fail(f, failure(STALE_CONTEXT, NOT_SENT, "Upload connection changed"));
                  else succeed(f, null);
                }
              },
              millis,
              TimeUnit.MILLISECONDS);
      uploadDelays.put(f, timer);
      return f;
    }
  }

  private final class SocketListener implements WebSocket.Listener {
    private final long g;
    private final StringBuilder buffer = new StringBuilder();

    SocketListener(long g) {
      this.g = g;
    }

    @Override
    public void onOpen(WebSocket ws) {
      opened(g, ws);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      synchronized (lock) {
        if (g != generation || !running) {
          ws.abort();
          return null;
        }
      }
      if (buffer.length() + data.length() > options.maxFrameChars) {
        synchronized (lock) {
          lost(g, false, failure(PROTOCOL, NOT_SENT, "Incoming frame exceeds size limit"));
        }
        return null;
      }
      buffer.append(data);
      if (last) {
        String text = buffer.toString();
        buffer.setLength(0);
        receive(g, text);
      }
      ws.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
      synchronized (lock) {
        lost(g, false, failure(PROTOCOL, NOT_SENT, "Binary protocol frames unsupported"));
      }
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
      synchronized (lock) {
        lost(g, false, failure(TRANSPORT, UNKNOWN, "WebSocket closed (" + status + ")"));
      }
      return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable e) {
      synchronized (lock) {
        lost(g, false, failure(TRANSPORT, UNKNOWN, "WebSocket transport failure"));
      }
    }
  }
}
