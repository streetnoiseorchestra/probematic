#!/usr/bin/env bash
set -euo pipefail

echo "Creating SQLite database and schema..."
sqlite3 ./data.dev/datomic/data/datomic-sqlite.db "
      CREATE TABLE IF NOT EXISTS datomic_kvs (
        id TEXT NOT NULL PRIMARY KEY,
        rev INTEGER,
        map TEXT,
        val BLOB
      );"
echo "Database initialization complete."
