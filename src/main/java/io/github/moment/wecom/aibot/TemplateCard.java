package io.github.moment.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

/**
 * All five official card types and their shared components. Server validates type-specific
 * combinations.
 */
public final class TemplateCard {
  public enum Type {
    TEXT_NOTICE,
    NEWS_NOTICE,
    BUTTON_INTERACTION,
    VOTE_INTERACTION,
    MULTIPLE_INTERACTION
  }

  public record Source(String iconUrl, String desc, Integer descColor) {}

  public record ActionItem(String text, String key) {}

  public record ActionMenu(String desc, List<ActionItem> actionList) {}

  public record Title(String title, String desc) {}

  public record QuoteArea(
      Integer type, String url, String appid, String pagepath, String title, String quoteText) {}

  public record HorizontalContent(
      Integer type, String keyname, String value, String url, String userid) {}

  public record JumpAction(
      Integer type, String title, String url, String appid, String pagepath, String question) {}

  public record CardAction(int type, String url, String appid, String pagepath) {}

  public record CardImage(String url, Double aspectRatio) {}

  public record ImageTextArea(
      Integer type,
      String url,
      String appid,
      String pagepath,
      String title,
      String desc,
      String imageUrl) {}

  public record Option(String id, String text, Boolean isChecked) {}

  public record Selection(
      String questionKey,
      String title,
      Boolean disable,
      String selectedId,
      List<Option> optionList) {}

  public record Button(String text, Integer style, String key) {}

  public record Checkbox(
      String questionKey, Boolean disable, Integer mode, List<Option> optionList) {}

  public record SubmitButton(String text, String key) {}

  private final ObjectNode data;

  private TemplateCard(ObjectNode data) {
    this.data = data.deepCopy();
  }

  public static Builder builder(Type type) {
    return new Builder(Objects.requireNonNull(type).name().toLowerCase(java.util.Locale.ROOT));
  }
  /** Explicit extension boundary, including future card types. */
  public static TemplateCard fromJson(ObjectNode node) {
    Json.required(Json.text(node, "card_type"), "card_type");
    return new TemplateCard(node);
  }

  public ObjectNode toJson() {
    return data.deepCopy();
  }

  public String taskId() {
    return Json.text(data, "task_id");
  }

  public static final class Builder {
    private final ObjectNode data = Json.object();

    private Builder(String type) {
      data.put("card_type", type);
    }

    private Builder set(String key, Object value) {
      data.set(key, Json.MAPPER.valueToTree(Objects.requireNonNull(value)));
      return this;
    }

    public Builder source(Source v) {
      return set("source", v);
    }

    public Builder actionMenu(ActionMenu v) {
      if (v.actionList().isEmpty() || v.actionList().size() > 3)
        throw new IllegalArgumentException("action_list: 1..3");
      return set("action_menu", v);
    }

    public Builder mainTitle(Title v) {
      return set("main_title", v);
    }

    public Builder emphasisContent(Title v) {
      return set("emphasis_content", v);
    }

    public Builder quoteArea(QuoteArea v) {
      return set("quote_area", v);
    }

    public Builder subTitleText(String v) {
      return set("sub_title_text", v);
    }

    public Builder horizontalContent(List<HorizontalContent> v) {
      limit(v, 6);
      return set("horizontal_content_list", v);
    }

    public Builder jumpList(List<JumpAction> v) {
      limit(v, 3);
      return set("jump_list", v);
    }

    public Builder cardAction(CardAction v) {
      return set("card_action", v);
    }

    public Builder cardImage(CardImage v) {
      return set("card_image", v);
    }

    public Builder imageTextArea(ImageTextArea v) {
      return set("image_text_area", v);
    }

    public Builder verticalContent(List<Title> v) {
      limit(v, 4);
      return set("vertical_content_list", v);
    }

    public Builder buttonSelection(Selection v) {
      return set("button_selection", v);
    }

    public Builder buttons(List<Button> v) {
      limit(v, 6);
      return set("button_list", v);
    }

    public Builder checkbox(Checkbox v) {
      return set("checkbox", v);
    }

    public Builder selections(List<Selection> v) {
      limit(v, 3);
      return set("select_list", v);
    }

    public Builder submitButton(SubmitButton v) {
      return set("submit_button", v);
    }

    public Builder taskId(String v) {
      if (!Json.limited(v, 128, "task_id").matches("[A-Za-z0-9_@-]+"))
        throw new IllegalArgumentException("Invalid task_id");
      return set("task_id", v);
    }

    public Builder feedback(String id) {
      return set(
          "feedback",
          java.util.Map.of("id", Json.limited(Json.required(id, "feedback"), 256, "feedback")));
    }
    /** Adds future fields without discarding typed fields. */
    public Builder extra(String name, JsonNode value) {
      if (data.has(name)) throw new IllegalArgumentException("Field already set: " + name);
      data.set(name, value.deepCopy());
      return this;
    }

    public TemplateCard build() {
      return new TemplateCard(data);
    }

    private static void limit(List<?> v, int n) {
      if (v.size() > n) throw new IllegalArgumentException("Too many card items (max " + n + ")");
    }
  }
}
