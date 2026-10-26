"""Standalone liveness never acts on inherited application resources."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_ktor import isolated_environment

class KtorIsolationTest(unittest.TestCase):
    def test_all_application_targets_are_removed_from_child_only(self):
        original = {key: "synthetic" for key in ["AUDITLAB_DB_JDBC_URL", "AUDITLAB_AUTH_SIGNING_KEY_BASE64",
            "AUDITLAB_TLS_PORT", "AUDITLAB_TLS_CERTIFICATE", "AUDITLAB_BOOTSTRAP_PASSWORD",
            "AUDITLAB_MAINTENANCE_JDBC_URL", "AUDITLAB_MIGRATION_JDBC_URL", "AUDITLAB_TEST_ISOLATED"]}
        original.update(PATH="/bin", HTTPS_PROXY="http://proxy:8080", JAVA_HOME="/jdk")
        before = dict(original)
        child = isolated_environment(original)
        self.assertEqual(before, original)
        self.assertEqual({"PATH": "/bin", "HTTPS_PROXY": "http://proxy:8080", "JAVA_HOME": "/jdk"}, child)

if __name__ == '__main__': unittest.main()
