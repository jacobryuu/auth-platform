-- Users
CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  email VARCHAR(255) UNIQUE NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Credentials
CREATE TABLE credentials (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,

  type VARCHAR(30) NOT NULL,
  identifier VARCHAR(255),
  secret TEXT,
  meta TEXT,

  enabled BOOLEAN DEFAULT true,
  priority INT DEFAULT 0,

  failed_count INT DEFAULT 0,
  locked_until TIMESTAMP,

  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Refresh Tokens
CREATE TABLE refresh_tokens (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash TEXT NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Login History
CREATE TABLE login_history (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  ip_address VARCHAR(64),
  user_agent TEXT,
  success BOOLEAN,
  action VARCHAR(50),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Recovery Tokens
CREATE TABLE recovery_tokens (
  user_id BIGINT NOT NULL,
  token_hash TEXT NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  token_type VARCHAR(50) NOT NULL DEFAULT 'password_reset',
  PRIMARY KEY (user_id, token_hash),
  FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Indexes
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_cred_user ON credentials(user_id);
CREATE INDEX idx_token_user ON refresh_tokens(user_id);
CREATE INDEX idx_login_user ON login_history(user_id);
CREATE INDEX idx_recovery_user ON recovery_tokens(user_id);

-- !Downs
DROP TABLE IF EXISTS recovery_tokens;
DROP TABLE IF EXISTS login_history;
DROP TABLE IF EXISTS refresh_tokens;
DROP TABLE IF EXISTS credentials;
DROP TABLE IF EXISTS users;
