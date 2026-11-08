package com.soundseek.tournament.repository;

import com.soundseek.tournament.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TournamentRepository extends JpaRepository<Tournament, UUID> {
  Optional<Tournament> findByIdAndGuestSessionIdAndDeletedAtIsNull(UUID id, UUID guestSessionId);
  Optional<Tournament> findByGuestSessionIdAndCreationIdempotencyKey(UUID guestSessionId, String creationIdempotencyKey);
  List<Tournament> findByGuestSessionIdAndStatusAndDeletedAtIsNullOrderByCompletedAtAsc(UUID guestSessionId, TournamentStatus status);
}
