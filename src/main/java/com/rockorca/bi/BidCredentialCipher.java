package com.rockorca.bi;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class BidCredentialCipher {
  private final Path path;
  public BidCredentialCipher(RuntimeConfig config) { path = config.runtimeDir().resolve("bid-monitor.key"); }

  private synchronized byte[] key(boolean create) throws Exception {
    if (!Files.exists(path) && create) {
      Files.createDirectories(path.getParent());
      try {
        try { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException error) { Files.createFile(path); }
      } catch (FileAlreadyExistsException ignored) { /* Another process initialized the shared key. */ }
    }
    try (var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);
         var lock=channel.lock()) {
      if(channel.size()==0&&create){
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
      }
      if(channel.size()!=32)throw new IllegalStateException("Invalid credential key");
      channel.position(0);var buffer=java.nio.ByteBuffer.allocate(32);
      while(buffer.hasRemaining())if(channel.read(buffer)<0)throw new IllegalStateException("Invalid credential key");
      return buffer.array();
    }
  }

  public String encrypt(long owner, String value) throws Exception {
    byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, iv));
    cipher.updateAAD(Long.toString(owner).getBytes(StandardCharsets.UTF_8));
    byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
    byte[] output = Arrays.copyOf(iv, iv.length + data.length);
    System.arraycopy(data, 0, output, iv.length, data.length);
    return Base64.getEncoder().encodeToString(output);
  }

  public String decrypt(long owner, String value) throws Exception {
    byte[] data = Base64.getDecoder().decode(value);
    if (data.length < 28) throw new IllegalArgumentException("Invalid encrypted credential");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, data, 0, 12));
    cipher.updateAAD(Long.toString(owner).getBytes(StandardCharsets.UTF_8));
    return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
  }
}
