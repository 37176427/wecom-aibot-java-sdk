package io.github.user37176427.wecom.aibot.examples;

import io.github.user37176427.wecom.aibot.*;
import java.util.concurrent.CountDownLatch;

/** Compiled by Maven, never automatically executed by tests. Use only a dedicated test bot. */
public final class BasicBot {
  public static void main(String[] args) throws Exception {
    var options =
        ClientOptions.builder(System.getenv("WECOM_BOT_ID"), System.getenv("WECOM_BOT_SECRET"))
            .build();
    try (var client = new WecomAiBotClient(options)) {
      client.addListener(
          new BotListener() {
            @Override
            public void onMessage(IncomingMessage message) {
              if (message.type() != IncomingMessage.Type.TEXT) return;
              var stream = client.stream(message.context());
              stream
                  .update("正在处理…")
                  .thenCompose(ack -> stream.finish("收到：" + message.content().text()))
                  .exceptionally(
                      error -> {
                        System.err.println("回复失败，请按交付状态处理");
                        return null;
                      });
            }

            @Override
            public void onEvent(IncomingEvent event) {
              if (event.type() == IncomingEvent.Type.ENTER_CHAT)
                client.replyWelcomeText(event.context(), "你好，我是智能助手。");
            }

            @Override
            public void onError(BotException error) {
              System.err.println(error.code() + " / " + error.delivery());
            }
          });
      Runtime.getRuntime().addShutdownHook(new Thread(client::close));
      client.connect().join();
      new CountDownLatch(1).await();
    }
  }
}
