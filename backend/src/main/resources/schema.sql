-- Saved diagrams, keyed by the signed-in user's Google subject id.
CREATE TABLE IF NOT EXISTS diagrams (
  id          IDENTITY PRIMARY KEY,
  user_sub    VARCHAR(64)  NOT NULL,
  user_email  VARCHAR(256),
  title       VARCHAR(200) NOT NULL,
  source_type VARCHAR(16)  NOT NULL,   -- 'code' or 'repo'
  source      CLOB         NOT NULL,   -- the pasted Java, or the GitHub repo URL
  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_diagrams_user ON diagrams(user_sub);
