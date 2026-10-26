"""Recovery checks work for small/empty databases and isolate restored API credentials."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_restore import server_environment, validate_restored_page


class RestoreGuardsTest(unittest.TestCase):
    def test_empty_restored_page(self):
        validate_restored_page({'items': [], 'nextCursor': None}, [])

    def test_small_page_matches_ids_and_device(self):
        validate_restored_page({'items': [{'eventId': 'event-1', 'sourceDevice': 'device-1'}]},
                               [('event-1', 'device-1')])

    def test_wrong_device_does_not_pass_with_same_event_id(self):
        with self.assertRaisesRegex(RuntimeError, 'differs'):
            validate_restored_page({'items': [{'eventId': 'event-1', 'sourceDevice': 'other-device'}]},
                                   [('event-1', 'device-1')])

    def test_missing_or_reordered_rows_fail(self):
        for rows in ([], [('second', 'device-1'), ('first', 'device-1')]):
            with self.subTest(rows=rows), self.assertRaisesRegex(RuntimeError, 'differs'):
                validate_restored_page({'items': [{'eventId': e, 'sourceDevice': d} for e, d in rows]},
                                       [('first', 'device-1'), ('second', 'device-1')])

    def test_server_has_no_administrative_credentials_or_background_purge(self):
        original = {key: 'synthetic' for key in ('AUDITLAB_MIGRATION_PASSWORD',
            'AUDITLAB_BOOTSTRAP_PASSWORD', 'AUDITLAB_TEST_MAINTENANCE_PASSWORD',
            'AUDITLAB_MAINTENANCE_PASSWORD', 'AUDITLAB_TARGET_DB_ROLE', 'PGPASSWORD')}
        original.update(AUDITLAB_DB_JDBC_URL='clone-url', AUDITLAB_DB_USER='runtime',
                        AUDITLAB_DB_PASSWORD='synthetic-runtime', AUDITLAB_AUTH_SIGNING_KEY_BASE64='synthetic',
                        HTTPS_PROXY='http://proxy:8080')
        before = dict(original)
        child = server_environment(original)
        self.assertEqual(before, original)
        self.assertEqual({'AUDITLAB_DB_JDBC_URL': 'clone-url', 'AUDITLAB_DB_USER': 'runtime',
                         'AUDITLAB_DB_PASSWORD': 'synthetic-runtime', 'AUDITLAB_AUTH_SIGNING_KEY_BASE64': 'synthetic',
                         'HTTPS_PROXY': 'http://proxy:8080'}, child)


if __name__ == '__main__': unittest.main()
