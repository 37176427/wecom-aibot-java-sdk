package io.github.user37176427.wecom.aibot;

import static io.github.user37176427.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DownloadLifecycleTest {
  @Test
  void timeoutCancelsTransportAndLateBodySubscription() throws Exception {
    var http = new DeferredHttp();
    try (var downloader = new MediaDownloader(http, Duration.ofMillis(50), 1024, 1)) {
      assertEquals(
          BotException.Code.DOWNLOAD,
          failure(downloader.download(URI.create("http://localhost/file"), null)).code());
      assertTrue(http.request.isCancelled(), "Timeout must cancel underlying HTTP exchange");
      assertLateSubscriptionCancelled(http);
    }
  }

  @Test
  void closeCancelsLateBodyAndDoesNotCloseHostClient() throws Exception {
    var http = new DeferredHttp();
    var downloader = new MediaDownloader(http, Duration.ofSeconds(5), 1024, 1);
    var download = downloader.download(URI.create("http://localhost/file"), null);
    downloader.close();
    failure(download);
    assertLateSubscriptionCancelled(http);
    assertFalse(http.closed);
  }

  @Test
  void cancellingPublicFutureDoesNotLeakDownloadCapacity() throws Exception {
    var http = new DeferredHttp();
    try (var downloader = new MediaDownloader(http, Duration.ofSeconds(5), 1024, 1)) {
      var abandoned = downloader.download(URI.create("http://localhost/file"), null);
      abandoned.cancel(false);
      http.request.complete(
          new HttpResponse<byte[]>() {
            public int statusCode() {
              return 200;
            }

            public HttpRequest request() {
              return HttpRequest.newBuilder(URI.create("http://localhost/file")).build();
            }

            public java.util.Optional<HttpResponse<byte[]>> previousResponse() {
              return java.util.Optional.empty();
            }

            public HttpHeaders headers() {
              return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            public byte[] body() {
              return new byte[] {1, 2, 3};
            }

            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
              return java.util.Optional.empty();
            }

            public URI uri() {
              return URI.create("http://localhost/file");
            }

            public HttpClient.Version version() {
              return HttpClient.Version.HTTP_1_1;
            }
          });
      assertArrayEquals(
          new byte[] {1, 2, 3},
          downloader
              .download(URI.create("http://localhost/file"), null)
              .get(5, TimeUnit.SECONDS)
              .bytes());
    }
  }

  private static void assertLateSubscriptionCancelled(DeferredHttp http) {
    var body =
        http.handler.apply(
            new HttpResponse.ResponseInfo() {
              public int statusCode() {
                return 200;
              }

              public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
              }

              public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
              }
            });
    var cancelled = new AtomicBoolean();
    body.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {
            cancelled.set(true);
          }
        });
    assertTrue(cancelled.get(), "A subscription arriving after termination must be cancelled");
  }

  static final class DeferredHttp extends LifecycleRaceTest.ControlledHttp {
    final CompletableFuture<HttpResponse<byte[]>> request = new CompletableFuture<>();
    HttpResponse.BodyHandler<byte[]> handler;

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> h) {
      handler = (HttpResponse.BodyHandler<byte[]>) h;
      return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) request;
    }
  }
}
