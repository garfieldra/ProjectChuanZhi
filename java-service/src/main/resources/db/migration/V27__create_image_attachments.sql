CREATE TABLE image_attachment (
 id BINARY(16) PRIMARY KEY, guest_session_id BINARY(16) NOT NULL,
 conversation_id BINARY(16) NULL, file_name VARCHAR(180) NOT NULL,
 content_type VARCHAR(40) NOT NULL, byte_size BIGINT NOT NULL,
 width INT NOT NULL,height INT NOT NULL,created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY (guest_session_id) REFERENCES guest_session(id),
 FOREIGN KEY (conversation_id) REFERENCES conversation(id),
 INDEX idx_image_attachment_owner (guest_session_id,created_at)
);
ALTER TABLE conversation_message ADD COLUMN attachment_ids VARCHAR(400) NOT NULL DEFAULT '';
ALTER TABLE agent_run_follow_up ADD COLUMN attachment_ids VARCHAR(400) NOT NULL DEFAULT '';
ALTER TABLE agent_run_intervention ADD COLUMN attachment_ids VARCHAR(400) NOT NULL DEFAULT '';
