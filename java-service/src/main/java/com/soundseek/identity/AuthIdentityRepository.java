package com.soundseek.identity;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, UUID> {
  Optional<AuthIdentity> findByProviderAndProviderSubject(String provider, String subject);
  Optional<AuthIdentity> findByUserIdAndProvider(UUID userId, String provider);
}
