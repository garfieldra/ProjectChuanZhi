package com.soundseek.identity;
import java.util.UUID;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Component;
@Component public class AccountAvailability {
 private final JdbcTemplate db;public AccountAvailability(JdbcTemplate db){this.db=db;}
 public void requireGuest(UUID guest){if(db.queryForObject("SELECT COUNT(*) FROM user_account WHERE primary_guest_session_id=UUID_TO_BIN(?) AND status<>'ACTIVE'",Integer.class,guest.toString())>0)throw new IllegalStateException("ACCOUNT_UNAVAILABLE");}
 public void requireRun(UUID run){var rows=db.queryForList("SELECT r.status AS run_status,u.status AS account_status FROM agent_run r LEFT JOIN user_account u ON u.primary_guest_session_id=r.guest_session_id WHERE r.id=UUID_TO_BIN(?) FOR SHARE",run.toString());if(rows.isEmpty())throw new IllegalStateException("AGENT_RUN_UNAVAILABLE");var row=rows.getFirst();if("CANCELLED".equals(row.get("run_status"))||(row.get("account_status")!=null&&!"ACTIVE".equals(row.get("account_status"))))throw new IllegalStateException("ACCOUNT_OR_RUN_UNAVAILABLE");}
}
