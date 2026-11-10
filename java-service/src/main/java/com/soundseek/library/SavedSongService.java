package com.soundseek.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedSongService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public SavedSongService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
  private static final String SELECT = """
      SELECT BIN_TO_UUID(s.recording_id) recording_id,r.title,a.name artist_name,r.album_title,
      r.version_label,r.cover_url,r.musicbrainz_mbid,s.reason_snapshot,s.created_at,
      BIN_TO_UUID(s.source_conversation_id) source_conversation_id,
      BIN_TO_UUID(s.source_message_id) source_message_id,
      CASE WHEN c.id IS NOT NULL AND c.status <> 'DELETED' AND c.guest_session_id=s.guest_session_id THEN 1 ELSE 0 END source_available
      FROM saved_song s JOIN recording r ON r.id=s.recording_id JOIN artist a ON a.id=r.artist_id
      LEFT JOIN conversation c ON c.id=s.source_conversation_id
      WHERE s.guest_session_id=UUID_TO_BIN(?)
      """;
  private static UUID uuid(String text) { return text==null?null:UUID.fromString(text); }
  private static final RowMapper<Song> MAPPER = (row,n) -> new Song(uuid(row.getString("recording_id")),row.getString("title"),row.getString("artist_name"),row.getString("album_title"),row.getString("version_label"),row.getString("cover_url"),row.getString("musicbrainz_mbid"),row.getString("reason_snapshot"),row.getTimestamp("created_at").toInstant(),uuid(row.getString("source_conversation_id")),uuid(row.getString("source_message_id")),row.getBoolean("source_available"));
  public record Song(UUID recordingId,String title,String artistName,String albumTitle,String versionLabel,String coverUrl,String musicbrainzMbid,String reason,Instant savedAt,UUID sourceConversationId,UUID sourceMessageId,boolean sourceAvailable) {}
  public record Page(List<Song> items,String nextCursor) {}

  @Transactional
  public Song save(UUID owner,UUID recording,UUID source) {
    // Lock the same identity row used by account merging, so saves cannot race a merge.
    jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM guest_session WHERE id=UUID_TO_BIN(?) FOR UPDATE",String.class,owner.toString());
    var existing=find(owner,recording); if(existing.isPresent()) return existing.get();
    var rows=jdbc.queryForList("""
        SELECT BIN_TO_UUID(c.id) conversation_id,m.card_payload_json
        FROM conversation_message m JOIN conversation c ON c.id=m.conversation_id
        WHERE m.id=UUID_TO_BIN(?) AND c.guest_session_id=UUID_TO_BIN(?)
        AND c.status <> 'DELETED' AND m.card_type='MUSIC_RECOMMENDATIONS'
        AND m.type='RECOMMENDATION_CARD'
        """,source.toString(),owner.toString());
    if(rows.isEmpty()) throw new NoSuchElementException();
    String reason=null;
    try {
      for(var song:json.readTree(String.valueOf(rows.getFirst().get("card_payload_json"))).path("songs")) {
        if(recording.toString().equals(song.path("recordingId").asText())) { reason=song.path("reason").asText("");break; }
      }
    } catch (java.io.IOException e) { throw new IllegalArgumentException("推荐来源无法读取"); }
    if(reason==null || !Integer.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM recording WHERE id=UUID_TO_BIN(?)",Integer.class,recording.toString()))) throw new NoSuchElementException();
    jdbc.update("""
        INSERT INTO saved_song(guest_session_id,recording_id,source_conversation_id,source_message_id,reason_snapshot)
        VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)
        ON DUPLICATE KEY UPDATE recording_id=recording_id
        """,owner.toString(),recording.toString(),rows.getFirst().get("conversation_id"),source.toString(),reason);
    return find(owner,recording).orElseThrow();
  }
  @Transactional
  public void remove(UUID owner,UUID recording) {
    jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM guest_session WHERE id=UUID_TO_BIN(?) FOR UPDATE",String.class,owner.toString());
    jdbc.update("DELETE FROM saved_song WHERE guest_session_id=UUID_TO_BIN(?) AND recording_id=UUID_TO_BIN(?)",owner.toString(),recording.toString());
  }
  @Transactional(readOnly=true)
  public Optional<Song> find(UUID owner,UUID recording) {
    return jdbc.query(SELECT+" AND s.recording_id=UUID_TO_BIN(?)",MAPPER,owner.toString(),recording.toString()).stream().findFirst();
  }
  @Transactional(readOnly=true)
  public Page list(UUID owner,String search,String cursor,int limit) {
    if(limit<1||limit>50||search.length()>120) throw new IllegalArgumentException("查询范围不合法");
    var sql=new StringBuilder(SELECT);var args=new ArrayList<Object>();args.add(owner.toString());
    if(!search.isBlank()) {
      var term="%"+search.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
      sql.append(" AND (r.title LIKE ? ESCAPE '!' OR a.name LIKE ? ESCAPE '!')");args.add(term);args.add(term);
    }
    if(cursor!=null&&!cursor.isBlank()) {
      try {
        if(cursor.length()>160) throw new IllegalArgumentException();
        var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
        if(parts.length!=2) throw new IllegalArgumentException();
        var at=Timestamp.from(Instant.ofEpochMilli(Long.parseLong(parts[0])));var id=UUID.fromString(parts[1]);
        sql.append(" AND (s.created_at < ? OR (s.created_at = ? AND s.recording_id < UUID_TO_BIN(?)))");args.add(at);args.add(at);args.add(id.toString());
      } catch(RuntimeException e) { throw new IllegalArgumentException("分页位置不合法"); }
    }
    sql.append(" ORDER BY s.created_at DESC,s.recording_id DESC LIMIT ?");args.add(limit+1);
    var rows=jdbc.query(sql.toString(),MAPPER,args.toArray());boolean more=rows.size()>limit;
    var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));String next=null;
    if(more){var last=items.getLast();next=Base64.getUrlEncoder().withoutPadding().encodeToString((last.savedAt().toEpochMilli()+"|"+last.recordingId()).getBytes(StandardCharsets.UTF_8));}
    return new Page(items,next);
  }
  @Transactional(readOnly=true)
  public List<UUID> status(UUID owner,List<UUID> ids) {
    if(ids.size()>100)throw new IllegalArgumentException("一次最多检查100首歌曲");
    if(ids.isEmpty())return List.of();
    var args=new ArrayList<Object>();args.add(owner.toString());ids.forEach(id->args.add(id.toString()));
    var placeholders=String.join(",",Collections.nCopies(ids.size(),"UUID_TO_BIN(?)"));
    return jdbc.query("SELECT BIN_TO_UUID(recording_id) FROM saved_song WHERE guest_session_id=UUID_TO_BIN(?) AND recording_id IN ("+placeholders+")",(row,n)->UUID.fromString(row.getString(1)),args.toArray());
  }
  @Transactional(readOnly=true)
  public String context(UUID owner,UUID recording) {
    var song=find(owner,recording).orElseThrow();
    var text="我想围绕《"+song.title()+"》—"+song.artistName()+"继续探索。";
    if(song.albumTitle()!=null&&!song.albumTitle().isBlank())text+="\n专辑："+song.albumTitle();
    if(song.versionLabel()!=null&&!song.versionLabel().isBlank())text+="\n版本："+song.versionLabel();
    if(song.musicbrainzMbid()!=null) {
      try {var mbid=UUID.fromString(song.musicbrainzMbid());text+="\n作品资料：https://musicbrainz.org/recording/"+mbid;} catch(IllegalArgumentException ignored) {}
    }
    return text;
  }
}
