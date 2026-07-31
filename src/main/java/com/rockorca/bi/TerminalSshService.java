package com.rockorca.bi;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchChangedHostKeyException;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Service
public class TerminalSshService {
  private static final Logger log = LoggerFactory.getLogger(TerminalSshService.class);
  private static final int CONNECT_TIMEOUT_MS = 10_000;
  private static final int MAX_SESSIONS = 4;

  private final RuntimeConfig config;
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ssh-terminal-", 0).factory());
  private final Semaphore slots = new Semaphore(MAX_SESSIONS);
  private final Map<String, TerminalConnection> connections = new ConcurrentHashMap<>();

  public TerminalSshService(RuntimeConfig config) {
    this.config = config;
  }

  public Map<String, Object> publicSettings() {
    Settings settings = settings();
    Path knownHosts = config.runtimeDir().resolve("ssh-known-hosts");
    return ReportService.mapOf(
        "configured", settings.configured(),
        "host", settings.host(),
        "port", settings.port(),
        "username", settings.username(),
        "authMethod", settings.authMethod(),
        "credentialSaved", settings.credentialSaved(),
        "knownHostSaved", regularNonEmptyFile(knownHosts),
        "maxSessions", MAX_SESSIONS);
  }

  public Map<String, Object> saveSettings(Map<String, Object> payload) {
    int port = parsePort(payload.get("port"));
    config.saveSshCredentials(
        ReportService.text(payload.get("host")),
        port,
        ReportService.text(payload.get("username")),
        ReportService.text(payload.get("authMethod")),
        rawText(payload.get("password")),
        rawText(payload.get("privateKey")),
        rawText(payload.get("passphrase")));
    return publicSettings();
  }

  public Map<String, Object> testConnection() {
    Settings settings = requireSettings();
    Session session = null;
    try {
      session = createSession(settings);
      session.connect(CONNECT_TIMEOUT_MS);
      return ReportService.mapOf(
          "ok", true,
          "message", "SSH 连接成功",
          "serverVersion", session.getServerVersion(),
          "hostKeyType", session.getHostKey().getType(),
          "hostKeyFingerprint", session.getHostKey().getFingerPrint(new JSch()));
    } catch (Exception error) {
      throw new IllegalArgumentException(readableError(error));
    } finally {
      if (session != null) session.disconnect();
    }
  }

  public void open(WebSocketSession webSocket) {
    if (!slots.tryAcquire()) {
      sendControl(webSocket, "error", "终端连接数已满，请先关闭其他终端");
      closeWebSocket(webSocket);
      return;
    }
    TerminalConnection connection = new TerminalConnection(webSocket);
    connections.put(webSocket.getId(), connection);
    executor.submit(() -> run(connection));
  }

  public void input(String sessionId, String data) {
    if (data == null || data.isEmpty() || data.length() > 32_768) return;
    TerminalConnection connection = connections.get(sessionId);
    if (connection == null) return;
    try {
      connection.write(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception error) {
      connection.close();
    }
  }

  public void resize(String sessionId, int columns, int rows) {
    TerminalConnection connection = connections.get(sessionId);
    if (connection == null) return;
    connection.resize(clamp(columns, 20, 400), clamp(rows, 5, 200));
  }

  public void close(String sessionId) {
    TerminalConnection connection = connections.get(sessionId);
    if (connection != null) connection.close();
  }

  private void run(TerminalConnection connection) {
    try {
      Settings settings = requireSettings();
      if (connection.closed.get()) return;
      sendControl(connection.webSocket, "status", "正在连接 SSH...");
      Session ssh = createSession(settings);
      connection.ssh = ssh;
      if (connection.closed.get()) return;
      ssh.connect(CONNECT_TIMEOUT_MS);
      if (connection.closed.get()) return;

      ChannelShell channel = (ChannelShell) ssh.openChannel("shell");
      connection.channel = channel;
      channel.setPty(true);
      channel.setPtyType("xterm-256color");
      channel.setPtySize(connection.columns, connection.rows, 0, 0);
      InputStream output = channel.getInputStream();
      connection.input = channel.getOutputStream();
      if (connection.closed.get()) return;
      channel.connect(CONNECT_TIMEOUT_MS);

      sendControl(connection.webSocket, "connected",
          settings.username() + "@" + settings.host());
      byte[] buffer = new byte[16_384];
      while (!connection.closed.get() && connection.webSocket.isOpen()) {
        int read = output.read(buffer);
        if (read < 0) break;
        if (read > 0) sendBinary(connection.webSocket, Arrays.copyOf(buffer, read));
      }
      sendControl(connection.webSocket, "disconnected", "SSH 连接已关闭");
    } catch (Exception error) {
      String message = readableError(error);
      log.warn("SSH terminal connection failed: {}", message);
      sendControl(connection.webSocket, "error", message);
    } finally {
      connection.close();
      connections.remove(connection.webSocket.getId(), connection);
      slots.release();
    }
  }

  private Session createSession(Settings settings) throws Exception {
    Path knownHosts = config.runtimeDir().resolve("ssh-known-hosts");
    Files.createDirectories(config.runtimeDir());
    if (!Files.exists(knownHosts)) Files.createFile(knownHosts);
    setOwnerOnly(knownHosts);

    JSch jsch = new JSch();
    jsch.setKnownHosts(knownHosts.toString());
    if (settings.authMethod().equals("privateKey")) {
      if (settings.passphrase().isBlank()) {
        jsch.addIdentity(settings.privateKeyPath().toString());
      } else {
        jsch.addIdentity(
            settings.privateKeyPath().toString(),
            settings.passphrase().getBytes(StandardCharsets.UTF_8));
      }
    }

    Session session = jsch.getSession(settings.username(), settings.host(), settings.port());
    if (settings.authMethod().equals("password")) {
      byte[] password = settings.password().getBytes(StandardCharsets.UTF_8);
      session.setPassword(password);
      Arrays.fill(password, (byte) 0);
    }
    session.setConfig(
        "PreferredAuthentications",
        settings.authMethod().equals("password")
            ? "password,keyboard-interactive"
            : "publickey");
    session.setConfig("StrictHostKeyChecking", "ask");
    session.setUserInfo(new TrustNewHostOnly());
    session.setServerAliveInterval(15_000);
    session.setServerAliveCountMax(3);
    return session;
  }

  private Settings settings() {
    String authMethod = config.get("SSH_AUTH_METHOD", "password");
    Path privateKeyPath = Path.of(
        config.get("SSH_PRIVATE_KEY_PATH", config.runtimeDir().resolve("ssh-private-key").toString()));
    return new Settings(
        config.get("SSH_HOST", ""),
        config.getInt("SSH_PORT", 22),
        config.get("SSH_USERNAME", ""),
        authMethod,
        config.decodedSecret("SSH_PASSWORD_B64"),
        privateKeyPath,
        config.decodedSecret("SSH_PASSPHRASE_B64"));
  }

  private Settings requireSettings() {
    Settings settings = settings();
    if (!settings.configured()) {
      throw new IllegalArgumentException("请先完成 SSH 服务器配置");
    }
    return settings;
  }

  private static int parsePort(Object value) {
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      throw new IllegalArgumentException("SSH 端口格式无效");
    }
  }

  private static String rawText(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String readableError(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) current = current.getCause();
    String message = String.valueOf(current.getMessage());
    if (error instanceof JSchChangedHostKeyException
        || message.toLowerCase().contains("hostkey has been changed")) {
      return "SSH 主机指纹发生变化，已拒绝连接。请核实服务器后删除 .runtime/ssh-known-hosts 再重新连接";
    }
    if (message.contains("Auth fail") || message.toLowerCase().contains("authentication")) {
      return "SSH 身份验证失败，请检查用户名和凭据";
    }
    if (message.toLowerCase().contains("timeout")) {
      return "SSH 连接超时，请检查主机地址、安全组和端口";
    }
    if (message.toLowerCase().contains("connection refused")) {
      return "SSH 端口拒绝连接，请检查 SSH 服务和安全组";
    }
    if (message.isBlank() || message.equals("null")) return "SSH 连接失败";
    return "SSH 连接失败：" + message.replaceAll("[\\r\\n]+", " ");
  }

  private void sendControl(WebSocketSession webSocket, String type, String message) {
    if (!webSocket.isOpen()) return;
    String payload;
    try {
      payload = config.objectMapper().writeValueAsString(Map.of(
          "type", type,
          "message", message == null ? "" : message));
    } catch (Exception ignored) {
      return;
    }
    synchronized (webSocket) {
      try {
        if (webSocket.isOpen()) webSocket.sendMessage(new TextMessage(payload));
      } catch (Exception ignored) {
        // The browser may close while an SSH operation is completing.
      }
    }
  }

  private static void sendBinary(WebSocketSession webSocket, byte[] data) throws Exception {
    synchronized (webSocket) {
      if (webSocket.isOpen()) webSocket.sendMessage(new BinaryMessage(data));
    }
  }

  private static void closeWebSocket(WebSocketSession webSocket) {
    try {
      if (webSocket.isOpen()) webSocket.close();
    } catch (Exception ignored) {
      // Already closed.
    }
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private static boolean regularNonEmptyFile(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static void setOwnerOnly(Path path) {
    try {
      Files.setPosixFilePermissions(
          path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (Exception ignored) {
      // Windows and some mounted filesystems do not expose POSIX permissions.
    }
  }

  @PreDestroy
  void shutdown() {
    connections.values().forEach(TerminalConnection::close);
    executor.shutdownNow();
  }

  record Settings(
      String host,
      int port,
      String username,
      String authMethod,
      String password,
      Path privateKeyPath,
      String passphrase) {
    boolean credentialSaved() {
      return authMethod.equals("privateKey")
          ? Files.isRegularFile(privateKeyPath)
          : !password.isBlank();
    }

    boolean configured() {
      return !host.isBlank()
          && port > 0
          && port <= 65535
          && !username.isBlank()
          && credentialSaved();
    }
  }

  private static final class TrustNewHostOnly implements UserInfo {
    @Override
    public String getPassphrase() {
      return null;
    }

    @Override
    public String getPassword() {
      return null;
    }

    @Override
    public boolean promptPassword(String message) {
      return false;
    }

    @Override
    public boolean promptPassphrase(String message) {
      return false;
    }

    @Override
    public boolean promptYesNo(String message) {
      return message != null && message.startsWith("The authenticity of host");
    }

    @Override
    public void showMessage(String message) {
      // Connection errors are returned through the terminal status channel.
    }
  }

  private static final class TerminalConnection {
    private final WebSocketSession webSocket;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Session ssh;
    private volatile ChannelShell channel;
    private volatile OutputStream input;
    private volatile int columns = 100;
    private volatile int rows = 30;

    private TerminalConnection(WebSocketSession webSocket) {
      this.webSocket = webSocket;
    }

    private synchronized void write(byte[] data) throws Exception {
      if (closed.get() || input == null) return;
      input.write(data);
      input.flush();
    }

    private void resize(int columns, int rows) {
      this.columns = columns;
      this.rows = rows;
      ChannelShell current = channel;
      if (current != null && current.isConnected()) current.setPtySize(columns, rows, 0, 0);
    }

    private void close() {
      if (!closed.compareAndSet(false, true)) return;
      try {
        if (input != null) input.close();
      } catch (Exception ignored) {
        // Continue closing SSH resources.
      }
      if (channel != null) channel.disconnect();
      if (ssh != null) ssh.disconnect();
    }
  }
}
