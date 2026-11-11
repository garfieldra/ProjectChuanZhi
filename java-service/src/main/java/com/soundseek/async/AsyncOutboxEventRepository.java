package com.soundseek.async;
import java.time.Instant; import java.util.*; import org.springframework.data.jpa.repository.*;
public interface AsyncOutboxEventRepository extends JpaRepository<AsyncOutboxEvent,UUID>{
 List<AsyncOutboxEvent> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(String status, Instant now);
 @Modifying(flushAutomatically=true)
 @Query("update AsyncOutboxEvent e set e.status='PENDING', e.nextAttemptAt=?2, e.publishedAt=null, e.updatedAt=?2 where e.aggregateType='KNOWLEDGE_INDEX' and e.aggregateId=?1")
 int republishKnowledge(UUID jobId,Instant now);
}
