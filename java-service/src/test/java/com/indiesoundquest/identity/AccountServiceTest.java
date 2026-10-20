package com.indiesoundquest.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.indiesoundquest.tournament.application.MusicPreferenceProfileService;
import com.indiesoundquest.tournament.domain.GuestSession;
import com.indiesoundquest.tournament.repository.GuestSessionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountServiceTest {
  private final UserAccountRepository users = mock(UserAccountRepository.class);
  private final AuthIdentityRepository identities = mock(AuthIdentityRepository.class);
  private final PasswordEncoder passwords = mock(PasswordEncoder.class);
  private final GuestAssetMergeService assets = mock(GuestAssetMergeService.class);
  private final GuestSessionRepository guestSessions = mock(GuestSessionRepository.class);
  private final MusicPreferenceProfileService profiles = mock(MusicPreferenceProfileService.class);
  private final GuestIdentityService guestIdentities = mock(GuestIdentityService.class);
  private final AccountService service = new AccountService(users, identities, passwords, assets, guestSessions, profiles, guestIdentities);

  @Test
  void registrationRevokesGuestTokenAfterAttachingAssets() {
    var guest = new GuestSession(UUID.randomUUID(), "a".repeat(64));
    when(users.findByEmailNormalized("listener@example.com")).thenReturn(Optional.empty());
    when(users.save(any())).thenAnswer(call -> call.getArgument(0));
    when(passwords.encode("long-enough-password")).thenReturn("hash");

    service.register(" Listener@Example.com ", "long-enough-password", "Listener", guest);

    verify(guestIdentities).revoke(guest);
    verify(assets, never()).merge(any(), any());
  }

  @Test
  void loginFromPrimaryGuestDoesNotRebuildAssetsButRevokesBearerToken() {
    var guest = new GuestSession(UUID.randomUUID(), "b".repeat(64));
    var account = UserAccount.create("listener@example.com", "Listener", null, guest.getId());
    when(users.findByEmailNormalized("listener@example.com")).thenReturn(Optional.of(account));

    service.completePasswordLogin("listener@example.com", guest);

    verifyNoInteractions(assets, profiles);
    verify(guestIdentities).revoke(guest);
  }

  @Test
  void loginFromAnotherGuestMergesAndRefreshesDerivedProfile() {
    var source = new GuestSession(UUID.randomUUID(), "c".repeat(64));
    var target = new GuestSession(UUID.randomUUID(), "d".repeat(64));
    var account = UserAccount.create("listener@example.com", "Listener", null, target.getId());
    when(users.findByEmailNormalized("listener@example.com")).thenReturn(Optional.of(account));
    when(guestSessions.findById(target.getId())).thenReturn(Optional.of(target));

    service.completePasswordLogin("listener@example.com", source);

    verify(assets).merge(source.getId(), target.getId());
    verify(profiles).refresh(target);
    verify(guestIdentities).revoke(source);
  }
}
