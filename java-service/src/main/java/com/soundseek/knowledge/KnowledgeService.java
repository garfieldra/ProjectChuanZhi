package com.soundseek.knowledge;

import static com.soundseek.knowledge.KnowledgeContracts.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soundseek.async.*;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL is the authority. Index rows are hints, never evidence on their own. */
@Service
public class KnowledgeService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Validator validator;
  private final AsyncOutboxEventRepository outbox;
  private final KnowledgeIndexPreflight preflight;
  public KnowledgeService(JdbcTemplate db, ObjectMapper json, Validator validator, AsyncOutboxEventRepository outbox, KnowledgeIndexPreflight preflight) {
    this.db=db; this.json=json; this.validator=validator; this.outbox=outbox; this.preflight=preflight;
  }
  String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("INVALID_KNOWLEDGE_JSON"); } }
  Map<String,Object> read(Object value) { try { return json.readValue(String.valueOf(value),new TypeReference<LinkedHashMap<String,Object>>(){}); } catch(Exception e) { throw new IllegalStateException("KNOWLEDGE_DATA_INVALID"); } }
  public static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
  private String id() { return UUID.randomUUID().toString(); }
  private Map<String,Object> one(String sql,Object... args) { var rows=db.queryForList(sql,args); if(rows.isEmpty())throw new NoSuchElementException(); return rows.getFirst(); }
  private int number(Object value) { return ((Number)value).intValue(); }
  private void audit(String actor,String object,String action,Object detail) { db.update("INSERT INTO knowledge_audit_event(id,actor,object_id,action,detail_json) VALUES(?,?,?,?,?)",id(),actor,object,action,write(detail)); }
  private void validate(Submission item) {
    if(item==null)throw new IllegalArgumentException("KNOWLEDGE_ITEM_REQUIRED");
    var errors=validator.validate(item); if(!errors.isEmpty())throw new IllegalArgumentException("KNOWLEDGE_FIELDS_INVALID: "+errors.iterator().next().getPropertyPath());
    if (item.support().stream().noneMatch(s -> "SUPPORTS".equals(s.relation())))throw new IllegalArgumentException("SUPPORTING_MATERIAL_REQUIRED");
    if ("ATTRIBUTED_STATEMENT".equals(item.knowledgeType()) && (item.attribution()==null || item.attribution().isBlank()))throw new IllegalArgumentException("ATTRIBUTION_REQUIRED");
    for(var support:item.support()) {
      var url=support.source().url();
      if(url!=null && !url.isBlank()) {
        java.net.URI uri; try { uri=java.net.URI.create(url); } catch(Exception e) { throw new IllegalArgumentException("SOURCE_URL_INVALID"); }
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)throw new IllegalArgumentException("SOURCE_URL_INVALID");
      }
    }
    if(new HashSet<>(item.entities()).size()!=item.entities().size())throw new IllegalArgumentException("DUPLICATE_ENTITY");
  }

  @Transactional
  public Map<String,Object> ingest(String actor,String key,ImportRequest input) {
    if(key==null || key.isBlank() || key.length()>100)throw new IllegalArgumentException("IDEMPOTENCY_KEY_REQUIRED");
    if(input==null || input.origin()==null || !Set.of("MANUAL_FILE","MANUAL_URL","MANUAL_EDIT").contains(input.origin()) || input.candidateItems()==null || input.candidateItems().isEmpty() || input.candidateItems().size()>100)throw new IllegalArgumentException("MANUAL_SUBMISSION_REQUIRED");
    String fingerprint=hash(write(input));
    // Serialize same-actor imports, including concurrent retries, using the existing account row.
    db.queryForList("SELECT id FROM user_account WHERE id=UNHEX(REPLACE(?,'-','')) OR email_normalized=? FOR UPDATE",actor,actor);
    var previous=db.queryForList("SELECT content_hash,payload_json FROM knowledge_ingestion_job WHERE actor=? AND idempotency_key=?",actor,key);
    if(!previous.isEmpty()) {
      if(!fingerprint.equals(previous.getFirst().get("content_hash")))throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED");
      return read(previous.getFirst().get("payload_json"));
    }
    var results=new ArrayList<Map<String,Object>>();
    for(int index=0;index<input.candidateItems().size();index++) {
      var item=input.candidateItems().get(index);
      try { validate(item); } catch(IllegalArgumentException e) { results.add(Map.of("index",index,"status","REJECTED","error",e.getMessage())); continue; }
      String entry=id();
      db.update("INSERT INTO knowledge_entry(id,knowledge_type,current_revision,status) VALUES(?,?,1,'UNPUBLISHED')",entry,item.knowledgeType());
      saveRevision(entry,1,item,actor);
      audit(actor,entry,"IMPORTED",Map.of("origin",input.origin()));
      results.add(Map.of("index",index,"status","DRAFT","entryId",entry,"revision",1));
    }
    String job=id(); var result=Map.<String,Object>of("jobId",job,"results",results);
    db.update("INSERT INTO knowledge_ingestion_job(id,actor,idempotency_key,content_hash,origin,payload_json) VALUES(?,?,?,?,?,?)",job,actor,key,fingerprint,input.origin(),write(result));
    return result;
  }
  private void saveRevision(String entry,int revision,Submission item,String actor) {
    String payload=write(item);
    db.update("INSERT INTO knowledge_entry_revision(entry_id,revision,summary,payload_json,content_hash,review_status,created_by) VALUES(?,?,?,?,?,'DRAFT',?)",entry,revision,item.summary(),payload,hash(payload),actor);
    for(var entity:item.entities())db.update("INSERT INTO knowledge_entry_entity(entry_id,revision,entity_type,entity_id,role) VALUES(?,?,?,?,?)",entry,revision,entity.type(),entity.id(),entity.role()==null?"SUBJECT":entity.role());
    for(var support:item.support()) {
      String sourceKey=hash(support.source().identifier());
      String candidate=id();
      db.update("INSERT INTO knowledge_source(id,source_key,current_revision,status) VALUES(?,?,0,'ACTIVE') ON DUPLICATE KEY UPDATE source_key=VALUES(source_key)",candidate,sourceKey);
      var source=one("SELECT * FROM knowledge_source WHERE source_key=? FOR UPDATE",sourceKey);
      if(!"ACTIVE".equals(source.get("status")))throw new IllegalStateException("SOURCE_RETRACTED");
      String sourceId=(String)source.get("id"), sourcePayload=write(support.source());
      String contentHash=hash(sourcePayload+"\n"+support.excerpt());
      // Each retained excerpt is immutable. Distinct excerpts may share a URL and still retain their own version.
      var existing=db.queryForList("SELECT revision FROM knowledge_source_revision WHERE source_id=? AND content_hash=?",sourceId,contentHash);
      int sourceRevision;
      if(existing.isEmpty()) {
        sourceRevision=number(source.get("current_revision"))+1;
        db.update("INSERT INTO knowledge_source_revision(source_id,revision,content_hash,payload_json) VALUES(?,?,?,?)",sourceId,sourceRevision,contentHash,sourcePayload);
        db.update("UPDATE knowledge_source SET current_revision=? WHERE id=?",sourceRevision,sourceId);
      } else sourceRevision=number(existing.getFirst().get("revision"));
      db.update("INSERT INTO knowledge_evidence_link(id,entry_id,revision,source_id,source_revision,relation_type,excerpt,locator) VALUES(?,?,?,?,?,?,?,?)",id(),entry,revision,sourceId,sourceRevision,support.relation(),support.excerpt(),support.locator());
    }
  }
  @Transactional
  public Map<String,Object> revise(String actor,String entry,int expected,Submission item) {
    validate(item); var current=one("SELECT * FROM knowledge_entry WHERE id=? FOR UPDATE",entry);
    if(number(current.get("current_revision"))!=expected)throw new IllegalStateException("REVISION_CONFLICT");
    if(!item.knowledgeType().equals(current.get("knowledge_type")))throw new IllegalArgumentException("KNOWLEDGE_TYPE_IMMUTABLE");
    int revision=expected+1; saveRevision(entry,revision,item,actor);
    db.update("UPDATE knowledge_entry SET current_revision=? WHERE id=?",revision,entry);
    audit(actor,entry,"REVISED",Map.of("revision",revision)); return detail(entry);
  }
  @Transactional
  public Map<String,Object> review(String actor,String entry,Decision decision) {
    one("SELECT * FROM knowledge_entry WHERE id=? FOR UPDATE",entry);
    int changed=db.update("UPDATE knowledge_entry_revision SET review_status=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP(3),review_note=? WHERE entry_id=? AND revision=? AND review_status='DRAFT'",decision.status(),actor,decision.note(),entry,decision.revision());
    if(changed!=1)throw new IllegalStateException("REVIEW_REVISION_INVALID");
    audit(actor,entry,"REVIEWED",decision); return detail(entry);
  }
  @Transactional
  public Map<String,Object> publish(String actor,String entry,int revision) {
    one("SELECT * FROM knowledge_index_generation WHERE status='ACTIVE' FOR UPDATE");
    var current=one("SELECT * FROM knowledge_entry WHERE id=? FOR UPDATE",entry);
    if("RETRACTED".equals(current.get("status")) && current.get("published_revision")!=null && revision<=number(current.get("published_revision")))throw new IllegalStateException("RETRACTED_REVISION_REQUIRES_REVISION");
    if(number(current.get("current_revision"))!=revision)throw new IllegalStateException("REVISION_CONFLICT");
    var version=one("SELECT review_status FROM knowledge_entry_revision WHERE entry_id=? AND revision=?",entry,revision);
    if(!"APPROVED".equals(version.get("review_status")))throw new IllegalStateException("APPROVAL_REQUIRED");
    if(!sourceActive(entry,revision))throw new IllegalStateException("SOURCE_RETRACTED");
    Object previous=current.get("published_revision");
    if("PUBLISHED".equals(current.get("status")) && previous!=null && number(previous)==revision)return detail(entry);
    var content=read(one("SELECT payload_json FROM knowledge_entry_revision WHERE entry_id=? AND revision=?",entry,revision).get("payload_json"));
    for(var generation:generations())preflight.validate(content,read(generation.get("payload_json")));
    db.update("UPDATE knowledge_entry SET published_revision=?,status='PUBLISHED',retraction_reason=NULL WHERE id=?",revision,entry);
    for(var generation:generations()) {
      int g=number(generation.get("id")); enqueue(entry,revision,g,"UPSERT");
      if(previous!=null && number(previous)!=revision)enqueue(entry,number(previous),g,"DELETE");
    }
    audit(actor,entry,"PUBLISHED",Map.of("revision",revision)); return detail(entry);
  }
  @Transactional
  public void retract(String actor,String entry,String reason) {
    one("SELECT * FROM knowledge_index_generation WHERE status='ACTIVE' FOR UPDATE");
    var current=one("SELECT * FROM knowledge_entry WHERE id=? FOR UPDATE",entry);
    db.update("UPDATE knowledge_entry SET status='RETRACTED',retraction_reason=? WHERE id=?",reason,entry);
    if(current.get("published_revision")!=null)for(var g:generations())enqueue(entry,number(current.get("published_revision")),number(g.get("id")),"DELETE");
    audit(actor,entry,"RETRACTED",Map.of("reason",reason));
  }
  @Transactional public void retractSource(String actor,String source,String reason) {
    one("SELECT id FROM knowledge_source WHERE id=? FOR UPDATE",source);
    db.update("UPDATE knowledge_source SET status='RETRACTED' WHERE id=?",source);
    for(var entry:db.queryForList("SELECT DISTINCT l.entry_id,l.revision FROM knowledge_evidence_link l JOIN knowledge_entry e ON e.id=l.entry_id AND e.published_revision=l.revision WHERE l.source_id=?",source))for(var g:generations())enqueue((String)entry.get("entry_id"),number(entry.get("revision")),number(g.get("id")),"DELETE");
    audit(actor,source,"SOURCE_RETRACTED",Map.of("reason",reason));
  }
  private boolean sourceActive(String entry,int revision) {
    Integer bad=db.queryForObject("SELECT COUNT(*) FROM knowledge_evidence_link l JOIN knowledge_source s ON s.id=l.source_id WHERE l.entry_id=? AND l.revision=? AND s.status<>'ACTIVE'",Integer.class,entry,revision);
    return bad!=null && bad==0;
  }
  public List<Map<String,Object>> list() { return db.queryForList("SELECT e.*,r.summary,r.review_status FROM knowledge_entry e JOIN knowledge_entry_revision r ON r.entry_id=e.id AND r.revision=e.current_revision ORDER BY e.created_at DESC LIMIT 200"); }
  public Map<String,Object> detail(String entry) {
    var value=one("SELECT * FROM knowledge_entry WHERE id=?",entry);
    var versions=db.queryForList("SELECT * FROM knowledge_entry_revision WHERE entry_id=? ORDER BY revision DESC",entry);
    for(var version:versions)version.put("content",read(version.remove("payload_json")));
    value.put("versions",versions); return value;
  }
  public List<Map<String,Object>> sources() { var rows=db.queryForList("SELECT s.*,r.payload_json FROM knowledge_source s JOIN knowledge_source_revision r ON r.source_id=s.id AND r.revision=s.current_revision LIMIT 200"); for(var row:rows)row.put("content",read(row.remove("payload_json")));return rows; }
  public List<Map<String,Object>> jobs() { return db.queryForList("SELECT * FROM knowledge_index_job ORDER BY created_at DESC LIMIT 200"); }
  public List<Map<String,Object>> auditLog() { return db.queryForList("SELECT * FROM knowledge_audit_event ORDER BY created_at DESC LIMIT 200"); }
  public List<Map<String,Object>> generations() { return db.queryForList("SELECT * FROM knowledge_index_generation ORDER BY id"); }
  public Map<String,Object> activeGeneration() { var row=one("SELECT * FROM knowledge_index_generation WHERE status='ACTIVE'");row.put("config",read(row.remove("payload_json")));return row; }
  public Map<String,Object> generation(int id) {var row=one("SELECT * FROM knowledge_index_generation WHERE id=?",id);row.put("config",read(row.remove("payload_json")));return row;}

  public List<Map<String,Object>> exact(Search request) {
    // Bound the candidate set using entity indexes before doing application-level alias matching.
    String sql="SELECT e.id,e.published_revision,r.summary,r.payload_json FROM knowledge_entry e JOIN knowledge_entry_revision r ON r.entry_id=e.id AND r.revision=e.published_revision WHERE e.status='PUBLISHED' AND r.review_status='APPROVED' AND NOT EXISTS(SELECT 1 FROM knowledge_evidence_link l JOIN knowledge_source s ON s.id=l.source_id WHERE l.entry_id=e.id AND l.revision=e.published_revision AND s.status<>'ACTIVE')";
    var args=new ArrayList<Object>();
    if(request.entities()!=null && !request.entities().isEmpty()) {
      sql+=" AND EXISTS(SELECT 1 FROM knowledge_entry_entity x WHERE x.entry_id=e.id AND x.revision=e.published_revision AND (";
      var parts=new ArrayList<String>(); for(var entity:request.entities()) { parts.add("(x.entity_type=? AND x.entity_id=?)");args.add(entity.type());args.add(entity.id()); }
      sql+=String.join(" OR ",parts)+")"+("STRICT".equals(request.entityScope())?" AND x.role='SUBJECT'":"")+")";
    }
    sql+=" ORDER BY e.created_at DESC LIMIT 500";
    var rows=db.queryForList(sql,args.toArray());
    var hits=new ArrayList<Map<String,Object>>();
    for(var row:rows) {
      var payload=read(row.get("payload_json"));
      String text=(String.valueOf(payload.get("summary"))+" "+write(payload.get("aliases"))+" "+write(payload.get("theme"))).toLowerCase(Locale.ROOT), query=request.query().toLowerCase(Locale.ROOT).trim();
      boolean aliasMatch=payload.get("aliases") instanceof List<?> aliases && aliases.stream().anyMatch(alias -> String.valueOf(alias).length()>1 && query.contains(String.valueOf(alias).toLowerCase(Locale.ROOT)));
      boolean entityMatch=request.entities()!=null&&!request.entities().isEmpty();
      if(entityMatch || aliasMatch || text.contains(query) || Arrays.stream(query.split("\\s+")).filter(s->s.length()>1).anyMatch(text::contains)) {
        if(request.knowledgeTypes()!=null&&!request.knowledgeTypes().isEmpty()&&!request.knowledgeTypes().contains(payload.get("knowledgeType")))continue;
        hits.add(Map.of("entryId",row.get("id"),"revision",row.get("published_revision")));
      }
      if(hits.size()>=request.limit())break;
    }
    return hits;
  }
  public Search expand(Search request) {
    if(!"RELATED_CONTEXT".equals(request.entityScope()) || request.entities()==null || request.entities().isEmpty())return request;
    var entities=new LinkedHashMap<String,Entity>();
    for(var entity:request.entities())entities.put(entity.type()+":"+entity.id(),entity);
    // Only one hop through an explicitly reviewed, published relationship binding.
    // Shared creators alone never imply that two works share production facts.
    for(var original:request.entities()) {
      var related=db.queryForList("SELECT DISTINCT b.entity_type,b.entity_id,b.role FROM knowledge_entry_entity a JOIN knowledge_entry e ON e.id=a.entry_id AND e.published_revision=a.revision JOIN knowledge_entry_revision r ON r.entry_id=e.id AND r.revision=e.published_revision JOIN knowledge_entry_entity b ON b.entry_id=a.entry_id AND b.revision=a.revision WHERE a.entity_type=? AND a.entity_id=? AND a.role='SUBJECT' AND b.role IN ('ALBUM_CONTEXT','RELATED_WORK') AND e.status='PUBLISHED' AND r.review_status='APPROVED' AND NOT EXISTS(SELECT 1 FROM knowledge_evidence_link l JOIN knowledge_source s ON s.id=l.source_id WHERE l.entry_id=e.id AND l.revision=e.published_revision AND s.status<>'ACTIVE')",original.type(),original.id());
      for(var row:related) {
        if(entities.size()>=20)break;
        var entity=new Entity((String)row.get("entity_type"),(String)row.get("entity_id"),(String)row.get("role"));
        entities.putIfAbsent(entity.type()+":"+entity.id(),entity);
      }
    }
    return new Search(request.query(),new ArrayList<>(entities.values()),request.entityScope(),request.knowledgeTypes(),request.limit());
  }
  public List<Map<String,Object>> verify(Verify input) {
    var output=new ArrayList<Map<String,Object>>(); var seen=new HashSet<String>();
    for(var hit:input.hits()) {
      String key=hit.entryId()+":"+hit.revision(); if(!seen.add(key))continue;
      var rows=db.queryForList("SELECT e.knowledge_type,r.payload_json FROM knowledge_entry e JOIN knowledge_entry_revision r ON r.entry_id=e.id AND r.revision=e.published_revision WHERE e.id=? AND e.published_revision=? AND e.status='PUBLISHED' AND r.review_status='APPROVED'",hit.entryId(),hit.revision());
      if(rows.isEmpty() || !sourceActive(hit.entryId(),hit.revision()))continue;
      var payload=read(rows.getFirst().get("payload_json"));
      if(input.search()!=null && !matches(payload,input.search()))continue;
      var supports=new ArrayList<Map<String,Object>>();
      for(var link:db.queryForList("SELECT l.*,r.payload_json,r.created_at AS accessed_at FROM knowledge_evidence_link l JOIN knowledge_source_revision r ON r.source_id=l.source_id AND r.revision=l.source_revision WHERE l.entry_id=? AND l.revision=?",hit.entryId(),hit.revision())) {
        var source=read(link.get("payload_json"));var s=new LinkedHashMap<String,Object>();
        s.put("sourceId",link.get("source_id"));s.put("sourceTitle",source.get("title"));s.put("sourceUrl",source.get("url"));s.put("sourceVersion",link.get("source_revision"));s.put("publisher",source.get("publisher"));s.put("publishedAt",source.get("publishedAt"));s.put("accessedAt",String.valueOf(link.get("accessed_at")));s.put("originGroup",source.get("originGroup"));s.put("materialType",source.get("materialType"));s.put("excerpt",link.get("excerpt"));s.put("locator",link.get("locator"));s.put("relation",link.get("relation_type"));supports.add(s);
      }
      var evidence=new LinkedHashMap<String,Object>();evidence.put("entryId",hit.entryId());evidence.put("revision",hit.revision());evidence.put("origin","KNOWLEDGE");evidence.put("knowledgeType",rows.getFirst().get("knowledge_type"));evidence.put("statement",payload.get("summary"));evidence.put("entities",payload.get("entities"));evidence.put("theme",payload.get("theme"));evidence.put("boundary",payload.get("boundary"));evidence.put("attribution",payload.get("attribution"));evidence.put("applicability",payload.get("applicability"));evidence.put("support",supports);output.add(evidence);
    }
    return output;
  }
  @SuppressWarnings("unchecked") private boolean matches(Map<String,Object> payload,Search request) {
    if(request.knowledgeTypes()!=null&&!request.knowledgeTypes().isEmpty()&&!request.knowledgeTypes().contains(payload.get("knowledgeType")))return false;
    if(request.entities()==null||request.entities().isEmpty())return true;
    for(var item:(List<Map<String,Object>>)payload.get("entities"))for(var wanted:request.entities())if(wanted.type().equals(item.get("type"))&&wanted.id().equals(item.get("id"))&&(!"STRICT".equals(request.entityScope())||item.get("role")==null||"SUBJECT".equals(item.get("role"))))return true;
    return false;
  }
  public Map<String,Object> publicEvidence(String entry,int revision) {
    var current=one("SELECT status,published_revision FROM knowledge_entry WHERE id=?",entry);
    boolean active="PUBLISHED".equals(current.get("status")) && current.get("published_revision")!=null&&number(current.get("published_revision"))==revision;
    if(!active || !sourceActive(entry,revision))return Map.of("status","unavailable","message","资料已更新或撤回");
    var evidence=verify(new Verify(List.of(new Hit(entry,revision)),null));
    return evidence.isEmpty()?Map.of("status","unavailable"):Map.of("status","active","evidence",evidence.getFirst());
  }
  public void assertCardReferences(Map<String,Object> card) {
    if(card==null)return;
    checkEvidenceTree(card);
  }
  private void checkEvidenceTree(Object node) {
    if(node instanceof Map<?,?> map) {
      if(map.get("entryId")!=null && map.get("revision") instanceof Number revision) {
        var hit=new Hit(String.valueOf(map.get("entryId")),revision.intValue());
        if(verify(new Verify(List.of(hit),null)).isEmpty())throw new IllegalStateException("KNOWLEDGE_EVIDENCE_CHANGED");
      }
      for(Object value:map.values())checkEvidenceTree(value);
    } else if(node instanceof List<?> list)for(Object value:list)checkEvidenceTree(value);
  }
  private void enqueue(String entry,int revision,int generation,String operation) {
    String job=id();int inserted=db.update("INSERT IGNORE INTO knowledge_index_job(id,entry_id,revision,generation,operation,status) VALUES(?,?,?,?,?,'PENDING')",job,entry,revision,generation,operation);
    if(inserted==1)outbox.save(AsyncOutboxEvent.pendingKnowledge(UUID.fromString(job),write(Map.of("jobId",job)),job));
  }
  @Transactional public Map<String,Object> claim(String job,String worker) {
    var row=one("SELECT * FROM knowledge_index_job WHERE id=? FOR UPDATE",job);
    if(Set.of("SUCCEEDED","DEAD","SUPERSEDED").contains(row.get("status")))return Map.of("status","skip");
    if(row.get("lease_until") instanceof java.sql.Timestamp until && until.toInstant().isAfter(Instant.now()))return Map.of("status","busy");
    if(row.get("next_attempt_at") instanceof java.sql.Timestamp next && next.toInstant().isAfter(Instant.now()))return Map.of("status","busy");
    if(number(row.get("attempts"))>=5){db.update("UPDATE knowledge_index_job SET status='DEAD',lease_token=NULL,lease_until=NULL,error_code='LEASE_RETRIES_EXHAUSTED' WHERE id=?",job);return Map.of("status","dead");}
    String lease=id(); db.update("UPDATE knowledge_index_job SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?",lease,java.sql.Timestamp.from(Instant.now().plusSeconds(120)),job);
    if("UPSERT".equals(row.get("operation")) && verify(new Verify(List.of(new Hit((String)row.get("entry_id"),number(row.get("revision")))),null)).isEmpty())row.put("operation","DELETE");
    row.put("leaseToken",lease); row.put("config",read(one("SELECT payload_json FROM knowledge_index_generation WHERE id=?",row.get("generation")).get("payload_json")));
    row.put("content",read(one("SELECT payload_json FROM knowledge_entry_revision WHERE entry_id=? AND revision=?",row.get("entry_id"),row.get("revision")).get("payload_json")));
    audit(worker,job,"INDEX_CLAIMED",Map.of());return row;
  }
  @Transactional public Map<String,Object> finish(String job,String lease,Finish finish) {
    var row=one("SELECT * FROM knowledge_index_job WHERE id=? FOR UPDATE",job);
    if(!Objects.equals(lease,row.get("lease_token")) || !"RUNNING".equals(row.get("status")) || !(row.get("lease_until") instanceof java.sql.Timestamp until) || until.toInstant().isBefore(Instant.now()))throw new IllegalStateException("LEASE_LOST");
    String status=finish.success()?"SUCCEEDED":number(row.get("attempts"))>=5?"DEAD":"RETRY_WAIT";
    db.update("UPDATE knowledge_index_job SET status=?,lease_token=NULL,lease_until=NULL,next_attempt_at=?,error_code=?,verified_at=? WHERE id=?",status,java.sql.Timestamp.from(Instant.now().plusSeconds(Math.min(300,5L<<number(row.get("attempts"))))),finish.success()?null:"INDEX_OPERATION_FAILED",finish.success()?java.sql.Timestamp.from(Instant.now()):null,job);
    return Map.of("status",status);
  }
  @Transactional public void heartbeat(String job,String lease) {
    int changed=db.update("UPDATE knowledge_index_job SET lease_until=? WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(3)",java.sql.Timestamp.from(Instant.now().plusSeconds(120)),job,lease);
    if(changed!=1)throw new IllegalStateException("LEASE_LOST");
  }
  @Transactional public void retry(String actor,String job) {
    one("SELECT id FROM knowledge_index_job WHERE id=? FOR UPDATE",job);
    db.update("UPDATE knowledge_index_job SET status='PENDING',attempts=0,lease_token=NULL,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status<>'RUNNING'",job);
    resend(job);audit(actor,job,"INDEX_RETRY",Map.of());
  }
  private void resend(String job) {
    if(outbox.republishKnowledge(UUID.fromString(job),Instant.now())==0)
      outbox.save(AsyncOutboxEvent.pendingKnowledge(UUID.fromString(job),write(Map.of("jobId",job)),job));
  }
  @Transactional public Map<String,Object> createGeneration(String actor,Generation config) {
    one("SELECT * FROM knowledge_index_generation WHERE status='ACTIVE' FOR UPDATE");
    int generation=db.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM knowledge_index_generation",Integer.class);
    var payload=new LinkedHashMap<>(read(write(config)));payload.put("factCollection","soundseek_fact_evidence_g"+generation);payload.put("themeCollection","soundseek_theme_cards_g"+generation);
    db.update("INSERT INTO knowledge_index_generation(id,status,payload_json) VALUES(?,'BUILDING',?)",generation,write(payload));
    reconcile();audit(actor,String.valueOf(generation),"GENERATION_CREATED",payload);return Map.of("id",generation,"config",payload);
  }
  @Transactional public void activate(String actor,int generation) {
    one("SELECT * FROM knowledge_index_generation WHERE status='ACTIVE' FOR UPDATE");one("SELECT id FROM knowledge_index_generation WHERE id=? FOR UPDATE",generation);
    reconcile();
    Integer missing=db.queryForObject("SELECT COUNT(*) FROM knowledge_entry e WHERE e.status='PUBLISHED' AND NOT EXISTS(SELECT 1 FROM knowledge_index_job j WHERE j.entry_id=e.id AND j.revision=e.published_revision AND j.generation=? AND j.operation='UPSERT' AND j.status='SUCCEEDED')",Integer.class,generation);
    if(missing==null||missing>0)throw new IllegalStateException("GENERATION_NOT_CAUGHT_UP");
    Integer reviewed=db.queryForObject("SELECT COUNT(*) FROM knowledge_audit_event WHERE object_id=? AND action='GENERATION_VALIDATED'",Integer.class,String.valueOf(generation));
    if(reviewed==null || reviewed==0)throw new IllegalStateException("GENERATION_QUALITY_REVIEW_REQUIRED");
    db.update("UPDATE knowledge_index_generation SET status='STANDBY' WHERE status='ACTIVE'");db.update("UPDATE knowledge_index_generation SET status='ACTIVE' WHERE id=?",generation);audit(actor,String.valueOf(generation),"GENERATION_ACTIVATED",Map.of());
  }
  @Transactional public void validateGeneration(String actor,int generation,String note) {
    one("SELECT id FROM knowledge_index_generation WHERE id=? FOR UPDATE",generation);
    audit(actor,String.valueOf(generation),"GENERATION_VALIDATED",Map.of("note",note));
  }
  @Transactional public Map<String,Object> reconcile() {
    int count=0;
    for(var entry:db.queryForList("SELECT id,published_revision FROM knowledge_entry WHERE status='PUBLISHED'"))for(var g:generations()){enqueue((String)entry.get("id"),number(entry.get("published_revision")),number(g.get("id")),"UPSERT");count++;}
    // Periodically reapply immutable rows as well: repairs index loss after a restore, not only missing jobs.
    db.update("UPDATE knowledge_index_job SET status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP(3) WHERE status='SUCCEEDED' AND verified_at<?",java.sql.Timestamp.from(Instant.now().minusSeconds(3600)));
    for(var job:db.queryForList("SELECT id FROM knowledge_index_job WHERE (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=CURRENT_TIMESTAMP(3)) OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3)) LIMIT 100"))resend((String)job.get("id"));
    return Map.of("checked",count);
  }
}
