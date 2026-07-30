package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {
  @Test
  void hashesUseRandomSaltAndVerifyWithoutStoringPlaintext() {
    PasswordHasher hasher = new PasswordHasher();
    String first = hasher.hash("123456");
    String second = hasher.hash("123456");

    assertNotEquals(first, second);
    assertFalse(first.contains("123456"));
    assertTrue(hasher.matches("123456", first));
    assertFalse(hasher.matches("wrong", first));
    assertFalse(hasher.matches("123456", "invalid"));
  }
}
