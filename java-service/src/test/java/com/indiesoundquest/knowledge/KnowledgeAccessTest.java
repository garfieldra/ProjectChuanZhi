package com.indiesoundquest.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.indiesoundquest.identity.*;
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
    var accounts=mock(AccountService.class);var access=new KnowledgeAccess(accounts," OPERATOR@example.com ");
    when(accounts.current("operator@example.com")).thenReturn(Optional.of(UserAccount.create("operator@example.com","维护者",null,UUID.randomUUID())));
    assertEquals("operator@example.com",access.require(()->"operator@example.com"));
  }
}
