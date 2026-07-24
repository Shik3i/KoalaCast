-- Store publisher-provided transcripts (Podcasting 2.0 <podcast:transcript>) as a
-- JSON array of {url,type}. Empty string when a feed ships none.
ALTER TABLE episodes ADD COLUMN transcripts TEXT NOT NULL DEFAULT '';
