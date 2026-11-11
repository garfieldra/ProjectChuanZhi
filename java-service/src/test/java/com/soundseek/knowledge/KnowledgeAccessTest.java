package com.soundseek.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.soundseek.identity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class KnowledgeAccessTest {
  @Test void noImplicitFirstUserOrGuestPrivileges() {
    var accounts=mock(AccountService.class);var disabled=new KnowledgeAccess(accounts,"");
    assertThrows(ResponseStatusException.class,()->disabled.require(()->"operator@example.com"));
    var access=new KnowledgeAccess(accounts,"operator@example.com");
    assertThrows(ResponseStatusException.class,()->access.require(()->"guest:fake"));
    when(accounts.current("operator@example.com")).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class,()->access.require(()->"operator@example.com"));
  }
  @Test void onlyActiveExplicitlyListedAccountAllowed() {
    var accounts=mock(AccountService.class);var user=UserAccount.create("operator@example.com","维护者",null,UUID.randomUUID());
    String id=user.getId().toString();var access=new KnowledgeAccess(accounts,id);
    when(accounts.current(id)).thenReturn(Optional.of(user));
    assertThrows(ResponseStatusException.class,()->access.require(()->id));
    user.verifyEmail();assertEquals(id,access.require(()->id));
    user.disable();assertThrows(ResponseStatusException.class,()->access.require(()->id));
  }
}
