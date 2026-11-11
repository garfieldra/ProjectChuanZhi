ALTER TABLE user_account MODIFY email_normalized VARCHAR(320) NULL,
 ADD email_verified_at TIMESTAMP(3) NULL,
 ADD auth_version BIGINT NOT NULL DEFAULT 0;
UPDATE user_account u JOIN auth_identity i ON i.user_id=u.id AND i.provider='GITHUB'
 SET u.email_normalized=NULL WHERE u.email_normalized=CONCAT('github-',i.provider_subject,'@users.soundseek.local');
CREATE TABLE auth_challenge (
 id VARCHAR(36) PRIMARY KEY, purpose VARCHAR(32) NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE,
 user_id BINARY(16) NULL, email VARCHAR(320) NOT NULL, auth_version BIGINT NOT NULL DEFAULT 0,
 password_hash VARCHAR(100), display_name VARCHAR(80), browser_hash CHAR(64),
 expires_at TIMESTAMP(3) NOT NULL, consumed_at TIMESTAMP(3) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY ix_challenge_owner(user_id,purpose), KEY ix_challenge_expiry(expires_at)
);
CREATE TABLE auth_mail_job (
 id VARCHAR(36) PRIMARY KEY, user_id BINARY(16) NULL, challenge_id VARCHAR(36) NULL, dedupe_key CHAR(64) NOT NULL UNIQUE,
 input_hash CHAR(64) NOT NULL, ciphertext TEXT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 expires_at TIMESTAMP(3) NOT NULL, error_code VARCHAR(80),
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), KEY ix_auth_mail_due(status,next_attempt_at)
);
CREATE TABLE account_security_event (
 id VARCHAR(36) PRIMARY KEY, user_id BINARY(16), action VARCHAR(64) NOT NULL,
 outcome VARCHAR(24) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE account_operation (
 id VARCHAR(36) PRIMARY KEY, user_id BINARY(16) NOT NULL, action VARCHAR(24) NOT NULL,
 reason VARCHAR(500) NOT NULL, status VARCHAR(24) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
