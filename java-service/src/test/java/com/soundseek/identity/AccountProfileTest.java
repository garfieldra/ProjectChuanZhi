package com.soundseek.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountProfileTest {
  @Test void renamePersistsOnlyDisplayNameOnTheAuthenticatedAccount() {
    var users = mock(UserAccountRepository.class);
    var account = UserAccount.create("listener@example.test", "Original", null, UUID.randomUUID());
    var id=account.getId();var guest=account.getPrimaryGuestSessionId();
    when(users.findByEmailNormalized("listener@example.test")).thenReturn(Optional.of(account));
    when(users.save(account)).thenReturn(account);
    var service = new AccountService(users,null,null,null,null,null,null);
    assertEquals("新称呼",service.updateProfile("listener@example.test"," 新称呼 ").getDisplayName());
    assertEquals(id,account.getId());assertEquals(guest,account.getPrimaryGuestSessionId());assertEquals("listener@example.test",account.getEmailNormalized());verify(users).save(account);
    assertThrows(IllegalArgumentException.class,()->account.rename("   "));
    assertThrows(IllegalArgumentException.class,()->account.rename("a".repeat(81)));
  }
  @Test void profileEndpointRejectsGuestAndInvalidInput() throws Exception {
    var accounts=mock(AccountService.class);
    @SuppressWarnings("unchecked") ObjectProvider<ClientRegistrationRepository> clients=mock(ObjectProvider.class);
    var mvc=MockMvcBuilders.standaloneSetup(new AuthController(accounts,null,null,null,clients)).build();
    mvc.perform(patch("/api/v1/auth/profile").contentType("application/json").content("{\"displayName\":\"Name\"}")).andExpect(status().isUnauthorized());
    mvc.perform(patch("/api/v1/auth/profile").principal(()->"guest:test").contentType("application/json").content("{\"displayName\":\"Name\"}")).andExpect(status().isUnauthorized());
    mvc.perform(patch("/api/v1/auth/profile").principal(()->"listener@example.test").contentType("application/json").content("{\"displayName\":\"  \"}")).andExpect(status().isBadRequest());
    verifyNoInteractions(accounts);
  }
}
