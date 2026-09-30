package io.github.moment.wecom.aibot;

import static io.github.moment.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MessageApiTest {
  @Test
  void allCardTypesMediaAndWelcomeCommands() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      Events events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      var p = server.peers.getFirst();
      p.callback("message", "{\"msgtype\":\"text\"}");
      var context = events.message().context();
      for (var type : TemplateCard.Type.values()) {
        TemplateCard card =
            TemplateCard.builder(type)
                .mainTitle(new TemplateCard.Title("标题", "说明"))
                .taskId("task")
                .feedback("feedback")
                .build();
        client.replyCard(context, card).get(5, TimeUnit.SECONDS);
        var reply = server.next("aibot_respond_msg");
        assertEquals(
            type.name().toLowerCase(), reply.frame().at("/body/template_card/card_type").asText());
        assertEquals("feedback", reply.frame().at("/body/template_card/feedback/id").asText());
        client.sendCard("chat", card).get(5, TimeUnit.SECONDS);
        assertEquals("chat", server.next("aibot_send_msg").frame().at("/body/chatid").asText());
      }
      for (var type : MediaType.values()) {
        var media =
            type == MediaType.VIDEO
                ? new MediaMessage(type, "media", "title", "description")
                : new MediaMessage(type, "media");
        client.replyMedia(context, media).get(5, TimeUnit.SECONDS);
        assertEquals(
            "media",
            server
                .next("aibot_respond_msg")
                .frame()
                .at("/body/" + type.wireName() + "/media_id")
                .asText());
        client.sendMedia("chat", media).get(5, TimeUnit.SECONDS);
        assertEquals(
            type.wireName(), server.next("aibot_send_msg").frame().at("/body/msgtype").asText());
      }
      p.event("welcome", "{\"eventtype\":\"enter_chat\"}");
      var welcome = events.event().context();
      client.replyWelcomeText(welcome, "你好").get(5, TimeUnit.SECONDS);
      assertEquals(
          "text", server.next("aibot_respond_welcome_msg").frame().at("/body/msgtype").asText());
      var card = TemplateCard.builder(TemplateCard.Type.TEXT_NOTICE).taskId("task").build();
      client.replyWelcomeCard(welcome, card).get(5, TimeUnit.SECONDS);
      assertEquals(
          "template_card",
          server.next("aibot_respond_welcome_msg").frame().at("/body/msgtype").asText());
      p.event("update", "{\"eventtype\":\"template_card_event\",\"task_id\":\"task\"}");
      var update = events.event().context();
      client.updateCard(update, card, List.of("user")).get(5, TimeUnit.SECONDS);
      var body = server.next("aibot_respond_update_msg").frame().path("body");
      assertEquals("update_template_card", body.path("response_type").asText());
      assertEquals("user", body.at("/userids/0").asText());
      assertThrows(
          IllegalArgumentException.class, () -> client.replyWelcomeText(context, "invalid"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              client.updateCard(
                  update,
                  TemplateCard.builder(TemplateCard.Type.TEXT_NOTICE).taskId("other").build(),
                  null));
    }
  }

  @Test
  void streamsEnforceFeedbackAndFinalState() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      var events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      server.peers.getFirst().callback("stream", "{\"msgtype\":\"text\"}");
      var stream = client.stream(events.message().context());
      stream.send("开始", false, "f").get(5, TimeUnit.SECONDS);
      var first = server.next("aibot_respond_msg").frame();
      assertEquals("stream", first.at("/body/msgtype").asText());
      assertEquals("f", first.at("/body/stream/feedback/id").asText());
      assertThrows(IllegalArgumentException.class, () -> stream.send("next", false, "again"));
      stream.finish("完成").get(5, TimeUnit.SECONDS);
      var last = server.next("aibot_respond_msg").frame();
      assertTrue(last.at("/body/stream/finish").asBoolean());
      assertFalse(last.path("body").has("template_card"));
      assertFalse(last.at("/body/stream").has("msg_item"));
      assertEquals(BotException.Code.STREAM_FINISHED, failure(stream.update("late")).code());
    }
  }

  @Test
  @SuppressWarnings("removal")
  void unsupportedLongConnectionModesFailBeforeSendingIncludingRawApi() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      var events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      server.next("aibot_subscribe");
      server.peers.getFirst().callback("stream", "{\"msgtype\":\"text\"}");
      var c = events.message().context();
      var image = StreamContent.InlineImage.of(new byte[] {1, 2, 3});
      var body = new StreamContent("s", "text", true, List.of(image), null);
      assertEquals(BotException.Code.UNSUPPORTED, failure(client.replyStream(c, body)).code());
      assertEquals(
          BotException.Code.UNSUPPORTED,
          failure(client.replyStreamWithCard(c, new StreamContent("s", "text", true), null))
              .code());
      assertEquals(
          BotException.Code.UNSUPPORTED,
          assertThrows(BotException.class, () -> client.streamWithCard(c)).code());
      var raw = Json.object().put("msgtype", "stream");
      raw.set("stream", body.toJson());
      assertEquals(BotException.Delivery.NOT_SENT, failure(client.replyRaw(c, raw)).delivery());
      assertEquals(
          BotException.Code.UNSUPPORTED,
          failure(client.replyRaw(c, Json.object().put("msgtype", "stream_with_template_card")))
              .code());
      assertTrue(server.received.isEmpty());
    }
  }

  @Test
  void managedStreamStopsAtTenMinuteProtocolWindow() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      var events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      server.peers.getFirst().callback("stream", "{\"msgtype\":\"text\"}");
      var c = events.message().context();
      var clock = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
      var stream = new StreamSession(client, c, "s", clock::get);
      stream.update("begin").get(5, TimeUnit.SECONDS);
      server.next("aibot_respond_msg");
      clock.addAndGet(java.time.Duration.ofMinutes(10).toNanos());
      assertEquals(BotException.Code.STREAM_EXPIRED, failure(stream.finish("too late")).code());
      assertTrue(server.received.isEmpty());
    }
  }

  @Test
  void nonBlockingIntermediateSkipsButFinalQueues() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client =
            new WecomAiBotClient(
                options(server).ackTimeout(java.time.Duration.ofSeconds(3)).build())) {
      Events events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      server.handler = (p, n) -> {};
      server.peers.getFirst().callback("r", "{\"msgtype\":\"text\"}");
      var c = events.message().context();
      var first = client.replyStreamNonBlocking(c, new StreamContent("s", "a", false));
      var sent = server.next("aibot_respond_msg");
      assertTrue(
          client.replyStreamNonBlocking(c, new StreamContent("s", "b", false)).join().isEmpty());
      var last = client.replyStreamNonBlocking(c, new StreamContent("s", "c", true));
      assertFalse(last.isDone());
      sent.peer().ack(sent.frame(), 0, null);
      first.get(5, TimeUnit.SECONDS);
      var end = server.next("aibot_respond_msg");
      end.peer().ack(end.frame(), 0, null);
      assertTrue(last.get(5, TimeUnit.SECONDS).isPresent());
    }
  }

  @Test
  void queueAndEventDeadlinePreventUnsentFrames() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client =
            new WecomAiBotClient(
                options(server)
                    .ackTimeout(java.time.Duration.ofSeconds(2))
                    .queueTimeout(java.time.Duration.ofMillis(70))
                    .maxOutstanding(2)
                    .build())) {
      client.connect().get(5, TimeUnit.SECONDS);
      server.handler = (p, n) -> {};
      Events events = new Events();
      client.addListener(events);
      server.peers.getFirst().callback("q", "{\"msgtype\":\"text\"}");
      var c = events.message().context();
      client.replyText(c, "first");
      var second = client.replyText(c, "queued");
      assertEquals(
          BotException.Code.QUEUE_FULL,
          failure(client.sendMarkdown("chat", "over global limit")).code());
      assertEquals(BotException.Code.QUEUE_TIMEOUT, failure(second).code());
    }
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build())) {
      Events events = new Events();
      client.addListener(events);
      client.connect().get(5, TimeUnit.SECONDS);
      server.peers.getFirst().event("late", "{\"eventtype\":\"enter_chat\"}");
      var c = events.event().context();
      Thread.sleep(5050);
      assertEquals(
          BotException.Code.QUEUE_TIMEOUT, failure(client.replyWelcomeText(c, "too late")).code());
      assertTrue(
          server.received.stream()
              .noneMatch(r -> r.frame().path("cmd").asText().equals("aibot_respond_welcome_msg")));
    }
  }

  @Test
  void validateUtf8AndCardFieldsAreDefensiveCopies() {
    assertThrows(
        IllegalArgumentException.class, () -> new StreamContent("s", "中".repeat(7000), true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StreamContent(
                "s", "", false, List.of(StreamContent.InlineImage.of(new byte[] {1})), null));
    var card =
        TemplateCard.builder(TemplateCard.Type.MULTIPLE_INTERACTION)
            .selections(
                List.of(
                    new TemplateCard.Selection(
                        "q", "选择", false, "a", List.of(new TemplateCard.Option("a", "甲", null)))))
            .submitButton(new TemplateCard.SubmitButton("确定", "submit"))
            .build();
    var json = card.toJson();
    assertEquals("q", json.at("/select_list/0/question_key").asText());
    assertFalse(json.at("/select_list/0/option_list/0").has("is_checked"));
    json.put("card_type", "changed");
    assertEquals("multiple_interaction", card.toJson().path("card_type").asText());
  }
}
