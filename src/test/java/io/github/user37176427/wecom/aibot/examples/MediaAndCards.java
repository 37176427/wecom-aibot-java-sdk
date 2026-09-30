package io.github.user37176427.wecom.aibot.examples;

import io.github.user37176427.wecom.aibot.*;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Call these helpers explicitly with test conversations; they are compiled, not run by tests. */
public final class MediaAndCards {
  public static CompletableFuture<Ack> uploadAndReply(
      WecomAiBotClient client,
      IncomingMessage incoming,
      byte[] bytes,
      MediaType type,
      String filename) {
    return client
        .uploadMedia(bytes, type, filename)
        .thenCompose(
            media ->
                client.replyMedia(incoming.context(), new MediaMessage(type, media.mediaId())));
  }

  public static CompletableFuture<Ack> uploadAndNotify(
      WecomAiBotClient client, String chatId, byte[] bytes, MediaType type, String filename) {
    return client
        .uploadMedia(bytes, type, filename)
        .thenCompose(media -> client.sendMedia(chatId, new MediaMessage(type, media.mediaId())));
  }

  public static CompletableFuture<MediaDownloader.Download> download(
      WecomAiBotClient client, IncomingMessage incoming) {
    var content = incoming.content();
    return client.downloader().download(URI.create(content.url()), content.aesKey());
  }

  public static TemplateCard notice() {
    return TemplateCard.builder(TemplateCard.Type.TEXT_NOTICE)
        .mainTitle(new TemplateCard.Title("处理通知", "任务已完成"))
        .cardAction(new TemplateCard.CardAction(1, "https://example.com/result", null, null))
        .build();
  }

  public static TemplateCard news() {
    return TemplateCard.builder(TemplateCard.Type.NEWS_NOTICE)
        .mainTitle(new TemplateCard.Title("图文通知", null))
        .cardImage(new TemplateCard.CardImage("https://example.com/image.png", 1.5))
        .verticalContent(List.of(new TemplateCard.Title("详情", "示例内容")))
        .build();
  }

  public static TemplateCard buttons(String taskId) {
    return TemplateCard.builder(TemplateCard.Type.BUTTON_INTERACTION)
        .taskId(taskId)
        .mainTitle(new TemplateCard.Title("选择操作", null))
        .buttons(
            List.of(
                new TemplateCard.Button("确认", 1, "confirm"),
                new TemplateCard.Button("取消", 2, "cancel")))
        .build();
  }

  public static TemplateCard vote(String taskId) {
    return TemplateCard.builder(TemplateCard.Type.VOTE_INTERACTION)
        .taskId(taskId)
        .mainTitle(new TemplateCard.Title("请选择", null))
        .checkbox(
            new TemplateCard.Checkbox(
                "choice",
                false,
                0,
                List.of(
                    new TemplateCard.Option("a", "方案 A", false),
                    new TemplateCard.Option("b", "方案 B", false))))
        .submitButton(new TemplateCard.SubmitButton("提交", "submit"))
        .build();
  }

  public static TemplateCard multiple(String taskId) {
    return TemplateCard.builder(TemplateCard.Type.MULTIPLE_INTERACTION)
        .taskId(taskId)
        .mainTitle(new TemplateCard.Title("选择范围", null))
        .selections(
            List.of(
                new TemplateCard.Selection(
                    "range",
                    "范围",
                    false,
                    "a",
                    List.of(
                        new TemplateCard.Option("a", "全部", null),
                        new TemplateCard.Option("b", "部分", null)))))
        .submitButton(new TemplateCard.SubmitButton("提交", "submit"))
        .build();
  }

  public static CompletableFuture<Ack> updateClickedCard(
      WecomAiBotClient client, IncomingEvent event) {
    var updated =
        TemplateCard.builder(TemplateCard.Type.BUTTON_INTERACTION)
            .taskId(event.taskId())
            .mainTitle(new TemplateCard.Title("已处理", null))
            .buttons(List.of(new TemplateCard.Button("完成", 1, "done")))
            .build();
    return client.updateCard(event.context(), updated, List.of(event.userId()));
  }
}
