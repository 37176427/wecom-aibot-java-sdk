package io.github.moment.wecom.aibot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** WeCom AES-256-CBC and SHA-1 wire primitives. No Webhook transport is implied. */
public final class WecomCrypto {
  private final String token, receiveId;
  private final byte[] key;

  public WecomCrypto(String token, String encodingAesKey, String receiveId) {
    this.token = Json.required(token, "token");
    key = decodeKey(encodingAesKey);
    this.receiveId = receiveId == null ? "" : receiveId;
  }

  public static byte[] decodeKey(String encoded) {
    byte[] key = Base64.getDecoder().decode(Json.required(encoded, "AES key").trim());
    if (key.length != 32) throw new IllegalArgumentException("AES key must decode to 32 bytes");
    return key;
  }

  public static byte[] pad(byte[] data, int blockSize) {
    if (blockSize < 1 || blockSize > 255) throw new IllegalArgumentException("Invalid block size");
    int n = blockSize - data.length % blockSize;
    byte[] out = Arrays.copyOf(data, data.length + n);
    Arrays.fill(out, data.length, out.length, (byte) n);
    return out;
  }

  public static byte[] unpad(byte[] data, int blockSize) {
    if (blockSize < 1 || blockSize > 255 || data.length == 0) throw cryptoError();
    int n = Byte.toUnsignedInt(data[data.length - 1]);
    if (n < 1 || n > blockSize || n > data.length) throw cryptoError();
    for (int i = data.length - n; i < data.length; i++)
      if (Byte.toUnsignedInt(data[i]) != n) throw cryptoError();
    return Arrays.copyOf(data, data.length - n);
  }

  public static byte[] decryptFile(byte[] encrypted, String encodedKey) {
    return unpad(crypt(Cipher.DECRYPT_MODE, decodeKey(encodedKey), encrypted), 32);
  }

  private static byte[] crypt(int mode, byte[] key, byte[] data) {
    try {
      Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOf(key, 16)));
      return cipher.doFinal(data);
    } catch (Exception e) {
      throw cryptoError();
    }
  }

  private static BotException cryptoError() {
    return new BotException(
        BotException.Code.CRYPTO,
        BotException.Delivery.NOT_SENT,
        "Invalid encrypted payload or padding");
  }

  public String computeSignature(String timestamp, String nonce, String encrypted) {
    String[] parts = {
      token,
      java.util.Objects.requireNonNull(timestamp),
      java.util.Objects.requireNonNull(nonce),
      java.util.Objects.requireNonNull(encrypted)
    };
    Arrays.sort(parts);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-1")
                  .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw cryptoError();
    }
  }

  public boolean verifySignature(
      String signature, String timestamp, String nonce, String encrypted) {
    return signature != null
        && MessageDigest.isEqual(
            computeSignature(timestamp, nonce, encrypted).getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII));
  }

  public String decrypt(String encrypted) {
    byte[] data = unpad(crypt(Cipher.DECRYPT_MODE, key, Base64.getDecoder().decode(encrypted)), 32);
    if (data.length < 20) throw cryptoError();
    long len = Integer.toUnsignedLong(ByteBuffer.wrap(data, 16, 4).getInt());
    if (len > data.length - 20) throw cryptoError();
    int end = 20 + (int) len;
    if (!receiveId.isEmpty()
        && !receiveId.equals(new String(data, end, data.length - end, StandardCharsets.UTF_8)))
      throw cryptoError();
    return new String(data, 20, (int) len, StandardCharsets.UTF_8);
  }

  public record Encrypted(String encrypt, String signature) {}

  public Encrypted encrypt(String text, String timestamp, String nonce) {
    byte[] random = new byte[16];
    new SecureRandom().nextBytes(random);
    byte[] msg = text.getBytes(StandardCharsets.UTF_8),
        receiver = receiveId.getBytes(StandardCharsets.UTF_8);
    byte[] raw =
        ByteBuffer.allocate(20 + msg.length + receiver.length)
            .put(random)
            .putInt(msg.length)
            .put(msg)
            .put(receiver)
            .array();
    String encrypted =
        Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE, key, pad(raw, 32)));
    return new Encrypted(encrypted, computeSignature(timestamp, nonce, encrypted));
  }
}
