package io.github.moment.wecom.aibot;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Temporary media identifier (official baseline: valid for three days). */
public record MediaUpload(MediaType type, String mediaId, String createdAt) {
  public static final int CHUNK_BYTES = 512 * 1024;
  public static final int MAX_BYTES = 100 * CHUNK_BYTES;

  static CompletableFuture<MediaUpload> upload(
      WecomAiBotClient client,
      byte[] input,
      MediaType type,
      String filename,
      int retries,
      long retryDelayMillis,
      int maxConcurrency) {
    Objects.requireNonNull(type);
    Json.required(filename, "filename");
    Objects.requireNonNull(input);
    if (input.length == 0 || input.length > MAX_BYTES)
      throw new IllegalArgumentException("Media must be 1..50MiB; server type limits also apply");
    long generation = client.currentGeneration();
    byte[] data = input.clone();
    int chunks = (data.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
    var init =
        Json.object()
            .put("type", type.wireName())
            .put("filename", filename)
            .put("total_size", data.length)
            .put("total_chunks", chunks)
            .put("md5", StreamContent.digest(data));
    return client
        .uploadRequest(generation, "aibot_upload_media_init", init)
        .thenCompose(
            ack -> {
              String uploadId = requiredResult(ack, "upload_id");
              int concurrency =
                  Math.min(maxConcurrency, chunks <= 4 ? chunks : chunks <= 10 ? 3 : 2);
              AtomicInteger next = new AtomicInteger();
              CompletableFuture<?>[] workers = new CompletableFuture<?>[concurrency];
              for (int i = 0; i < concurrency; i++)
                workers[i] =
                    worker(
                        client,
                        generation,
                        uploadId,
                        data,
                        chunks,
                        next,
                        retries,
                        retryDelayMillis);
              return CompletableFuture.allOf(workers)
                  .thenCompose(
                      v ->
                          client.uploadRequest(
                              generation,
                              "aibot_upload_media_finish",
                              Json.object().put("upload_id", uploadId)));
            })
        .thenApply(
            ack ->
                new MediaUpload(
                    type, requiredResult(ack, "media_id"), Json.text(ack.body(), "created_at")));
  }

  private static CompletableFuture<Void> worker(
      WecomAiBotClient client,
      long generation,
      String uploadId,
      byte[] data,
      int chunks,
      AtomicInteger next,
      int retries,
      long retryDelayMillis) {
    int index = next.getAndIncrement();
    if (index >= chunks) return CompletableFuture.completedFuture(null);
    String encoded =
        Base64.getEncoder()
            .encodeToString(
                Arrays.copyOfRange(
                    data, index * CHUNK_BYTES, Math.min(data.length, (index + 1) * CHUNK_BYTES)));
    return chunk(client, generation, uploadId, index, encoded, 0, retries, retryDelayMillis)
        .thenCompose(
            v ->
                worker(
                    client, generation, uploadId, data, chunks, next, retries, retryDelayMillis));
  }

  private static CompletableFuture<Void> chunk(
      WecomAiBotClient client,
      long generation,
      String uploadId,
      int index,
      String encoded,
      int attempt,
      int retries,
      long retryDelayMillis) {
    var body =
        Json.object()
            .put("upload_id", uploadId)
            .put("chunk_index", index)
            .put("base64_data", encoded);
    return client
        .uploadRequest(generation, "aibot_upload_media_chunk", body)
        .<Void>thenApply(a -> null)
        .exceptionallyCompose(
            error -> {
              Throwable e = error;
              while (e instanceof CompletionException && e.getCause() != null) e = e.getCause();
              // Only an indexed chunk is replayable; init, finish and message sends are never
              // retried.
              if (attempt >= retries
                  || !(e instanceof BotException b)
                  || (b.code() != BotException.Code.REMOTE_ERROR
                      && b.code() != BotException.Code.ACK_TIMEOUT))
                return CompletableFuture.failedFuture(e);
              return client
                  .delay(
                      generation,
                      retryDelayMillis > Long.MAX_VALUE / (attempt + 1L)
                          ? Long.MAX_VALUE
                          : retryDelayMillis * (attempt + 1L))
                  .thenCompose(
                      v ->
                          chunk(
                              client,
                              generation,
                              uploadId,
                              index,
                              encoded,
                              attempt + 1,
                              retries,
                              retryDelayMillis));
            });
  }

  private static String requiredResult(Ack ack, String key) {
    String v = Json.text(ack.body(), key);
    if (v == null || v.isBlank())
      throw new BotException(
          BotException.Code.PROTOCOL, BotException.Delivery.UNKNOWN, "Upload ACK missing " + key);
    return v;
  }
}
