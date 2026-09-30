package io.github.user37176427.wecom.aibot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class OfficialCryptoTest {
  @Test
  void decryptOfficialNodeVectorAndVerifySignature() throws Exception {
    var vector =
        Json.MAPPER.readTree(getClass().getResourceAsStream("/official-crypto-vector.json"));
    var crypto =
        new WecomCrypto(
            vector.path("token").asText(),
            vector.path("encodingKey").asText(),
            vector.path("receiveId").asText());
    assertEquals(vector.path("text").asText(), crypto.decrypt(vector.path("encrypt").asText()));
    assertTrue(
        crypto.verifySignature(
            vector.path("signature").asText(), "123", "456", vector.path("encrypt").asText()));
    assertFalse(crypto.verifySignature("bad", "123", "456", vector.path("encrypt").asText()));
    assertArrayEquals(
        Base64.getDecoder().decode(vector.path("mediaPlain").asText()),
        WecomCrypto.decryptFile(
            Base64.getDecoder().decode(vector.path("mediaEncrypted").asText()),
            vector.path("encodingKey").asText()));
    assertThrows(
        BotException.class,
        () ->
            new WecomCrypto("t", vector.path("encodingKey").asText(), "wrong-receiver")
                .decrypt(vector.path("encrypt").asText()));
  }

  @Test
  void unicodeAndBlockAlignedRoundTrips() {
    var crypto = new WecomCrypto("token", Base64.getEncoder().encodeToString(new byte[32]), "");
    for (String text : java.util.List.of("", "x".repeat(12), "中文 😀")) {
      var encrypted = crypto.encrypt(text, "123", "456");
      assertTrue(crypto.verifySignature(encrypted.signature(), "123", "456", encrypted.encrypt()));
      assertEquals(text, crypto.decrypt(encrypted.encrypt()));
    }
  }
}
