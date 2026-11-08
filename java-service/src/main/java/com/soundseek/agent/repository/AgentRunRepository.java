package com.soundseek.agent.repository;
import com.soundseek.agent.domain.AgentRun; import com.soundseek.agent.domain.AgentRunStatus; import com.soundseek.agent.domain.AgentRunType; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface AgentRunRepository extends JpaRepository<AgentRun,UUID> {
 Optional<AgentRun> findByIdAndGuestSessionId(UUID id,UUID guestSessionId);
 Optional<AgentRun> findTopByTournamentIdAndRunTypeOrderByCreatedAtDesc(UUID tournamentId,AgentRunType runType);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from AgentRun r where r.id=:id") Optional<AgentRun> findLockedById(@Param("id") UUID id);
 List<AgentRun> findByConversationIdAndStatusInOrderByStartedAtDesc(UUID conversationId,Collection<AgentRunStatus> statuses);
}
