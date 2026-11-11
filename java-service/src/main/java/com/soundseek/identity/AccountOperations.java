package com.soundseek.identity;
import java.util.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
/** Local operations only: preview and disable. Deliberately has no data deletion capability. */
@Service public class AccountOperations {
 private final JdbcTemplate db;private final UserAccountRepository users;
 public AccountOperations(JdbcTemplate db,UserAccountRepository users){this.db=db;this.users=users;}
 @Transactional(readOnly=true) public Map<String,Object> preview(UUID id){var u=users.findById(id).orElseThrow();return Map.of("accountId",id,"status",u.getStatus(),"conversations",count("conversation",u.getPrimaryGuestSessionId()),"savedSongs",count("saved_song",u.getPrimaryGuestSessionId()),"tournaments",count("tournament",u.getPrimaryGuestSessionId()),"memories",count("music_preference_memory",u.getPrimaryGuestSessionId()),"physicalDeletionSupported",false);}
 private int count(String table,UUID guest){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE guest_session_id=UUID_TO_BIN(?)",Integer.class,guest.toString());}
 @Transactional public void disable(UUID id,String reason){
  if(reason==null||reason.isBlank()||reason.length()>500)throw new IllegalArgumentException("需要有效的操作理由。");
  var u=users.lockById(id).orElseThrow();u.disable();users.flush();
  db.update("UPDATE agent_run_attempt a JOIN agent_run r ON r.id=a.run_id SET a.status='FAILED',a.failure_code='ACCOUNT_DISABLED',a.finished_at=CURRENT_TIMESTAMP(3) WHERE r.guest_session_id=UUID_TO_BIN(?) AND a.status='RUNNING'",u.getPrimaryGuestSessionId().toString());
  db.update("UPDATE agent_run_follow_up SET status='CANCELLED' WHERE guest_session_id=UUID_TO_BIN(?) AND status='WAITING'",u.getPrimaryGuestSessionId().toString());
  db.update("UPDATE agent_run SET status='CANCELLED',completed_at=CURRENT_TIMESTAMP(3) WHERE guest_session_id=UUID_TO_BIN(?) AND status IN ('QUEUED','RUNNING','WAITING_FOR_USER')",u.getPrimaryGuestSessionId().toString());
  db.update("UPDATE auth_challenge SET consumed_at=CURRENT_TIMESTAMP(3),password_hash=NULL WHERE user_id=UUID_TO_BIN(?)",id.toString());
  db.update("UPDATE auth_mail_job SET ciphertext=NULL,status='EXPIRED' WHERE user_id=UUID_TO_BIN(?)",id.toString());
  db.update("INSERT INTO account_operation(id,user_id,action,reason,status) VALUES(?,UUID_TO_BIN(?),'DISABLE',?,'DONE')",UUID.randomUUID().toString(),id.toString(),reason);
 }
}
