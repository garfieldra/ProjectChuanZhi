package com.soundseek.identity;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
  Optional<UserAccount> findByEmailNormalized(String email);
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select u from UserAccount u where u.id=:id")
  Optional<UserAccount> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
}
