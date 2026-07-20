-- V9: snapshot fingerprint for same-revision conflict detection (0711 §5.1.4)

ALTER TABLE registry_source_state
    ADD COLUMN IF NOT EXISTS snapshot_fingerprint VARCHAR(64);
