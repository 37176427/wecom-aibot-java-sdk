package io.github.user37176427.wecom.aibot;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Each update contains complete Markdown text. Nonempty images are rejected by the long-connection
 * client.
 */
public record StreamContent(
    String id, String content, boolean finish, List<InlineImage> images, String feedbackId) {
  public StreamContent {
    Json.required(id, "stream id");
    Json.limited(content, 20480, "stream content");
    images = images == null ? List.of() : List.copyOf(images);
    if (images.size() > 10 || (!finish && !images.isEmpty()))
      throw new IllegalArgumentException("Up to 10 images allowed only in final frame");
    if (feedbackId != null)
      Json.limited(Json.required(feedbackId, "feedbackId"), 256, "feedbackId");
  }

  public StreamContent(String id, String content, boolean finish, String feedbackId) {
    this(id, content, finish, List.of(), feedbackId);
  }

  public StreamContent(String id, String content, boolean finish) {
    this(id, content, finish, List.of(), null);
  }

  public ObjectNode toJson() {
    ObjectNode n = Json.object().put("id", id).put("content", content).put("finish", finish);
    if (!images.isEmpty()) {
      var a = n.putArray("msg_item");
      for (var i : images) a.add(i.toJson());
    }
    if (feedbackId != null) n.putObject("feedback").put("id", feedbackId);
    return n;
  }

  public record InlineImage(String base64, String md5) {
    public InlineImage {
      Json.required(base64, "base64");
      Json.required(md5, "md5");
      byte[] bytes = Base64.getDecoder().decode(base64);
      if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024)
        throw new IllegalArgumentException("Inline image must be 1..10MiB");
      if (!digest(bytes).equalsIgnoreCase(md5))
        throw new IllegalArgumentException("Image MD5 mismatch");
    }

    public static InlineImage of(byte[] data) {
      return new InlineImage(Base64.getEncoder().encodeToString(data), digest(data));
    }

    ObjectNode toJson() {
      var n = Json.object().put("msgtype", "image");
      n.putObject("image").put("base64", base64).put("md5", md5);
      return n;
    }

    @Override
    public String toString() {
      return "InlineImage[data=REDACTED]";
    }
  }

  static String digest(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
