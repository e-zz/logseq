#!/usr/bin/env node
/*
 * Probe: SQL-shape verification of the production search index (db graph FTS).
 *
 * Uses the EXACT production SQL from current HEAD (546d1f7cca):
 *  - schema: src/main/frontend/worker/search.cljs:25-64 (blocks + FTS5 trigram + triggers)
 *  - upsert: search.cljs:163-196 (INSERT ... ON CONFLICT (id) DO UPDATE)
 *  - delete: search.cljs:198-202 (DELETE FROM blocks WHERE id IN (...))
 *  - queries: search.cljs match/like/exact-title (see report §3)
 *
 * What it verifies:
 *  1. FTS rows are a pure mirror of the `blocks` table (triggers). The SQL layer
 *     has NO deleted-at/hide column — recycled visibility is decided exclusively
 *     by whether the CLJS layer (hidden-entity? filter) upserted the row.
 *  2. delete-blocks! removes the FTS row via the blocks_ad trigger.
 *  3. Full-rebuild window: truncate = DROP+CREATE tables + PRAGMA user_version=0
 *     (search.cljs:1126-1130); mid-rebuild FTS contains only completed upsert batches.
 *  4. exact-title query reads `blocks` (not FTS) — same row-presence semantics.
 *
 * Does NOT replicate hidden-entity?/recycled? predicate logic (forbidden); that
 * layer is verified by source diff (source-diff.md) and existing test evidence.
 *
 * Node >=22 built-in node:sqlite, in-memory only. Writes nothing outside this dir.
 */
'use strict';
const { DatabaseSync } = require('node:sqlite');
const fs = require('node:fs');
const path = require('node:path');

const out = { probes: [], meta: {
  node: process.version,
  sourceRef: 'HEAD 546d1f7cca src/main/frontend/worker/search.cljs',
  note: 'Schema/SQL strings transcribed verbatim from production source; FTS5 trigram tokenizer.'
}};
function record(name, fn) {
  try { out.probes.push({ name, ok: true, ...fn() }); }
  catch (e) { out.probes.push({ name, ok: false, error: String(e) }); }
}

// ---- verbatim production schema (search.cljs:52-64, 20-42) ----
function createProductionSchema(db) {
  db.exec(`CREATE TABLE IF NOT EXISTS blocks (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        page TEXT)`);
  db.exec(`CREATE VIRTUAL TABLE IF NOT EXISTS blocks_fts USING fts5(id, title, page, tokenize="trigram")`);
  db.exec(`CREATE INDEX IF NOT EXISTS blocks_title_nocase_idx ON blocks(title COLLATE NOCASE)`);
  db.exec(`CREATE TRIGGER IF NOT EXISTS blocks_ad AFTER DELETE ON blocks
         BEGIN
             DELETE FROM blocks_fts WHERE rowid = old.rowid;
         END;`);
  db.exec(`CREATE TRIGGER IF NOT EXISTS blocks_ai AFTER INSERT ON blocks
         BEGIN
             INSERT INTO blocks_fts (rowid, id, title, page)
             VALUES (new.rowid, new.id, new.title, new.page);
         END;`);
  db.exec(`CREATE TRIGGER IF NOT EXISTS blocks_au AFTER UPDATE ON blocks
         BEGIN
             DELETE FROM blocks_fts WHERE rowid = old.rowid;
             INSERT INTO blocks_fts (rowid, id, title, page)
             VALUES (new.rowid, new.id, new.title, new.page);
         END;`);
}

// ---- verbatim production SQL (search.cljs) ----
const upsertSql = (n) => `INSERT INTO blocks (id, title, page) VALUES ` +
  Array.from({length: n}, () => '(?, ?, ?)').join(', ') +
  ` ON CONFLICT (id) DO UPDATE SET (title, page) = (excluded.title, excluded.page)`;
const deleteSql = (n) => `DELETE from blocks WHERE id IN (` +
  Array.from({length: n}, () => '?').join(', ') + `)`;
// search-blocks FTS match (trigram tokenizer => bare substring match works)
const matchSql = "select id, page, title, rank from blocks_fts where title match ? limit ?";
const likeSql = "select id, page, title from blocks where title like ? limit ?";
const exactTitleSql = "select id, page, title from blocks where title = ? COLLATE NOCASE limit ?";

const db = new DatabaseSync(':memory:');
createProductionSchema(db);

// Fixture: one ACTIVE block row (CLJS layer upserted it) and one RECYCLED block
// row (CLJS layer upserted it — the hypothetical H2 case). SQL layer treats them
// identically: no recycled/hide column exists in either table.
db.prepare(upsertSql(2)).run(
  '11111111-1111-4111-8111-111111111111', 'Zebraquantum Active Page', 'aaaaaaaa-0000-4000-8000-000000000001',
  '22222222-2222-4222-8222-222222222222', 'Zebraquantum Recycled Child', 'bbbbbbbb-0000-4000-8000-000000000002');

record('P1-fts-mirror-of-blocks', () => {
  const fts = db.prepare(matchSql).all('Zebraquantum', 10).map(r => r.title);
  const blocks = db.prepare(likeSql).all('%Zebraquantum%', 10).map(r => r.title);
  const columns = db.prepare(`PRAGMA table_info(blocks)`).all().map(c => c.name);
  const ftsColumns = db.prepare(`PRAGMA table_info(blocks_fts)`).all().map(c => c.name);
  return {
    ftsTitles: fts, blocksTitles: blocks, blockColumns: columns, ftsColumns,
    verdict: 'SQL layer has no deleted-at/hide column; a row upserted by CLJS is query-identical to any other row'
  };
});

record('P2-delete-blocks-removes-fts-row', () => {
  db.prepare(deleteSql(1)).run('22222222-2222-4222-8222-222222222222');
  const fts = db.prepare(matchSql).all('Zebraquantum', 10).map(r => r.title);
  return { ftsTitlesAfterDelete: fts, verdict: 'DELETE FROM blocks (search-delete-blocks path) removes the FTS row via blocks_ad trigger' };
});

record('P3-exact-title-reads-blocks-table', () => {
  const rows = db.prepare(exactTitleSql).all('Zebraquantum Active Page', 10).map(r => r.title);
  return { rows, verdict: 'exact-title query hits blocks (NOT FTS); row presence is the only gate' };
});

record('P4-truncate-is-drop-recreate-version0', () => {
  db.exec(`DROP TABLE IF EXISTS blocks;
DROP TABLE IF EXISTS blocks_fts;
DROP TABLE IF EXISTS blocks_fts_next;
DROP TRIGGER IF EXISTS blocks_ad;
DROP TRIGGER IF EXISTS blocks_ai;
DROP TRIGGER IF EXISTS blocks_au;`);
  db.exec('PRAGMA user_version = 0');
  createProductionSchema(db);
  const v = db.prepare('PRAGMA user_version').get().user_version;
  const fts = db.prepare('select count(*) as n from blocks_fts').get().n;
  const blocks = db.prepare('select count(*) as n from blocks').get().n;
  // batch 1 of a hypothetical rebuild: only the active block (CLJS filter hidden-entity? applied per batch)
  db.prepare(upsertSql(1)).run('11111111-1111-4111-8111-111111111111', 'Zebraquantum Active Page', 'aaaaaaaa-0000-4000-8000-000000000001');
  const mid = db.prepare(matchSql).all('Zebraquantum', 10).map(r => r.title);
  return {
    userVersionAfterTruncate: v, ftsRows: fts, blocksRows: blocks,
    ftsAfterBatch1: mid,
    verdict: 'mid-rebuild FTS == completed batches only; whether recycled rows appear is decided by the CLJS per-batch filter, not SQL'
  };
});

const outDir = __dirname;
fs.writeFileSync(path.join(outDir, 'results.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
