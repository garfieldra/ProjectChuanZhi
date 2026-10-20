package com.indiesoundquest.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_identity")
public class AuthIdentity {
  @Id private UUID id;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(nullable = false, length = 24) private String provider;
  @Column(name = "provider_subject", nullable = false, length = 255) private String providerSubject;
  @Column(name = "password_hash", length = 100) private String passwordHash;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected AuthIdentity() {}

  public static AuthIdentity password(UUID userId, String email, String passwordHash) {
    return create(userId, "PASSWORD", email, passwordHash);
  }

  public static AuthIdentity github(UUID userId, String subject) {
    return create(userId, "GITHUB", subject, null);
  }

  private static AuthIdentity create(UUID userId, String provider, String subject, String passwordHash) {
    var value = new AuthIdentity();
    value.id = UUID.randomUUID();
    value.userId = userId;
    value.provider = provider;
    value.providerSubject = subject;
    value.passwordHash = passwordHash;
    value.createdAt = value.updatedAt = Instant.now();
    return value;
  }

  public UUID getUserId() { return userId; }
  public String getPasswordHash() { return passwordHash; }
}
