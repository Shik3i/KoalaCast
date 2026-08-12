ALTER TABLE users ADD COLUMN data_generation INTEGER NOT NULL DEFAULT 0
    CHECK (data_generation >= 0);
