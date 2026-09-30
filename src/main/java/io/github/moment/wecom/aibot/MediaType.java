package io.github.moment.wecom.aibot;

public enum MediaType {
  IMAGE,
  FILE,
  VOICE,
  VIDEO;

  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
