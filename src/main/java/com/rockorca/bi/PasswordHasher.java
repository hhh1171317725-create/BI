package com.rockorca.bi;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final String PREFIX = "pbkdf2-sha256";
  private static final int ITERATIONS = 120_000;
  private static final int KEY_BITS = 256;
  private static final int SALT_BYTES = 16;
  private final SecureRandom random = new SecureRandom();

  public String hash(String password) {
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    byte[] derived = derive(String.valueOf(password), salt, ITERATIONS);
    return PREFIX + "$" + ITERATIONS + "$"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
  }

  public boolean matches(String password, String encoded) {
    try {
      String[] parts = String.valueOf(encoded).split("\\$", -1);
      if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
      int iterations = Integer.parseInt(parts[1]);
      if (iterations < 10_000 || iterations > 1_000_000) return false;
      byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
      byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
      byte[] actual = derive(String.valueOf(password), salt, iterations);
      return MessageDigest.isEqual(expected, actual);
    } catch (Exception ignored) {
      return false;
    }
  }

  private static byte[] derive(String password, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
    try {
      return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
    } catch (Exception error) {
      throw new IllegalStateException("生成密码哈希失败", error);
    } finally {
      spec.clearPassword();
    }
  }
}
