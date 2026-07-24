-- Add an expiry to device credentials so leaked Bearer tokens do not remain
-- valid indefinitely. Existing rows keep expires_at = 0, which is treated as
-- "no expiry" for backward compatibility; newly issued tokens set a real expiry.
ALTER TABLE device_credentials ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_device_credentials_token ON device_credentials(token_hash);
