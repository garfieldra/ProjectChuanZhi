CREATE TABLE saved_song (
  guest_session_id BINARY(16) NOT NULL,
  recording_id BINARY(16) NOT NULL,
  source_conversation_id BINARY(16) NULL,
  source_message_id BINARY(16) NULL,
  reason_snapshot TEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (guest_session_id, recording_id),
  CONSTRAINT fk_saved_song_owner FOREIGN KEY (guest_session_id) REFERENCES guest_session(id),
  CONSTRAINT fk_saved_song_recording FOREIGN KEY (recording_id) REFERENCES recording(id),
  INDEX idx_saved_song_page (guest_session_id, created_at DESC, recording_id DESC)
);
