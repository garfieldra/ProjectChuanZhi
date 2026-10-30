package com.soundseek.identity;

import com.soundseek.tournament.domain.GuestSession;
import com.soundseek.tournament.repository.GuestSessionRepository;
import com.soundseek.tournament.application.MusicPreferenceProfileService;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService implements UserDetailsService {
  private final UserAccountRepository users;
  private final AuthIdentityRepository identities;
  private final PasswordEncoder passwords;
  private final GuestAssetMergeService assets;
  private final GuestSessionRepository guestSessions;
  private final MusicPreferenceProfileService profiles;
  private final GuestIdentityService guestIdentities;

  public AccountService(UserAccountRepository users, AuthIdentityRepository identities, PasswordEncoder passwords, GuestAssetMergeService assets, GuestSessionRepository guestSessions, MusicPreferenceProfileService profiles, GuestIdentityService guestIdentities) {
    this.users = users; this.identities = identities; this.passwords = passwords; this.assets = assets; this.guestSessions = guestSessions; this.profiles = profiles; this.guestIdentities = guestIdentities;
  }

  @Transactional
  public UserAccount register(String email, String password, String displayName, GuestSession guest) {
    var normalized = normalize(email);
    if (users.findByEmailNormalized(normalized).isPresent()) throw new DuplicateAccountException();
    var account = users.save(UserAccount.create(normalized, normalizedName(displayName, normalized), null, guest.getId()));
    identities.save(AuthIdentity.password(account.getId(), normalized, passwords.encode(password)));
    guestIdentities.revoke(guest);
    return account;
  }

  @Transactional
  public UserAccount completePasswordLogin(String email, GuestSession currentGuest) {
    var account = users.findByEmailNormalized(normalize(email)).orElseThrow(() -> new UsernameNotFoundException("invalid credentials"));
    if (!currentGuest.getId().equals(account.getPrimaryGuestSessionId())) {
      assets.merge(currentGuest.getId(), account.getPrimaryGuestSessionId());
      profiles.refresh(guestSessions.findById(account.getPrimaryGuestSessionId()).orElseThrow());
    }
    guestIdentities.revoke(currentGuest);
    return account;
  }

  @Transactional
  public UserAccount completeGithubLogin(String subject, String displayName, String avatarUrl, GuestSession currentGuest) {
    var existing = identities.findByProviderAndProviderSubject("GITHUB", subject);
    UserAccount account;
    if (existing.isPresent()) account = users.findById(existing.get().getUserId()).orElseThrow();
    else {
      account = users.save(UserAccount.create("github-" + subject + "@users.soundseek.local", normalizedName(displayName, "GitHub 用户"), avatarUrl, currentGuest.getId()));
      identities.save(AuthIdentity.github(account.getId(), subject));
    }
    assets.merge(currentGuest.getId(), account.getPrimaryGuestSessionId());
    if (!currentGuest.getId().equals(account.getPrimaryGuestSessionId())) profiles.refresh(guestSessions.findById(account.getPrimaryGuestSessionId()).orElseThrow());
    guestIdentities.revoke(currentGuest);
    return account;
  }

  @Transactional(readOnly = true) public Optional<UserAccount> current(String username) { return username == null ? Optional.empty() : users.findByEmailNormalized(normalize(username)); }

  @Override @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    var account = users.findByEmailNormalized(normalize(username)).orElseThrow(() -> new UsernameNotFoundException("invalid credentials"));
    var password = identities.findByUserIdAndProvider(account.getId(), "PASSWORD").map(AuthIdentity::getPasswordHash).orElse("{noop}oauth-only-account");
    return User.withUsername(account.getEmailNormalized()).password(password).roles("USER").disabled(!"ACTIVE".equals(account.getStatus())).build();
  }

  static String normalize(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }
  private String normalizedName(String value, String fallback) { var trimmed = value == null ? "" : value.trim(); var selected = trimmed.isBlank() ? fallback : trimmed; return selected.substring(0, Math.min(selected.length(), 80)); }
  public static class DuplicateAccountException extends DataIntegrityViolationException { public DuplicateAccountException() { super("account already exists"); } }
}
