#!/usr/bin/env python3
"""Host SQLite checks of DDL extracted from Kotlin, NOT Android adapter tests.

Uses only the Python standard library and temporary databases. Run from any cwd.
The Android instrumentation APK exercises the actual repository separately.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'app/src/main/java/com/auditlab/academic/storage/AuditDatabase.kt').read_text()


def constant(name):
    match = re.search(r'val ' + name + r' = """(.*?)"""', SOURCE, re.S)
    if not match:
        raise ValueError(f'Missing Kotlin SQL constant: {name}')
    sql = match.group(1)
    uuid_check = re.search(r'fun uuidCheck\(column: String\) = """(.*?)"""', SOURCE, re.S).group(1)
    sql = re.sub(r'\$\{uuidCheck\("([a-z_]+)"\)\}', lambda m: uuid_check.replace('$column', m.group(1)), sql)
    if '$' in sql:
        raise ValueError('Unresolved SQL interpolation')
    return sql


EVENTS = constant('CREATE_EVENTS')
INDEX = re.search(r'db.execSQL\("(CREATE INDEX events_history_order[^"\n]+)"\)', SOURCE).group(1)
CAPACITY = re.search(r'db.execSQL\("""\s*(CREATE TRIGGER events_capacity.*?)"""', SOURCE, re.S).group(1)
INSERT = 'INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?, ?)'


def row(number=1, timestamp=2000):
    return (str(uuid.UUID(int=number)), timestamp, 'CALL', 'INCOMING', 1, str(uuid.UUID(int=1)), '1.0', 1000)


class EventSchemaTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='auditlab-schema-')
        self.addCleanup(self.temp.cleanup)
        self.path = str(Path(self.temp.name) / 'test.db')
        self.db = sqlite3.connect(self.path, isolation_level=None)
        self.addCleanup(self.db.close)
        self.db.executescript(EVENTS + ';' + INDEX + ';' + CAPACITY)

    def test_rows_survive_reopen(self):
        self.db.execute(INSERT, row())
        with sqlite3.connect(self.path) as reopened:
            self.assertEqual([row()], reopened.execute('SELECT * FROM events').fetchall())

    def test_duplicate_id_does_not_overwrite(self):
        self.db.execute(INSERT, row())
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(INSERT, row(timestamp=3000))
        self.assertEqual([row()], self.db.execute('SELECT * FROM events').fetchall())

    def test_schema_rejects_invalid_values(self):
        self.db.execute(INSERT, row())
        invalid = [('kind', 'SMS_REAL'), ('direction', 'OTHER'), ('is_simulated', 0),
                   ('timestamp_ms', 999), ('accepted_at_ms', -1), ('event_id', 'bad-id'),
                   ('consent_id', 'bad-id'), ('policy_version', '=1+1'), ('policy_version', '')]
        for column, value in invalid:
            with self.subTest(column=column, value=value), self.assertRaises(sqlite3.IntegrityError):
                self.db.execute(f'UPDATE events SET {column} = ?', (value,))
        self.assertEqual([row()], self.db.execute('SELECT * FROM events').fetchall())

    def test_capacity_trigger_rejects_1001_without_loss(self):
        self.db.executemany(INSERT, [row(i) for i in range(1000)])
        before = self.db.execute('SELECT event_id FROM events ORDER BY event_id').fetchall()
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(INSERT, row(1001))
        self.assertEqual(before, self.db.execute('SELECT event_id FROM events ORDER BY event_id').fetchall())

    def test_history_order_and_limit(self):
        rows = [row(i, 2000 + i // 3) for i in range(150)]
        self.db.executemany(INSERT, rows)
        result = self.db.execute('SELECT * FROM events ORDER BY timestamp_ms DESC, event_id ASC LIMIT ?', (100,)).fetchall()
        self.assertEqual(sorted(rows, key=lambda r: (-r[1], r[0]))[:100], result)
        self.assertEqual(150, self.db.execute('SELECT COUNT(*) FROM events').fetchone()[0])

    def test_failed_delete_rolls_back(self):
        self.db.executemany(INSERT, [row(i) for i in range(3)])
        self.db.executescript("CREATE TRIGGER fail_delete BEFORE DELETE ON events BEGIN SELECT RAISE(ABORT, 'test'); END")
        self.db.execute('BEGIN IMMEDIATE')
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('DELETE FROM events')
        self.db.rollback()
        self.assertEqual(3, self.db.execute('SELECT COUNT(*) FROM events').fetchone()[0])

    def test_independent_connections_count_and_insert_in_one_transaction(self):
        def insert_batch(worker):
            with sqlite3.connect(self.path, isolation_level=None, timeout=10) as db:
                results = []
                for i in range(15):
                    db.execute('BEGIN IMMEDIATE')
                    allowed = db.execute('SELECT COUNT(*) FROM events').fetchone()[0] < 20
                    if allowed:
                        db.execute(INSERT, row(worker * 100 + i))
                    db.commit()
                    results.append(allowed)
                return results
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = sum(pool.map(insert_batch, range(2)), [])
        self.assertEqual(20, results.count(True))
        self.assertEqual(10, results.count(False))
        self.assertEqual(20, self.db.execute('SELECT COUNT(*) FROM events').fetchone()[0])


if __name__ == '__main__':
    print('Host SQLite DDL checks only; Android instrumentation must run separately.', flush=True)
    unittest.main(verbosity=2)
