package com.indiesoundquest.knowledge;

import com.indiesoundquest.identity.AccountService;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Explicit operator allowlist; never grants authority to the first registered user. */
@Component
public class KnowledgeAccess {
  private final Set<String> maintainers;
  private final AccountService accounts;
  public KnowledgeAccess(AccountService accounts, @Value("${knowledge.maintainers:}") String configured) {
    this.accounts = accounts;
    maintainers = new HashSet<>();
    for (String value : configured.split(",")) if (!value.isBlank()) maintainers.add(value.trim().toLowerCase(Locale.ROOT));
  }
  public String require(Principal principal) {
    if (principal == null || !maintainers.contains(principal.getName().toLowerCase(Locale.ROOT)) ||
        accounts.current(principal.getName()).filter(a -> "ACTIVE".equals(a.getStatus())).isEmpty())
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "KNOWLEDGE_MAINTAINER_REQUIRED");
    return principal.getName().toLowerCase(Locale.ROOT);
  }
}
