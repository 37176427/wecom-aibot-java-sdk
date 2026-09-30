package io.github.moment.wecom.aibot;

/** SDK logs contain lifecycle metadata only, never credentials, message bodies or download URLs. */
@FunctionalInterface
public interface BotLogger {
  void log(System.Logger.Level level, String message);

  static BotLogger system() {
    System.Logger logger = System.getLogger("io.github.moment.wecom.aibot");
    return logger::log;
  }

  static BotLogger silent() {
    return (level, message) -> {};
  }
}
