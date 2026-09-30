package io.github.user37176427.wecom.aibot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EventPayloadTest {
  private static IncomingEvent event(String content) throws Exception {
    var body = Json.object().put("msgtype", "event");
    body.set("event", Json.MAPPER.readTree(content));
    var frame = Json.object();
    frame.set("body", body);
    return new IncomingEvent(null, new WsFrame(frame));
  }

  @Test
  void nestedCardPayloadExposesTaskAndKeyWithoutLosingEnvelope() throws Exception {
    var incoming =
        event(
            "{\"eventtype\":\"template_card_event\",\"template_card_event\":{\"card_type\":\"button_interaction\",\"event_key\":\"confirm\",\"task_id\":\"task\",\"future\":42}}");
    assertEquals(IncomingEvent.Type.TEMPLATE_CARD_EVENT, incoming.type());
    assertEquals("task", incoming.taskId());
    assertEquals("confirm", incoming.eventKey());
    assertEquals(42, incoming.details().path("future").asInt());
    assertTrue(incoming.data().has("template_card_event"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) incoming.details()).put("task_id", "changed");
    assertEquals("task", incoming.taskId());
  }

  @Test
  void flatSdkBaselineEventRemainsReadable() throws Exception {
    var incoming =
        event(
            "{\"eventtype\":\"template_card_event\",\"event_key\":\"confirm\",\"task_id\":\"task\"}");
    assertEquals("task", incoming.taskId());
    assertEquals("confirm", incoming.eventKey());
  }

  @Test
  void nestedFeedbackAndUnknownEventsKeepSpecificFields() throws Exception {
    var feedback =
        event(
            "{\"eventtype\":\"feedback_event\",\"feedback_event\":{\"id\":\"feedback\",\"type\":1}}");
    assertEquals(IncomingEvent.Type.FEEDBACK_EVENT, feedback.type());
    assertEquals("feedback", feedback.details().path("id").asText());
    var unknown = event("{\"eventtype\":\"future_event\",\"future_event\":{\"data\":123}}");
    assertEquals(IncomingEvent.Type.UNKNOWN, unknown.type());
    assertEquals(123, unknown.details().path("data").asInt());
  }
}
