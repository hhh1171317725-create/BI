package com.rockorca.bi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/** Browser-test server: real HTTP/game/session code, in-memory test users, no DB. */
public class GomokuBrowserServer {
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(BrowserConfiguration.class);
    app.setDefaultProperties(Map.of(
        "spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.config.location", "optional:classpath:/gomoku-browser-no-config.properties",
        "server.address", "127.0.0.1",
        "server.port", "0",
        "logging.level.root", "WARN",
        "spring.main.banner-mode", "off"));
    ConfigurableApplicationContext context = app.run(args);
    System.out.println("GOMOKU_BROWSER_READY http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"));
    System.out.flush();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class BrowserConfiguration {
    @Bean UserService users() {
      UserService users = mock(UserService.class);
      LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);
      for (int i = 0; i < 3; i++) {
        String name = new String[]{"alpha", "beta", "gamma"}[i];
        UserRepository.UserAccount user = new UserRepository.UserAccount(i + 1L, name,
            "unused-test-password", "member", true, 1, now, now, null);
        when(users.findById(user.id())).thenReturn(user);
        when(users.findByUsername(name)).thenReturn(user);
      }
      when(users.view(any(UserRepository.UserAccount.class), anyBoolean())).thenAnswer(call -> {
        UserRepository.UserAccount user = call.getArgument(0);
        return Map.of("id", user.id(), "username", user.username(), "role", user.role());
      });
      return users;
    }

    @Bean SessionService sessions(UserService users) {
      RuntimeConfig config = mock(RuntimeConfig.class);
      when(config.get("REPORT_SESSION_SECRET", "")).thenReturn("gomoku-browser-test-only-signing-secret");
      return new SessionService(config, new ObjectMapper(), users);
    }

    @Bean GomokuRoomService rooms() { return new GomokuRoomService(); }
    @Bean GomokuApiController gameApi(GomokuRoomService rooms, SessionService sessions) {
      return new GomokuApiController(rooms, sessions);
    }
    @Bean AuthApiController authApi(SessionService sessions, UserService users) {
      return new AuthApiController(sessions, users);
    }
    @Bean ApiExceptionHandler errors() { return new ApiExceptionHandler(); }
    @Bean BrowserSupportApi supportApi(SessionService sessions, UserService users) {
      return new BrowserSupportApi(sessions, users);
    }
    @Bean WebMvcConfigurer web(SessionService sessions) {
      return new WebMvcConfigurer() {
        @Override public void addInterceptors(InterceptorRegistry registry) {
          registry.addInterceptor(new AuthInterceptor(sessions, new ObjectMapper())).addPathPatterns("/api/**");
        }
        @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
          String resources = Path.of("frontend").toAbsolutePath().toUri().toString();
          registry.addResourceHandler("/**").addResourceLocations(resources);
        }
      };
    }
  }

  @RestController
  static class BrowserSupportApi {
    private final SessionService sessions;
    private final UserService users;
    BrowserSupportApi(SessionService sessions, UserService users) { this.sessions = sessions; this.users = users; }
    @GetMapping("/__test/login/{username}")
    ResponseEntity<Map<String, Object>> login(@PathVariable String username, HttpServletRequest request) {
      UserRepository.UserAccount user = users.findByUsername(username);
      if (user == null) return ResponseEntity.notFound().build();
      String cookie = sessions.cookie(request, sessions.createToken(user, System.currentTimeMillis()), SessionService.LIFETIME).toString();
      return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie).body(Map.of("authenticated", true));
    }
    @GetMapping("/api/report-visibility")
    Map<String, Boolean> reports() { return Map.of("dhh", true, "jd", false, "jdLowActivity", false, "adpflux", false); }
    @GetMapping("/api/tool-visibility")
    Map<String, Boolean> tools() { return Map.of("bidMonitor", false); }
    @GetMapping(value = "/login", produces = "text/html;charset=UTF-8")
    String loginPage() { return "<!doctype html><html lang=zh-CN><body><h1>登录</h1></body></html>"; }
  }
}
