package com.soundseek.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "user_account")
public class UserAccount {
  @Id private UUID id;
  @Column(name = "email_normalized", nullable = true, unique = true, length = 320) private String emailNormalized;
  @Column(name = "display_name", nullable = false, length = 80) private String displayName;
  @Column(name = "avatar_url", length = 1024) private String avatarUrl;
  @Column(nullable = false, length = 24) private String status;
  @Column(name = "primary_guest_session_id", nullable = false, unique = true) private UUID primaryGuestSessionId;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @Column(name="email_verified_at") private Instant emailVerifiedAt;
  @Column(name="auth_version",nullable=false) private long authVersion;
  protected UserAccount() {}
  public boolean isEmailVerified(){return emailVerifiedAt!=null;}
  public long getAuthVersion(){return authVersion;}
  public void verifyEmail(){emailVerifiedAt=Instant.now();updatedAt=Instant.now();}
  public void changeEmail(String email){emailNormalized=email;verifyEmail();revokeSessions();}
  public void revokeSessions(){authVersion++;updatedAt=Instant.now();}
  public void disable(){status="DISABLED";revokeSessions();}


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

  public void rename(String displayName) {
    var value = displayName == null ? "" : displayName.strip();
    if (value.isBlank() || value.length() > 80) throw new IllegalArgumentException("称呼需要填写 1–80 个字符");
    this.displayName = value;
    this.updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public String getEmailNormalized() { return emailNormalized; }
  public String getDisplayName() { return displayName; }
  public String getAvatarUrl() { return avatarUrl; }
  public String getStatus() { return status; }
  public UUID getPrimaryGuestSessionId() { return primaryGuestSessionId; }
}
