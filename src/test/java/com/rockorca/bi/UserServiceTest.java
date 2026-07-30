package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UserServiceTest {
  private UserRepository repository;
  private PasswordHasher passwords;
  private UserService service;
  private UserRepository.UserAccount admin;
  private UserRepository.UserAccount member;

  @BeforeEach
  void setUp() {
    repository = mock(UserRepository.class);
    passwords = mock(PasswordHasher.class);
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.get("REPORT_USERNAME", "hhh")).thenReturn("hhh");
    when(config.get("REPORT_PASSWORD", "123456")).thenReturn("123456");
    when(passwords.hash("123456")).thenReturn("bootstrap-hash");
    LocalDateTime now = LocalDateTime.of(2026, 7, 30, 12, 0);
    admin = new UserRepository.UserAccount(
        1L, "hhh", "admin-hash", "admin", true, 1, now, now, now);
    member = new UserRepository.UserAccount(
        2L, "member", "member-hash", "user", true, 1, now, now, null);
    service = new UserService(repository, passwords, config);
  }

  @Test
  void initializesBootstrapAdminAndAuthenticatesActiveUsers() {
    when(repository.findByUsername("hhh")).thenReturn(Optional.of(admin));
    when(repository.findById(1L)).thenReturn(Optional.of(admin));
    when(passwords.matches("123456", "admin-hash")).thenReturn(true);

    assertEquals(admin, service.authenticate("hhh", "123456"));
    verify(repository).initialize("hhh", "bootstrap-hash");
    verify(repository).markLogin(1L);
  }

  @Test
  void ordinaryUsersCannotManageAccounts() {
    assertThrows(ResponseStatusException.class,
        () -> service.createUser(member, "newuser", "123456", "user"));
  }

  @Test
  void changingPasswordRequiresCurrentPassword() {
    when(repository.findById(1L)).thenReturn(Optional.of(admin));
    when(passwords.matches("wrong", "admin-hash")).thenReturn(false);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> service.changeOwnPassword(admin, "wrong", "654321"));
    assertEquals("当前密码不正确", error.getMessage());
  }
}
