package com.soundseek.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class KnowledgeSchemaMigrationTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withUrlParam("sslMode","DISABLED").withUrlParam("allowPublicKeyRetrieval","true");

  @Test void upgradesMainV23WithoutDroppingExistingData() {
    Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())
        .target(MigrationVersion.fromVersion("23")).load().migrate();
    var db=new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()));
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='saved_song'",Integer.class));
    db.execute("CREATE TABLE merge_migration_probe(id INT PRIMARY KEY, value_text VARCHAR(30))");
    db.update("INSERT INTO merge_migration_probe VALUES(1,'preserved')");
    String user="aaaaaaaa-0000-4000-8000-000000000001",guest="aaaaaaaa-0000-4000-8000-000000000002";
    String github="aaaaaaaa-0000-4000-8000-000000000003",githubGuest="aaaaaaaa-0000-4000-8000-000000000004";
    db.update("INSERT INTO guest_session(id,token_hash) VALUES(UUID_TO_BIN(?),?), (UUID_TO_BIN(?),?)",guest,"a".repeat(64),githubGuest,"b".repeat(64));
    db.update("INSERT INTO user_account(id,email_normalized,display_name,status,primary_guest_session_id) VALUES(UUID_TO_BIN(?),'legacy@example.invalid','Legacy','ACTIVE',UUID_TO_BIN(?)),(UUID_TO_BIN(?),'github-321@users.soundseek.local','Github','ACTIVE',UUID_TO_BIN(?))",user,guest,github,githubGuest);
    db.update("INSERT INTO auth_identity(id,user_id,provider,provider_subject,password_hash) VALUES(UUID_TO_BIN(UUID()),UUID_TO_BIN(?),'PASSWORD','legacy@example.invalid','preserved-test-hash'),(UUID_TO_BIN(UUID()),UUID_TO_BIN(?),'GITHUB','321',NULL)",user,github);
    var merged=Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()).load();
    merged.migrate();merged.validate();
    assertEquals("26",merged.info().current().getVersion().toString());
    assertNull(db.queryForObject("SELECT email_verified_at FROM user_account WHERE id=UUID_TO_BIN(?)",java.sql.Timestamp.class,user));
    assertNull(db.queryForObject("SELECT email_normalized FROM user_account WHERE id=UUID_TO_BIN(?)",String.class,github));
    assertEquals(0L,db.queryForObject("SELECT auth_version FROM user_account WHERE id=UUID_TO_BIN(?)",Long.class,user));
    assertEquals("preserved-test-hash",db.queryForObject("SELECT password_hash FROM auth_identity WHERE user_id=UUID_TO_BIN(?)",String.class,user));
    assertEquals("preserved",db.queryForObject("SELECT value_text FROM merge_migration_probe WHERE id=1",String.class));
    assertEquals("46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59",db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.modelRevision')) FROM knowledge_index_generation WHERE status='ACTIVE'",String.class));
    assertEquals("soundseek_fact_evidence_g1",db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.factCollection')) FROM knowledge_index_generation WHERE status='ACTIVE'",String.class));
  }
}
