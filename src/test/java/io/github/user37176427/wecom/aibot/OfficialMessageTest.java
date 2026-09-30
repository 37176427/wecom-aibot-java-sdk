package io.github.user37176427.wecom.aibot;

import static io.github.user37176427.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OfficialMessageTest {
  @Test
  void outboundBodiesMatchOfficialNodeClientFixtures() throws Exception {
    var vectors =
        Json.MAPPER
            .readTree(getClass().getResourceAsStream("/official-message-vectors.json"))
            .path("frames");
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      var events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      var p = server.peers.getFirst();
      p.callback("callback", "{\"msgtype\":\"text\"}");
      var c = events.message().context();
      var card =
          TemplateCard.fromJson(
              (com.fasterxml.jackson.databind.node.ObjectNode)
                  vectors.get(3).path("body").path("template_card"));
      client
          .replyStream(c, new StreamContent("stream", "final", true, "feedback"))
          .get(5, TimeUnit.SECONDS);
      check(server, vectors.get(0));
      assertEquals(
          BotException.Code.UNSUPPORTED,
          failure(
                  client.replyRaw(
                      c,
                      (com.fasterxml.jackson.databind.node.ObjectNode) vectors.get(1).path("body")))
              .code());
      p.event("welcome", "{\"eventtype\":\"enter_chat\"}");
      client.replyWelcomeText(events.event().context(), "welcome").get(5, TimeUnit.SECONDS);
      check(server, vectors.get(2));
      p.event("update", "{\"eventtype\":\"template_card_event\",\"task_id\":\"task\"}");
      client.updateCard(events.event().context(), card, List.of("tester")).get(5, TimeUnit.SECONDS);
      check(server, vectors.get(3));
      client
          .replyMedia(c, new MediaMessage(MediaType.VIDEO, "media", "video", "test"))
          .get(5, TimeUnit.SECONDS);
      check(server, vectors.get(4));
      client.sendMedia("chat", new MediaMessage(MediaType.FILE, "media")).get(5, TimeUnit.SECONDS);
      check(server, vectors.get(5));
      client.sendMarkdown("chat", "notice").get(5, TimeUnit.SECONDS);
      check(server, vectors.get(6));
      var feedback = card.toJson();
      feedback.putObject("feedback").put("id", "feedback");
      client.replyCard(c, TemplateCard.fromJson(feedback)).get(5, TimeUnit.SECONDS);
      check(server, vectors.get(7));
    }
  }

  private static void check(
      LocalWebSocketServer server, com.fasterxml.jackson.databind.JsonNode expected)
      throws Exception {
    var actual = server.next(expected.path("cmd").asText()).frame();
    assertEquals(expected.path("body"), actual.path("body"));
  }
}
