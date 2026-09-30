package io.github.moment.wecom.aibot;

import static io.github.moment.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ResourceLifecycleTest {
  private static long sdkThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.isAlive() && t.getName().startsWith("wecom-"))
        .count();
  }

  @Test
  void repeatedConnectionBatchesReleaseSdkThreads() throws Exception {
    long before = sdkThreads();
    try (var server = new LocalWebSocketServer()) {
      for (int run = 0; run < 12; run++) {
        try (var client =
            new WecomAiBotClient(options(server).ackTimeout(Duration.ofSeconds(5)).build())) {
          client.connect().get(5, TimeUnit.SECONDS);
          List<CompletableFuture<Ack>> batch = new ArrayList<>();
          for (int i = 0; i < 50; i++)
            batch.add(client.sendMarkdown("test", "batch-" + run + "-" + i));
          CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        }
      }
    }
    await(() -> sdkThreads() <= before);
  }
}
