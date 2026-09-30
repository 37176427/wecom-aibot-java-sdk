package io.github.user37176427.wecom.aibot;

import static io.github.user37176427.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ReleaseReviewTest {
  @Test
  void errorCodesMustNotOverflowIntoSuccess() throws Exception {
    var frame = new WsFrame(Json.MAPPER.readTree("{\"errcode\":4294967296}"));
    assertNull(frame.errorCode());
  }

  @Test
  void strictParsingRejectsConcatenatedAndDuplicateFrames() {
    assertThrows(Exception.class, () -> Json.MAPPER.readTree("{\"errcode\":0} {\"errcode\":1}"));
    assertThrows(Exception.class, () -> Json.MAPPER.readTree("{\"errcode\":1,\"errcode\":0}"));
  }

  @Test
  void cancellingStreamWaitMustNotSkipItsFailureState() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).completionThreads(1).build())) {
      var events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      server.handler = (p, n) -> {};
      server.peers.getFirst().callback("stream", "{\"msgtype\":\"text\"}");
      var context = events.message().context();
      var stream = client.stream(context);
      var abandoned = stream.update("first");
      var first = server.next("aibot_respond_msg");
      abandoned.cancel(false);
      first.peer().ack(first.frame(), 45009, null);
      await(() -> !client.hasPendingReplyAck(context));
      // A completion queued after the stream ACK provides an executor barrier.
      var barrier = client.sendMarkdown("test", "barrier");
      var sent = server.next("aibot_send_msg");
      sent.peer().ack(sent.frame(), 0, null);
      barrier.get(5, TimeUnit.SECONDS);
      assertEquals(BotException.Code.STREAM_FINISHED, failure(stream.update("late")).code());
    }
  }
}
