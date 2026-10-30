package com.soundseek.agent.repository;

import com.soundseek.agent.domain.AgentRunAttempt;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunAttemptRepository extends JpaRepository<AgentRunAttempt, UUID> {
  Optional<AgentRunAttempt> findTopByRunIdOrderByAttemptNoDesc(UUID runId);
  Optional<AgentRunAttempt> findByRunIdAndLeaseToken(UUID runId, String leaseToken);
  List<AgentRunAttempt> findTop50ByStatusAndLeaseExpiresAtLessThanEqualOrderByLeaseExpiresAtAsc(String status,java.time.Instant now);
}
