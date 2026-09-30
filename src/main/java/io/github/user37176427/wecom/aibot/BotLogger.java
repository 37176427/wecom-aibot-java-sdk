package io.github.user37176427.wecom.aibot;

/** SDK logs contain lifecycle metadata only, never credentials, message bodies or download URLs. */
@FunctionalInterface
public interface BotLogger {
  void log(System.Logger.Level level, String message);

  static BotLogger system() {
    System.Logger logger = System.getLogger("io.github.user37176427.wecom.aibot");
    return logger::log;
  }

  static BotLogger silent() {
    return (level, message) -> {};
  }
}
