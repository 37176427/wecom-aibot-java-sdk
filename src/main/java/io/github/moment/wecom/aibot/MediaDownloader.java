package io.github.moment.wecom.aibot;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Bounded HTTP downloads; URLs come from callbacks and are never logged. */
public final class MediaDownloader implements AutoCloseable {
  private final HttpClient http;
  private final Duration timeout;
  private final long maxBytes;
  private final Semaphore slots;
  private final Set<Transfer> active = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  public MediaDownloader(HttpClient http, Duration timeout, long maxBytes, int maxConcurrent) {
    this.http = java.util.Objects.requireNonNull(http);
    this.timeout = java.util.Objects.requireNonNull(timeout);
    if (timeout.toMillis() < 1
        || maxBytes < 1
        || maxBytes > Integer.MAX_VALUE - 32L
        || maxConcurrent < 1) throw new IllegalArgumentException("Invalid download limits");
    this.maxBytes = maxBytes;
    slots = new Semaphore(maxConcurrent);
  }

  public static final class Download {
    private final byte[] bytes;
    private final String filename;

    Download(byte[] bytes, String filename) {
      this.bytes = bytes.clone();
      this.filename = filename;
    }

    public byte[] bytes() {
      return bytes.clone();
    }
    /** Untrusted server metadata; never use as an unchecked filesystem path. */
    public String filename() {
      return filename;
    }
  }

  public CompletableFuture<Download> download(URI uri, String aesKey) {
    if (!Set.of("https", "http").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null)
      throw new IllegalArgumentException("Expected HTTP(S) download URI without credentials");
    if (aesKey != null) WecomCrypto.decodeKey(aesKey);
    if (closed) return CompletableFuture.failedFuture(error("Downloader closed"));
    if (!slots.tryAcquire())
      return CompletableFuture.failedFuture(
          new BotException(
              BotException.Code.QUEUE_FULL,
              BotException.Delivery.NOT_SENT,
              "Download concurrency limit reached"));
    Transfer transfer = new Transfer();
    active.add(transfer);
    if (closed) transfer.abort();
    CompletableFuture<HttpResponse<byte[]>> request;
    try {
      if (transfer.aborted) throw new IllegalStateException("Downloader closed");
      request =
          http.sendAsync(
              HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
              info -> {
                LimitedBody body = new LimitedBody(maxBytes);
                transfer.subscriber.set(body);
                if (transfer.aborted) body.abort();
                return body;
              });
      transfer.request = request;
      if (transfer.aborted) request.cancel(true);
    } catch (RuntimeException e) {
      active.remove(transfer);
      slots.release();
      return CompletableFuture.failedFuture(error("Download could not start"));
    }
    // Keep the transport future cancellable when the separate total deadline expires.
    CompletableFuture<Download> result =
        request
            .copy()
            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .handle(
                (response, e) -> {
                  try {
                    if (e != null) {
                      transfer.abort();
                      throw error("Download failed or timed out");
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                      throw error("HTTP download status " + response.statusCode());
                    byte[] raw = response.body();
                    return new Download(
                        aesKey == null ? raw : WecomCrypto.decryptFile(raw, aesKey),
                        filename(response.headers().firstValue("Content-Disposition").orElse("")));
                  } finally {
                    active.remove(transfer);
                    slots.release();
                  }
                });
    return result.copy();
  }

  private static final class Transfer {
    final AtomicReference<LimitedBody> subscriber = new AtomicReference<>();
    volatile CompletableFuture<?> request;
    volatile boolean aborted;

    void abort() {
      aborted = true;
      LimitedBody body = subscriber.get();
      if (body != null) body.abort();
      CompletableFuture<?> pending = request;
      if (pending != null) pending.cancel(true);
    }
  }

  private static BotException error(String message) {
    return new BotException(BotException.Code.DOWNLOAD, BotException.Delivery.NOT_SENT, message);
  }

  private static String filename(String header) {
    var encoded =
        Pattern.compile("filename\\*=UTF-8''([^;\\s]+)", Pattern.CASE_INSENSITIVE).matcher(header);
    if (encoded.find())
      try {
        return URLDecoder.decode(encoded.group(1).replace("+", "%2B"), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException ignored) {
        return null;
      }
    var plain =
        Pattern.compile("filename=\"([^\"]*)\"|filename=([^;\\s]+)", Pattern.CASE_INSENSITIVE)
            .matcher(header);
    if (!plain.find()) return null;
    String name = plain.group(1) != null ? plain.group(1) : plain.group(2);
    try {
      return URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ignored) {
      return name;
    }
  }

  @Override
  public void close() {
    closed = true;
    for (var transfer : active) transfer.abort();
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final long limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private volatile Flow.Subscription subscription;
    private volatile boolean aborted;

    LimitedBody(long limit) {
      this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      if (aborted) s.cancel();
      else s.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      if (aborted) return;
      for (var item : items) {
        if (bytes.size() + (long) item.remaining() > limit) {
          abort();
          return;
        }
        byte[] b = new byte[item.remaining()];
        item.get(b);
        bytes.writeBytes(b);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable e) {
      result.completeExceptionally(e);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }

    void abort() {
      aborted = true;
      Flow.Subscription s = subscription;
      if (s != null) s.cancel();
      result.completeExceptionally(error("Download aborted or exceeds size limit"));
    }
  }
}
