DROP INDEX IF EXISTS idx_device_credentials_token;
ALTER TABLE device_credentials DROP COLUMN expires_at;
