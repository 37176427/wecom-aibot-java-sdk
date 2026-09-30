package io.github.user37176427.wecom.aibot;

import static io.github.user37176427.wecom.aibot.ClientIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.user37176427.wecom.aibot.examples.SandboxBot;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SandboxBotTest {
  private static final String NONCE = "local-test-1234567890";

  private static String message(String type, String text, String user, String chat) {
    var b = Json.object().put("msgtype", type).put("chattype", chat == null ? "single" : "group");
    b.putObject("from").put("userid", user);
    if (chat != null) b.put("chatid", chat);
    if (text != null) b.putObject("text").put("content", text);
    return b.toString();
  }

  @Test
  void ignoresUnboundAndRoutesNotificationsOnlyToBoundConversation() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build());
        var sandbox =
            new SandboxBot(
                client, new PrintStream(OutputStream.nullOutputStream()), NONCE, Map.of())) {
      client.addListener(sandbox);
      client.connect().get(5, TimeUnit.SECONDS);
      server.next("aibot_subscribe");
      var p = server.peers.getFirst();
      p.callback("ignored", message("text", "sdk notify", "u", null));
      p.callback("bind", message("text", "sdk bind " + NONCE, "u", "test-chat"));
      assertEquals("bind", server.next("aibot_respond_msg").frame().at("/headers/req_id").asText());
      p.callback("other-user", message("text", "sdk notify", "other", "test-chat"));
      p.callback("other-chat", message("text", "sdk notify", "u", "production-chat"));
      p.callback("allowed", message("text", "sdk notify", "u", "test-chat"));
      assertEquals("test-chat", server.next("aibot_send_msg").frame().at("/body/chatid").asText());
      assertTrue(server.received.isEmpty());
    }
  }

  @Test
  void groupCommandsAcceptMentionPrefixButOtherTextDoesNotAuthorizeSending() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build());
        var sandbox =
            new SandboxBot(
                client, new PrintStream(OutputStream.nullOutputStream()), NONCE, Map.of())) {
      client.addListener(sandbox);
      client.connect().get(5, TimeUnit.SECONDS);
      server.next("aibot_subscribe");
      var peer = server.peers.getFirst();
      peer.callback(
          "wrong", message("text", "@SDK联调专用\u2005sdk bind wrong-token", "u", "test-chat"));
      peer.callback("single-mention", message("text", "@SDK联调专用 sdk bind " + NONCE, "u", null));
      peer.callback("bind", message("text", "@SDK联调专用\u2005sdk bind " + NONCE, "u", "test-chat"));
      assertEquals("bind", server.next("aibot_respond_msg").frame().at("/headers/req_id").asText());
      peer.callback("discussion", message("text", "这不是指令 sdk notify", "u", "test-chat"));
      peer.callback("wrong-user", message("text", "@SDK联调专用 sdk notify", "other", "test-chat"));
      peer.callback("notify", message("text", "@SDK 联调专用\nsdk notify", "u", "test-chat"));
      assertEquals("test-chat", server.next("aibot_send_msg").frame().at("/body/chatid").asText());
      assertTrue(server.received.isEmpty());
    }
  }

  @Test
  void builtinImageAndFilePerformRealUploadProtocol() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build());
        var sandbox =
            new SandboxBot(
                client, new PrintStream(OutputStream.nullOutputStream()), NONCE, Map.of())) {
      server.handler =
          (p, n) -> {
            switch (n.path("cmd").asText()) {
              case "aibot_upload_media_init" -> p.ack(n, 0, Json.object().put("upload_id", "u"));
              case "aibot_upload_media_finish" -> p.ack(n, 0, Json.object().put("media_id", "m"));
              default -> p.ack(n, 0, null);
            }
          };
      client.addListener(sandbox);
      client.connect().get(5, TimeUnit.SECONDS);
      var p = server.peers.getFirst();
      p.callback("bind", message("text", "sdk bind " + NONCE, "u", null));
      server.next("aibot_respond_msg");
      for (String type : List.of("image", "file")) {
        p.callback(type, message("text", "sdk media " + type, "u", null));
        assertEquals(
            type, server.next("aibot_upload_media_init").frame().at("/body/type").asText());
        byte[] bytes =
            Base64.getDecoder()
                .decode(
                    server
                        .next("aibot_upload_media_chunk")
                        .frame()
                        .at("/body/base64_data")
                        .asText());
        if (type.equals("image")) {
          var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
          assertNotNull(image);
          assertEquals(128, image.getWidth());
          assertEquals(64, image.getHeight());
          var buffer = java.nio.ByteBuffer.wrap(bytes);
          buffer.position(8);
          while (buffer.hasRemaining()) {
            int size = buffer.getInt();
            byte[] data = new byte[size + 4];
            buffer.get(data);
            var crc = new java.util.zip.CRC32();
            crc.update(data);
            assertEquals(crc.getValue(), Integer.toUnsignedLong(buffer.getInt()));
          }
        }
        assertEquals(
            "m",
            server.next("aibot_respond_msg").frame().at("/body/" + type + "/media_id").asText());
      }
    }
  }

  @Test
  void cardInteractionUpdatesOriginalTypeAndTaskId() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build());
        var sandbox =
            new SandboxBot(
                client, new PrintStream(OutputStream.nullOutputStream()), NONCE, Map.of())) {
      client.addListener(sandbox);
      client.connect().get(5, TimeUnit.SECONDS);
      var p = server.peers.getFirst();
      p.callback("bind", message("text", "sdk bind " + NONCE, "u", null));
      server.next("aibot_respond_msg");
      p.callback("vote", message("text", "sdk card vote", "u", null));
      String task =
          server.next("aibot_respond_msg").frame().at("/body/template_card/task_id").asText();
      var event = Json.object().put("cmd", "aibot_event_callback");
      event.putObject("headers").put("req_id", "event");
      var b = event.putObject("body").put("msgtype", "event").put("chattype", "single");
      b.putObject("from").put("userid", "u");
      b.putObject("event")
          .put("eventtype", "template_card_event")
          .putObject("template_card_event")
          .put("event_key", "submit")
          .put("task_id", task);
      p.send(event.toString());
      var updated =
          server.next("aibot_respond_update_msg").frame().path("body").path("template_card");
      assertEquals("vote_interaction", updated.path("card_type").asText());
      assertEquals(task, updated.path("task_id").asText());
      assertTrue(updated.path("checkbox").path("disable").asBoolean());
    }
  }

  @Test
  void reconnectCommandPreservesTestBindingAndReauthenticates() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build());
        var sandbox =
            new SandboxBot(
                client, new PrintStream(OutputStream.nullOutputStream()), NONCE, Map.of())) {
      client.addListener(sandbox);
      client.connect().get(5, TimeUnit.SECONDS);
      var old = server.peers.getFirst();
      old.callback("bind", message("text", "sdk bind " + NONCE, "u", null));
      server.next("aibot_respond_msg");
      old.callback("reconnect", message("text", "sdk reconnect", "u", null));
      server.next("aibot_respond_msg");
      var auth = server.next("aibot_subscribe");
      assertNotSame(old, auth.peer());
      await(client::isConnected);
      auth.peer().callback("after", message("text", "sdk text", "u", null));
      assertEquals(
          "after", server.next("aibot_respond_msg").frame().at("/headers/req_id").asText());
    }
  }

  @Test
  void proactiveCardUsesBoundChatAndRegistersItsInteractionTask() throws Exception {
    try (var server = new LocalWebSocketServer();
        var client = new WecomAiBotClient(options(server).build());
        var sandbox =
            new SandboxBot(
                client, new PrintStream(OutputStream.nullOutputStream()), NONCE, Map.of())) {
      client.addListener(sandbox);
      client.connect().get(5, TimeUnit.SECONDS);
      var peer = server.peers.getFirst();
      peer.callback("bind", message("text", "sdk bind " + NONCE, "u", "chat"));
      server.next("aibot_respond_msg");
      peer.callback("push", message("text", "sdk push-card button", "u", "chat"));
      var frame = server.next("aibot_send_msg").frame();
      assertEquals("chat", frame.at("/body/chatid").asText());
      assertEquals("button_interaction", frame.at("/body/template_card/card_type").asText());
      assertFalse(frame.at("/body/template_card/task_id").asText().isBlank());
    }
  }
}
