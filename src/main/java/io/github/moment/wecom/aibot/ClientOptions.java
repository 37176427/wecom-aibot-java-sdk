package io.github.moment.wecom.aibot;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/** Immutable configuration. A supplied HttpClient remains owned by the caller. */
public final class ClientOptions {
  final String botId, secret, pluginVersion;
  final Integer scene;
  final URI endpoint;
  final HttpClient httpClient;
  final java.util.concurrent.Executor completionExecutor;
  final int completionThreads, maxCompletionTasks;
  final int maxUploads, maxCallbackQueueSize, uploadChunkRetries, uploadMaxConcurrency;
  final Duration reconnectMaxDelay, uploadRetryDelay;
  final Duration connectTimeout,
      ackTimeout,
      queueTimeout,
      heartbeatInterval,
      reconnectDelay,
      downloadTimeout;
  final int maxReconnectAttempts,
      maxAuthRetries,
      maxMissedHeartbeats,
      maxQueuePerRequest,
      maxOutstanding,
      maxFrameChars,
      maxDownloads;
  final long maxDownloadBytes;
  final BotLogger logger;

  private ClientOptions(Builder b) {
    botId = Json.required(b.botId, "botId");
    secret = Json.required(b.secret, "secret");
    pluginVersion = b.pluginVersion;
    scene = b.scene;
    endpoint = Objects.requireNonNull(b.endpoint);
    if (!java.util.Set.of("ws", "wss").contains(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null)
      throw new IllegalArgumentException("Invalid WebSocket endpoint");
    httpClient = b.httpClient;
    completionExecutor = b.completionExecutor;
    completionThreads = positive(b.completionThreads);
    maxCompletionTasks = positive(b.maxCompletionTasks);
    connectTimeout = positive(b.connectTimeout);
    ackTimeout = positive(b.ackTimeout);
    queueTimeout = positive(b.queueTimeout);
    heartbeatInterval = positive(b.heartbeatInterval);
    reconnectDelay = positive(b.reconnectDelay);
    downloadTimeout = positive(b.downloadTimeout);
    maxReconnectAttempts = retries(b.maxReconnectAttempts);
    maxAuthRetries = retries(b.maxAuthRetries);
    maxMissedHeartbeats = positive(b.maxMissedHeartbeats);
    maxQueuePerRequest = positive(b.maxQueuePerRequest);
    maxOutstanding = positive(b.maxOutstanding);
    maxFrameChars = positive(b.maxFrameChars);
    maxDownloads = positive(b.maxDownloads);
    maxUploads = positive(b.maxUploads);
    maxCallbackQueueSize = positive(b.maxCallbackQueueSize);
    uploadChunkRetries = b.uploadChunkRetries;
    if (uploadChunkRetries < 0)
      throw new IllegalArgumentException("uploadChunkRetries must be nonnegative");
    uploadMaxConcurrency = positive(b.uploadMaxConcurrency);
    if (uploadMaxConcurrency > 4)
      throw new IllegalArgumentException("uploadMaxConcurrency must be <=4");
    reconnectMaxDelay = positive(b.reconnectMaxDelay);
    if (reconnectMaxDelay.compareTo(reconnectDelay) < 0)
      throw new IllegalArgumentException("reconnectMaxDelay must be >= reconnectDelay");
    uploadRetryDelay = positive(b.uploadRetryDelay);
    if (b.maxDownloadBytes < 1 || b.maxDownloadBytes > Integer.MAX_VALUE - 32L)
      throw new IllegalArgumentException("Invalid download limit");
    maxDownloadBytes = b.maxDownloadBytes;
    logger = Objects.requireNonNull(b.logger);
  }

  private static Duration positive(Duration d) {
    if (d == null) throw new IllegalArgumentException("Duration is required");
    try {
      if (d.toMillis() < 1) throw new IllegalArgumentException("Duration must be >=1ms");
      d.toNanos();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "Duration exceeds the supported nanosecond range", overflow);
    }
    return d;
  }

  private static int positive(int n) {
    if (n < 1) throw new IllegalArgumentException("Limit must be positive");
    return n;
  }

  private static int retries(int n) {
    if (n < -1) throw new IllegalArgumentException("Retries must be -1 or nonnegative");
    return n;
  }

  public static Builder builder(String botId, String secret) {
    return new Builder(botId, secret);
  }

  @Override
  public String toString() {
    return "ClientOptions[credentials=REDACTED]";
  }

  public static final class Builder {
    private final String botId, secret;
    private String pluginVersion;
    private Integer scene;
    private URI endpoint = URI.create("wss://openws.work.weixin.qq.com");
    private HttpClient httpClient;
    private java.util.concurrent.Executor completionExecutor;
    private int completionThreads = 2, maxCompletionTasks = 2048;
    private int maxUploads = 2,
        maxCallbackQueueSize = 2000,
        uploadChunkRetries = 2,
        uploadMaxConcurrency = 4;
    private Duration reconnectMaxDelay = Duration.ofSeconds(30),
        uploadRetryDelay = Duration.ofMillis(500);
    private Duration connectTimeout = Duration.ofSeconds(10),
        ackTimeout = Duration.ofSeconds(5),
        queueTimeout = Duration.ofSeconds(30),
        heartbeatInterval = Duration.ofSeconds(30),
        reconnectDelay = Duration.ofSeconds(1),
        downloadTimeout = Duration.ofSeconds(10);
    private int maxReconnectAttempts = 10,
        maxAuthRetries = 5,
        maxMissedHeartbeats = 2,
        maxQueuePerRequest = 500,
        maxOutstanding = 2000,
        maxFrameChars = 2 * 1024 * 1024,
        maxDownloads = 4;
    private long maxDownloadBytes = 50L * 1024 * 1024;
    private BotLogger logger = BotLogger.system();

    private Builder(String id, String secret) {
      botId = id;
      this.secret = secret;
    }

    public Builder endpoint(URI v) {
      endpoint = v;
      return this;
    }

    public Builder httpClient(HttpClient v) {
      httpClient = v;
      return this;
    }

    /** Caller-owned executor; never shut down by the SDK. Capacity is still enforced by the SDK. */
    public Builder completionExecutor(java.util.concurrent.Executor executor) {
      completionExecutor = Objects.requireNonNull(executor);
      return this;
    }

    /** Threads in the SDK-owned completion pool (default 2). */
    public Builder completionThreads(int threads) {
      completionThreads = threads;
      return this;
    }

    /** In-flight plus queued/running completion tasks (default 2048). */
    public Builder maxCompletionTasks(int capacity) {
      maxCompletionTasks = capacity;
      return this;
    }

    /** Concurrent whole-media uploads per client; default 2. */
    public Builder maxUploads(int value) {
      maxUploads = value;
      return this;
    }

    /** Incoming listener queue capacity, independent of outbound requests; default 2000. */
    public Builder maxCallbackQueueSize(int value) {
      maxCallbackQueueSize = value;
      return this;
    }

    /** Exponential reconnect delay cap, default 30 seconds; must be >= reconnectDelay. */
    public Builder reconnectMaxDelay(Duration value) {
      reconnectMaxDelay = value;
      return this;
    }

    /** Additional attempts for indexed chunks only; default 2, zero disables retries. */
    public Builder uploadChunkRetries(int value) {
      uploadChunkRetries = value;
      return this;
    }

    /** Base delay for linear chunk retry backoff; default 500ms. */
    public Builder uploadRetryDelay(Duration value) {
      uploadRetryDelay = value;
      return this;
    }

    /** Additional cap on the official adaptive chunk concurrency; 1..4, default 4. */
    public Builder uploadMaxConcurrency(int value) {
      uploadMaxConcurrency = value;
      return this;
    }

    public Builder scene(Integer v) {
      scene = v;
      return this;
    }

    public Builder pluginVersion(String v) {
      pluginVersion = v;
      return this;
    }

    public Builder connectTimeout(Duration v) {
      connectTimeout = v;
      return this;
    }

    public Builder ackTimeout(Duration v) {
      ackTimeout = v;
      return this;
    }

    public Builder queueTimeout(Duration v) {
      queueTimeout = v;
      return this;
    }

    public Builder heartbeatInterval(Duration v) {
      heartbeatInterval = v;
      return this;
    }

    public Builder reconnectDelay(Duration v) {
      reconnectDelay = v;
      return this;
    }

    public Builder downloadTimeout(Duration v) {
      downloadTimeout = v;
      return this;
    }

    public Builder maxReconnectAttempts(int v) {
      maxReconnectAttempts = v;
      return this;
    }

    public Builder maxAuthRetries(int v) {
      maxAuthRetries = v;
      return this;
    }

    public Builder maxMissedHeartbeats(int v) {
      maxMissedHeartbeats = v;
      return this;
    }

    public Builder maxQueuePerRequest(int v) {
      maxQueuePerRequest = v;
      return this;
    }

    public Builder maxOutstanding(int v) {
      maxOutstanding = v;
      return this;
    }

    public Builder maxFrameChars(int v) {
      maxFrameChars = v;
      return this;
    }

    public Builder maxDownloads(int v) {
      maxDownloads = v;
      return this;
    }

    public Builder maxDownloadBytes(long v) {
      maxDownloadBytes = v;
      return this;
    }

    public Builder logger(BotLogger v) {
      logger = v;
      return this;
    }

    public ClientOptions build() {
      return new ClientOptions(this);
    }
  }
}
