package io.github.user37176427.wecom.aibot.examples;

import io.github.user37176427.wecom.aibot.*;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Explicitly launched test tool; never auto-started by Maven and never packaged in the SDK jar. */
public final class SandboxBot implements BotListener, AutoCloseable {
  private static final byte[] IMAGE = testImage();

  private static byte[] testImage() {
    var image =
        new java.awt.image.BufferedImage(128, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 64; y++)
      for (int x = 0; x < 128; x++)
        image.setRGB(
            x,
            y,
            x < 4 || y < 4 || x >= 124 || y >= 60 ? 0xFFFFFF : (x < 64 ? 0x1967D2 : 0x34A853));
    try (var output = new java.io.ByteArrayOutputStream()) {
      if (!javax.imageio.ImageIO.write(image, "png", output))
        throw new IllegalStateException("PNG writer unavailable");
      return output.toByteArray();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot generate test PNG", e);
    }
  }

  private record Peer(String type, String chat, String user) {}

  private final WecomAiBotClient client;
  private final PrintStream out;
  private final String nonce;
  private final Map<String, String> settings;
  private final Set<Peer> allowed = ConcurrentHashMap.newKeySet();
  private final Map<String, TemplateCard> cardTasks = new ConcurrentHashMap<>();
  private final ExecutorService io =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          Thread.ofPlatform().daemon().name("sandbox-io-", 0).factory());
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("sandbox-timer").factory());

  public SandboxBot(
      WecomAiBotClient client, PrintStream out, String nonce, Map<String, String> settings) {
    this.client = Objects.requireNonNull(client);
    this.out = Objects.requireNonNull(out);
    if (nonce == null || nonce.length() < 16)
      throw new IllegalArgumentException("Use an unpredictable binding nonce");
    this.nonce = nonce;
    this.settings = Map.copyOf(settings);
    String user = settings.get("WECOM_TEST_USER_ID");
    if (user != null && !user.isBlank()) allowed.add(new Peer("single", user, user));
  }

  public static void main(String[] args) throws Exception {
    Map<String, String> settings = readSettings(Path.of(args.length == 0 ? ".env.local" : args[0]));
    var options =
        ClientOptions.builder(
            required(settings, "WECOM_BOT_ID"), required(settings, "WECOM_BOT_SECRET"));
    String endpoint = settings.get("WECOM_WS_URL");
    if (endpoint != null && !endpoint.isBlank()) options.endpoint(URI.create(endpoint));
    String nonce = UUID.randomUUID().toString();
    try (var client = new WecomAiBotClient(options.build());
        var sandbox = new SandboxBot(client, System.out, nonce, settings)) {
      client.addListener(sandbox);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    sandbox.close();
                    client.close();
                  }));
      client.connect().join();
      System.out.println("READY. In each dedicated test conversation send: sdk bind " + nonce);
      System.out.println(
          "Only bound users/conversations may run test commands. Ctrl-C closes this session.");
      new CountDownLatch(1).await();
    } catch (Exception e) {
      System.err.println("Sandbox stopped: " + root(e).getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static Map<String, String> readSettings(Path path) throws Exception {
    Map<String, String> result = new HashMap<>();
    if (Files.exists(path))
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        line = line.strip();
        if (line.isEmpty() || line.startsWith("#")) continue;
        int split = line.indexOf('=');
        if (split < 1) throw new IllegalArgumentException("Expected KEY=value in settings file");
        String key = line.substring(0, split).strip(), value = line.substring(split + 1).strip();
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))))
          value = value.substring(1, value.length() - 1);
        if (!key.startsWith("WECOM_"))
          throw new IllegalArgumentException("Unsupported settings key");
        result.put(key, value);
      }
    System.getenv()
        .forEach(
            (key, value) -> {
              if (key.startsWith("WECOM_")) result.put(key, value);
            });
    return result;
  }

  private static String required(Map<String, String> values, String key) {
    String v = values.get(key);
    if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing " + key);
    return v;
  }

  private static Peer peer(WsFrame frame) {
    var b = frame.body();
    String user = b.path("from").path("userid").asText("");
    if (user.isBlank()) return null;
    String type = b.path("chattype").asText("single");
    if (!type.equals("single") && !type.equals("group")) return null;
    String chat = type.equals("group") ? b.path("chatid").asText("") : user;
    return chat.isBlank() ? null : new Peer(type, chat, user);
  }

  @Override
  public void onState(ConnectionState state) {
    out.println("STATE " + state);
  }

  @Override
  public void onError(BotException e) {
    logFailure("connection", e);
  }

  @Override
  public void onMessage(IncomingMessage message) {
    Peer peer = peer(message.frame());
    if (peer == null) return;
    String text =
        message.type() == IncomingMessage.Type.TEXT
            ? commandText(peer, message.content().text())
            : null;
    if (text != null && text.strip().equals("sdk bind " + nonce)) {
      if (allowed.size() >= 16 && !allowed.contains(peer)) {
        out.println("BIND_LIMIT");
        return;
      }
      allowed.add(peer);
      out.println("BOUND " + peer.type() + " user=" + peer.user() + " chat=" + peer.chat());
      track("bind", client.replyText(message.context(), "[SDK测试] 已绑定此测试会话。发送 sdk help 查看测试指令。"));
      return;
    }
    if (!allowed.contains(peer)) {
      out.println("IGNORED_UNBOUND " + message.type());
      return;
    }
    out.println("RECEIVED " + message.type());
    if (message.type() == IncomingMessage.Type.IMAGE
        || message.type() == IncomingMessage.Type.FILE
        || message.type() == IncomingMessage.Type.VIDEO) {
      receiveMedia(message.context(), message.content());
      return;
    }
    if (message.type() == IncomingMessage.Type.MIXED) {
      for (var item : message.content().items())
        if ("image".equals(item.type())) {
          track(
              "mixed-download",
              client
                  .downloader()
                  .download(URI.create(item.url()), item.aesKey())
                  .thenAccept(d -> out.println("MIXED_BYTES " + d.bytes().length)));
        }
      track("mixed", client.replyText(message.context(), "[SDK测试] 收到图文混排。"));
      return;
    }
    if (message.type() == IncomingMessage.Type.VOICE) {
      track("voice-transcription", client.replyText(message.context(), "[SDK测试] 已收到语音转写。"));
      return;
    }
    if (text == null || !text.strip().startsWith("sdk ")) return;
    try {
      command(peer, message, text.strip().substring(4).strip());
    } catch (Exception e) {
      logFailure("command", e);
      track("command-error", client.replyText(message.context(), "[SDK测试] 指令或素材配置无效，请查看本地测试记录。"));
    }
  }

  private static String commandText(Peer peer, String content) {
    if (content == null) return null;
    String text = content.strip();
    if (peer.type().equals("group") && text.startsWith("@")) {
      var match =
          java.util.regex.Pattern.compile("(?s)^@[^\\r\\n]+?[\\s\\p{Zs}]+(sdk .*)$").matcher(text);
      if (match.matches()) return match.group(1).strip();
    }
    return text;
  }

  private void command(Peer peer, IncomingMessage message, String command) {
    var context = message.context();
    switch (command) {
      case "text" -> track("text", client.replyText(context, "[SDK测试] 普通文本回复成功。"));
      case "markdown" -> track(
          "markdown", client.replyMarkdown(context, "**[SDK测试] Markdown**\n> 引用内容\n- 列表项"));
      case "notify" -> track("notify", client.sendMarkdown(peer.chat(), "**[SDK测试] 主动通知**"));
      case "stream" -> {
        var stream = client.stream(context);
        track(
            "stream",
            stream
                .update("[SDK测试] 第一阶段…")
                .thenCompose(a -> pause())
                .thenCompose(a -> stream.update("[SDK测试] 第二阶段…"))
                .thenCompose(a -> pause())
                .thenCompose(a -> stream.finish("**[SDK测试] 流式结束**")));
      }
      case "image", "combined" -> track(
          "unsupported-mode-explanation",
          client.replyText(
              context, "[SDK测试] 长连接不支持流式内嵌图片或流式卡片组合。图片请使用 sdk media image，卡片请使用 sdk card button。"));
      case "feedback" -> track(
          "feedback-message",
          client.stream(context).send("[SDK测试] 请对这条消息提交反馈。", true, "sdk-" + UUID.randomUUID()));
      case "reconnect" -> track(
          "reconnect",
          client
              .replyText(context, "[SDK测试] 即将断开并重新连接，连接恢复后可继续发送测试指令。")
              .thenCompose(
                  ack -> {
                    client.disconnect();
                    return client.connect();
                  }));
      default -> {
        if (command.startsWith("card ")) {
          track(command, client.replyCard(context, testCard(command.substring(5))));
        } else if (command.startsWith("push-card ")) {
          track(command, client.sendCard(peer.chat(), testCard(command.substring(10))));
        } else if (command.startsWith("media ") || command.startsWith("push-media ")) {
          boolean push = command.startsWith("push-media ");
          MediaType type =
              MediaType.valueOf(
                  command.substring(command.indexOf(' ') + 1).toUpperCase(Locale.ROOT));
          track(
              command,
              CompletableFuture.supplyAsync(() -> asset(type), io)
                  .thenCompose(
                      bytes -> client.uploadMedia(bytes, type, "sdk-test." + extension(type)))
                  .thenCompose(
                      media ->
                          push
                              ? client.sendMedia(
                                  peer.chat(), new MediaMessage(type, media.mediaId()))
                              : client.replyMedia(
                                  context, new MediaMessage(type, media.mediaId()))));
        } else
          track(
              "help",
              client.replyText(
                  context,
                  "[SDK测试] 指令：sdk text / markdown / stream / notify / feedback / reconnect / card"
                      + " text|news|button|vote|multiple / push-card text|news|button|vote|multiple"
                      + " / media image|file|voice|video / push-media"
                      + " image|file|voice|video。也可发送测试图片、文件、语音、视频。"));
      }
    }
  }

  private CompletableFuture<Void> pause() {
    var f = new CompletableFuture<Void>();
    timer.schedule(() -> f.complete(null), 600, TimeUnit.MILLISECONDS);
    return f;
  }

  private String task() {
    if (cardTasks.size() >= 100) throw new IllegalStateException("Card test capacity reached");
    String id = UUID.randomUUID().toString();
    return id;
  }

  private TemplateCard interactive() {
    return register(MediaAndCards.buttons(task()));
  }

  private TemplateCard testCard(String type) {
    return switch (type) {
      case "text" -> MediaAndCards.notice();
      case "news" -> news();
      case "button" -> interactive();
      case "vote" -> register(MediaAndCards.vote(task()));
      case "multiple" -> register(MediaAndCards.multiple(task()));
      default -> throw new IllegalArgumentException("Unknown test card type");
    };
  }

  private TemplateCard register(TemplateCard card) {
    cardTasks.put(card.taskId(), card);
    return card;
  }

  private TemplateCard news() {
    String url = required(settings, "WECOM_TEST_CARD_IMAGE_URL");
    URI uri = URI.create(url);
    if (!"https".equals(uri.getScheme()) || uri.getHost() == null)
      throw new IllegalArgumentException("Expected a public HTTPS test image URL");
    return TemplateCard.builder(TemplateCard.Type.NEWS_NOTICE)
        .mainTitle(new TemplateCard.Title("[SDK测试] 图文卡片", "验证图片展示"))
        .cardImage(new TemplateCard.CardImage(url, 1.5))
        .cardAction(new TemplateCard.CardAction(1, url, null, null))
        .build();
  }

  private static String extension(MediaType type) {
    return switch (type) {
      case IMAGE -> "png";
      case FILE -> "txt";
      case VOICE -> "amr";
      case VIDEO -> "mp4";
    };
  }

  private byte[] asset(MediaType type) {
    if (type == MediaType.IMAGE) return IMAGE.clone();
    if (type == MediaType.FILE)
      return "SDK independent test fixture\n".getBytes(StandardCharsets.UTF_8);
    String key = type == MediaType.VOICE ? "WECOM_TEST_VOICE_FILE" : "WECOM_TEST_VIDEO_FILE";
    try (var in = Files.newInputStream(Path.of(required(settings, key)))) {
      byte[] data = in.readNBytes(MediaUpload.MAX_BYTES + 1);
      if (data.length > MediaUpload.MAX_BYTES)
        throw new IllegalArgumentException("Test asset too large");
      return data;
    } catch (Exception e) {
      throw new IllegalStateException("Test asset unavailable");
    }
  }

  private void receiveMedia(ReplyContext context, IncomingMessage.Content content) {
    track(
        "download",
        client
            .downloader()
            .download(URI.create(content.url()), content.aesKey())
            .thenCompose(
                d -> {
                  byte[] data = d.bytes();
                  String hash;
                  try {
                    hash =
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                  out.println("DOWNLOADED bytes=" + data.length + " sha256=" + hash);
                  return client.replyText(context, "[SDK测试] 下载解密完成，字节数：" + data.length);
                }));
  }

  @Override
  public void onEvent(IncomingEvent event) {
    Peer peer = peer(event.frame());
    if (event.type() == IncomingEvent.Type.DISCONNECTED_EVENT) {
      out.println("REPLACED_BY_ANOTHER_CONNECTION");
      return;
    }
    if (peer == null || !allowed.contains(peer)) return;
    out.println("EVENT " + event.type());
    switch (event.type()) {
      case ENTER_CHAT -> track(
          "welcome", client.replyWelcomeText(event.context(), "[SDK测试] 欢迎进入测试会话。"));
      case TEMPLATE_CARD_EVENT -> {
        TemplateCard card = event.taskId() == null ? null : cardTasks.remove(event.taskId());
        if (card != null) {
          var updated = card.toJson();
          updated.putObject("main_title").put("title", "[SDK测试] 已收到选择");
          if (updated.path("checkbox")
              instanceof com.fasterxml.jackson.databind.node.ObjectNode field)
            field.put("disable", true);
          if (updated.path("button_selection")
              instanceof com.fasterxml.jackson.databind.node.ObjectNode field)
            field.put("disable", true);
          for (var field : updated.path("select_list"))
            if (field instanceof com.fasterxml.jackson.databind.node.ObjectNode object)
              object.put("disable", true);
          track(
              "card-update",
              client.updateCard(
                  event.context(), TemplateCard.fromJson(updated), List.of(peer.user())));
        }
      }
      case FEEDBACK_EVENT -> out.println("FEEDBACK_RECEIVED");
      default -> {}
    }
  }

  private void track(String label, CompletableFuture<?> action) {
    action.whenComplete(
        (v, e) -> {
          if (e == null) out.println("OK " + label);
          else logFailure(label, e);
        });
  }

  private void logFailure(String label, Throwable error) {
    Throwable e = root(error);
    if (e instanceof BotException b)
      out.println(
          "FAIL "
              + label
              + " code="
              + b.code()
              + " remote="
              + b.remoteCode()
              + " delivery="
              + b.delivery());
    else out.println("FAIL " + label + " type=" + e.getClass().getSimpleName());
  }

  private static Throwable root(Throwable e) {
    while ((e instanceof CompletionException || e instanceof ExecutionException)
        && e.getCause() != null) e = e.getCause();
    return e;
  }

  @Override
  public void close() {
    timer.shutdownNow();
    io.shutdownNow();
  }
}
