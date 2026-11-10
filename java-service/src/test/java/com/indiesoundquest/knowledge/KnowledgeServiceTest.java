package com.indiesoundquest.knowledge;

import static com.indiesoundquest.knowledge.KnowledgeContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indiesoundquest.async.AsyncOutboxEventRepository;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class KnowledgeServiceTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
  static KnowledgeService service;
  static JdbcTemplate db;
  @BeforeAll static void schema() throws Exception {
    var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
    db=new JdbcTemplate(ds);
    try(var connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V15__create_async_outbox.sql"));ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V23__music_evidence_knowledge.sql"));ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V24__pin_knowledge_model_revision.sql"));}
    db.execute("CREATE TABLE user_account(id VARCHAR(36),email_normalized VARCHAR(320))");
    db.update("INSERT INTO user_account VALUES('operator','maintainer@example.com')");
    var mapper=new ObjectMapper();var outbox=mock(AsyncOutboxEventRepository.class);
    when(outbox.save(any(com.indiesoundquest.async.AsyncOutboxEvent.class))).thenAnswer(call->{
      var event=call.getArgument(0,com.indiesoundquest.async.AsyncOutboxEvent.class);String job=mapper.readTree(event.getPayloadJson()).get("jobId").asText();
      db.update("INSERT INTO async_outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,next_attempt_at) VALUES(UUID_TO_BIN(?),'KNOWLEDGE_INDEX',UUID_TO_BIN(?),'KNOWLEDGE_INDEX_REQUESTED',?,'PENDING',CURRENT_TIMESTAMP(3))",event.getId().toString(),job,event.getPayloadJson());return event;
    });
    when(outbox.republishKnowledge(any(UUID.class),any(java.time.Instant.class))).thenAnswer(call->db.update("UPDATE async_outbox_event SET status='PENDING' WHERE aggregate_type='KNOWLEDGE_INDEX' AND aggregate_id=UUID_TO_BIN(?)",call.getArgument(0,UUID.class).toString()));
    service=new KnowledgeService(db,mapper,Validation.buildDefaultValidatorFactory().getValidator(),outbox);
  }
  Submission item(String summary) {
    return new Submission("FACT",summary,"仅适用于原作",null,"WORK",List.of("作品别名"),List.of(new Entity("WORK","work-id","SUBJECT")),List.of(new Support(new Source("source-"+UUID.randomUUID(),"官方采访","https://example.org/interview","发行方","zh",null,"允许保存必要短摘录",null,"INTERVIEW"),"作者在采访中明确介绍了这首作品的创作背景。","创作背景段落","SUPPORTS")),null);
  }
  @SuppressWarnings("unchecked") String ingest(Submission content,String key) {
    var result=service.ingest("maintainer@example.com",key,new ImportRequest("MANUAL_EDIT",List.of(content)));
    return (String)((List<Map<String,Object>>)result.get("results")).getFirst().get("entryId");
  }
  @Test void publicationRequiresReviewAndRetractionRejectsStaleHits() {
    String entry=ingest(item("这首作品的创作背景来自公开采访。"),UUID.randomUUID().toString());
    assertThrows(IllegalStateException.class,()->service.publish("operator",entry,1));
    service.review("operator",entry,new Decision(1,"APPROVED","已核对实体和原文"));service.publish("operator",entry,1);
    assertEquals(1,service.verify(new Verify(List.of(new Hit(entry,1)),null)).size());
    service.retract("operator",entry,"资料需要修订");
    assertTrue(service.verify(new Verify(List.of(new Hit(entry,1)),null)).isEmpty());
    assertEquals("unavailable",service.publicEvidence(entry,1).get("status"));
  }
  @Test void revisionNeverSubstitutesNewTextForOldVector() {
    String entry=ingest(item("作品背景版本一"),UUID.randomUUID().toString());
    service.review("operator",entry,new Decision(1,"APPROVED","核对完成"));service.publish("operator",entry,1);
    service.revise("operator",entry,1,item("作品背景版本二"));
    assertEquals(1,service.verify(new Verify(List.of(new Hit(entry,1)),null)).size());
    service.review("operator",entry,new Decision(2,"APPROVED","核对修订"));service.publish("operator",entry,2);
    assertTrue(service.verify(new Verify(List.of(new Hit(entry,1)),null)).isEmpty());
    assertEquals("作品背景版本二",service.verify(new Verify(List.of(new Hit(entry,2)),null)).getFirst().get("statement"));
    assertThrows(IllegalStateException.class,()->service.revise("operator",entry,1,item("冲突")));
  }
  @Test void idempotentImportsAndManualOnlyBoundary() {
    var item=item("导入幂等事实");String key=UUID.randomUUID().toString();
    assertEquals(ingest(item,key),ingest(item,key));
    assertThrows(IllegalStateException.class,()->ingest(item("不同内容"),key));
    assertThrows(IllegalArgumentException.class,()->service.ingest("operator","auto",new ImportRequest("AUTOMATED_COLLECTOR",List.of(item))));
  }
  @Test void indexLeaseCannotBeCompletedByAnotherWorker() {
    String entry=ingest(item("索引租约事实"),UUID.randomUUID().toString());
    service.review("operator",entry,new Decision(1,"APPROVED","核对完成"));service.publish("operator",entry,1);
    String job=(String)db.queryForMap("SELECT id FROM knowledge_index_job WHERE entry_id=? AND operation='UPSERT'",entry).get("id");
    var lease=service.claim(job,"worker-a");assertEquals("busy",service.claim(job,"worker-b").get("status"));
    assertThrows(IllegalStateException.class,()->service.finish(job,"wrong",new Finish(true,null)));
    service.finish(job,(String)lease.get("leaseToken"),new Finish(true,null));
    assertEquals("skip",service.claim(job,"worker-c").get("status"));
  }
  @Test void entityFilteringPreventsCrossWorkAnswers() {
    String entry=ingest(item("同名作品的背景事实"),UUID.randomUUID().toString());
    service.review("operator",entry,new Decision(1,"APPROVED","核对完成"));service.publish("operator",entry,1);
    var request=new Search("背景",List.of(new Entity("WORK","different-work","SUBJECT")),"STRICT",List.of("FACT"),6);
    assertTrue(service.verify(new Verify(List.of(new Hit(entry,1)),request)).isEmpty());
  }
  @Test void sourceRetractionImmediatelyInvalidatesKnowledge() {
    String entry=ingest(item("来源撤回的验证材料"),UUID.randomUUID().toString());
    service.review("operator",entry,new Decision(1,"APPROVED","核对完成"));service.publish("operator",entry,1);
    String source=(String)db.queryForMap("SELECT source_id FROM knowledge_evidence_link WHERE entry_id=?",entry).get("source_id");
    service.retractSource("operator",source,"来源已失效");
    assertTrue(service.verify(new Verify(List.of(new Hit(entry,1)),null)).isEmpty());
    assertThrows(IllegalStateException.class,()->service.publish("operator",entry,1));
  }
  @Test void retractedVersionCannotBeRepublishedAgainstOldDeleteTask() {
    String entry=ingest(item("撤回重发的验证材料"),UUID.randomUUID().toString());
    service.review("operator",entry,new Decision(1,"APPROVED","核对完成"));service.publish("operator",entry,1);
    service.retract("operator",entry,"需要重新审核");
    assertThrows(IllegalStateException.class,()->service.publish("operator",entry,1));
  }
  @Test void reconciliationAndManualRetryReuseTheUniqueOutboxEvent() {
    String entry=ingest(item("对账与重试的验证材料"),UUID.randomUUID().toString());
    service.review("operator",entry,new Decision(1,"APPROVED","核对完成"));service.publish("operator",entry,1);
    String job=(String)db.queryForMap("SELECT id FROM knowledge_index_job WHERE entry_id=? AND operation='UPSERT'",entry).get("id");
    service.reconcile();service.reconcile();service.retry("operator",job);
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM async_outbox_event WHERE aggregate_type='KNOWLEDGE_INDEX' AND aggregate_id=UUID_TO_BIN(?)",Integer.class,job));
  }
}
