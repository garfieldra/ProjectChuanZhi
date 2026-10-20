package com.indiesoundquest.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_account")
public class UserAccount {
  @Id private UUID id;
  @Column(name = "email_normalized", nullable = false, unique = true, length = 320) private String emailNormalized;
  @Column(name = "display_name", nullable = false, length = 80) private String displayName;
  @Column(name = "avatar_url", length = 1024) private String avatarUrl;
  @Column(nullable = false, length = 24) private String status;
  @Column(name = "primary_guest_session_id", nullable = false, unique = true) private UUID primaryGuestSessionId;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected UserAccount() {}

  public static UserAccount create(String email, String displayName, String avatarUrl, UUID guestSessionId) {
    var value = new UserAccount();
    value.id = UUID.randomUUID();
    value.emailNormalized = email;
    value.displayName = displayName;
    value.avatarUrl = avatarUrl;
    value.status = "ACTIVE";
    value.primaryGuestSessionId = guestSessionId;
    value.createdAt = value.updatedAt = Instant.now();
    return value;
  }

  public UUID getId() { return id; }
  public String getEmailNormalized() { return emailNormalized; }
  public String getDisplayName() { return displayName; }
  public String getAvatarUrl() { return avatarUrl; }
  public String getStatus() { return status; }
  public UUID getPrimaryGuestSessionId() { return primaryGuestSessionId; }
}
