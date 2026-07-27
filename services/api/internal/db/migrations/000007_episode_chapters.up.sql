-- Store the publisher's Podcasting 2.0 <podcast:chapters> URL alongside the
-- episode. The RSS parser has always read this tag, but with nowhere to put it
-- the value was discarded at ingest, which left the clients' chapter UI unable
-- to trigger. Empty string means "this episode has no chapters".
ALTER TABLE episodes ADD COLUMN chapters_url TEXT NOT NULL DEFAULT '';
