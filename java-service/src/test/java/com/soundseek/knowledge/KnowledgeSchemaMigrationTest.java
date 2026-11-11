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
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @Test void upgradesMainV23WithoutDroppingExistingData() {
    Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())
        .target(MigrationVersion.fromVersion("23")).load().migrate();
    var db=new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()));
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='saved_song'",Integer.class));
    db.execute("CREATE TABLE merge_migration_probe(id INT PRIMARY KEY, value_text VARCHAR(30))");
    db.update("INSERT INTO merge_migration_probe VALUES(1,'preserved')");
    var merged=Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()).load();
    merged.migrate();merged.validate();
    assertEquals("25",merged.info().current().getVersion().toString());
    assertEquals("preserved",db.queryForObject("SELECT value_text FROM merge_migration_probe WHERE id=1",String.class));
    assertEquals("46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59",db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.modelRevision')) FROM knowledge_index_generation WHERE status='ACTIVE'",String.class));
    assertEquals("soundseek_fact_evidence_g1",db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.factCollection')) FROM knowledge_index_generation WHERE status='ACTIVE'",String.class));
  }
}
