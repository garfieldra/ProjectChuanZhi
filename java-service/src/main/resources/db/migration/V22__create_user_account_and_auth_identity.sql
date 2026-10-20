CREATE TABLE user_account (
  id BINARY(16) PRIMARY KEY,
  email_normalized VARCHAR(320) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  avatar_url VARCHAR(1024) NULL,
  status VARCHAR(24) NOT NULL,
  primary_guest_session_id BINARY(16) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_user_primary_guest FOREIGN KEY (primary_guest_session_id) REFERENCES guest_session(id),
  UNIQUE KEY uq_user_email (email_normalized),
  UNIQUE KEY uq_user_primary_guest (primary_guest_session_id)
);

CREATE TABLE auth_identity (
  id BINARY(16) PRIMARY KEY,
  user_id BINARY(16) NOT NULL,
  provider VARCHAR(24) NOT NULL,
  provider_subject VARCHAR(255) NOT NULL,
  password_hash VARCHAR(100) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_auth_identity_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  UNIQUE KEY uq_auth_provider_subject (provider, provider_subject),
  UNIQUE KEY uq_auth_user_provider (user_id, provider)
);
