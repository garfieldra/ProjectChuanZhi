package com.soundseek.identity;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestAssetMergeService {
  private final JdbcTemplate jdbc;
  public GuestAssetMergeService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Transactional
  public void merge(UUID source, UUID target) {
    if (source.equals(target)) return;
    var ordered = source.compareTo(target) < 0 ? new UUID[]{source, target} : new UUID[]{target, source};
    for (var guest : ordered) jdbc.query("SELECT id FROM guest_session WHERE id=UUID_TO_BIN(?) FOR UPDATE", result -> {}, guest.toString());
    var targetHasProfile = jdbc.queryForObject("SELECT COUNT(*) FROM music_preference_profile WHERE guest_session_id=UUID_TO_BIN(?)", Integer.class, target.toString());
    if (targetHasProfile == null || targetHasProfile == 0) jdbc.update("UPDATE music_preference_profile SET guest_session_id=UUID_TO_BIN(?) WHERE guest_session_id=UUID_TO_BIN(?)", target.toString(), source.toString());
    for (var table : new String[]{"conversation", "tournament", "music_preference_memory", "agent_run", "agent_run_intervention", "agent_run_follow_up"}) jdbc.update("UPDATE " + table + " SET guest_session_id=UUID_TO_BIN(?) WHERE guest_session_id=UUID_TO_BIN(?)", target.toString(), source.toString());
    jdbc.update("UPDATE preference_event source LEFT JOIN preference_event target ON target.guest_session_id=UUID_TO_BIN(?) AND target.idempotency_key=source.idempotency_key SET source.guest_session_id=UUID_TO_BIN(?) WHERE source.guest_session_id=UUID_TO_BIN(?) AND target.id IS NULL", target.toString(), target.toString(), source.toString());
  }
}
