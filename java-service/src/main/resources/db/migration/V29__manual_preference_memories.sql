-- Explicitly entered preferences have no conversation; conversation saves retain a message anchor.
ALTER TABLE music_preference_memory
  MODIFY source_conversation_id BINARY(16) NULL,
  ADD COLUMN source_message_id BINARY(16) NULL;
