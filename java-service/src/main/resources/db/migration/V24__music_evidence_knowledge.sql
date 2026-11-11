CREATE TABLE knowledge_source (
 id VARCHAR(36) PRIMARY KEY, source_key VARCHAR(64) NOT NULL UNIQUE,
 current_revision INT NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
);
CREATE TABLE knowledge_source_revision (
 source_id VARCHAR(36) NOT NULL, revision INT NOT NULL, content_hash VARCHAR(64) NOT NULL,
 payload_json JSON NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(source_id, revision), FOREIGN KEY(source_id) REFERENCES knowledge_source(id)
);
CREATE TABLE knowledge_entry (
 id VARCHAR(36) PRIMARY KEY, knowledge_type VARCHAR(32) NOT NULL,
 current_revision INT NOT NULL, published_revision INT NULL, status VARCHAR(24) NOT NULL,
 retraction_reason VARCHAR(500), created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE knowledge_entry_revision (
 entry_id VARCHAR(36) NOT NULL, revision INT NOT NULL, summary TEXT NOT NULL,
 payload_json JSON NOT NULL, content_hash VARCHAR(64) NOT NULL,
 review_status VARCHAR(24) NOT NULL, created_by VARCHAR(320) NOT NULL,
 reviewed_by VARCHAR(320), reviewed_at TIMESTAMP(3), review_note VARCHAR(1000),
 PRIMARY KEY(entry_id, revision), FOREIGN KEY(entry_id) REFERENCES knowledge_entry(id)
);
CREATE TABLE knowledge_entry_entity (
 entry_id VARCHAR(36) NOT NULL, revision INT NOT NULL, entity_type VARCHAR(24) NOT NULL,
 entity_id VARCHAR(100) NOT NULL, role VARCHAR(24) NOT NULL,
 PRIMARY KEY(entry_id, revision, entity_type, entity_id, role),
 KEY ix_knowledge_entity(entity_type, entity_id),
 FOREIGN KEY(entry_id, revision) REFERENCES knowledge_entry_revision(entry_id, revision)
);
CREATE TABLE knowledge_evidence_link (
 id VARCHAR(36) PRIMARY KEY, entry_id VARCHAR(36) NOT NULL, revision INT NOT NULL,
 source_id VARCHAR(36) NOT NULL, source_revision INT NOT NULL,
 relation_type VARCHAR(24) NOT NULL, excerpt TEXT NOT NULL, locator VARCHAR(500) NOT NULL,
 FOREIGN KEY(entry_id, revision) REFERENCES knowledge_entry_revision(entry_id, revision),
 FOREIGN KEY(source_id, source_revision) REFERENCES knowledge_source_revision(source_id, revision)
);
CREATE TABLE knowledge_ingestion_job (
 id VARCHAR(36) PRIMARY KEY, actor VARCHAR(320) NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 content_hash VARCHAR(64) NOT NULL, origin VARCHAR(32) NOT NULL, payload_json JSON NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), UNIQUE KEY uq_ingestion(actor,idempotency_key)
);
CREATE TABLE knowledge_index_generation (
 id INT PRIMARY KEY, status VARCHAR(24) NOT NULL, payload_json JSON NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
INSERT INTO knowledge_index_generation VALUES (1,'ACTIVE',
 '{"model":"BAAI/bge-small-zh-v1.5","dimension":512,"templateVersion":"1","modelRevision":"baseline-v1","factCollection":"soundseek_fact_evidence_g1","themeCollection":"soundseek_theme_cards_g1"}',CURRENT_TIMESTAMP(3));
CREATE TABLE knowledge_index_job (
 id VARCHAR(36) PRIMARY KEY, entry_id VARCHAR(36) NOT NULL, revision INT NOT NULL,
 generation INT NOT NULL, operation VARCHAR(16) NOT NULL, status VARCHAR(24) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, lease_token VARCHAR(36), lease_until TIMESTAMP(3),
 next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), error_code VARCHAR(100),
 verified_at TIMESTAMP(3),
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_index_job(entry_id,revision,generation,operation), KEY ix_index_pending(status,next_attempt_at)
);
CREATE TABLE knowledge_audit_event (
 id VARCHAR(36) PRIMARY KEY, actor VARCHAR(320) NOT NULL, object_id VARCHAR(100) NOT NULL,
 action VARCHAR(40) NOT NULL, detail_json JSON NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
