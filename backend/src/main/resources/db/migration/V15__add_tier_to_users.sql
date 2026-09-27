-- Study Packs: per-user subscription tier (FREE | PRO | MAX), set by an admin. Existing rows get
-- FREE via the default. Plain ANSI syntax so it runs unchanged on PostgreSQL and H2 2.x.
ALTER TABLE users ADD COLUMN tier VARCHAR(10) DEFAULT 'FREE' NOT NULL;
