"""The APK audit follows the manifest's compiled resource, including all variants."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_apk import xml_resource_paths

class CompiledResourcesTest(unittest.TestCase):
    def test_debug_filename(self):
        self.assertEqual(['res/xml/network_security_config.xml'], xml_resource_paths('resource 0x7f010001 xml/network_security_config\n  () (file) res/xml/network_security_config.xml type=XML', '0x7f010001'))

    def test_release_renamed_file_is_resolved_by_id(self):
        table = 'resource 0x7f0c0000 xml/data_extraction_rules\n  () (file) res/4j.xml type=XML\nresource 0x7f0c0001 xml/network_security_config\n  () (file) res/8G.xml type=XML\nresource 0x7f0c0002 xml/other\n  () (file) res/other.xml type=XML'
        self.assertEqual(['res/8G.xml'], xml_resource_paths(table, '0x7f0c0001'))

    def test_all_configuration_variants_are_audited(self):
        table = 'resource 0x7f010001 xml/policy\n  () (file) res/a.xml type=XML\n  (v28) (file) res/b.xml type=XML\n type raw id=0d\n  () (file) res/unrelated.xml type=XML'
        self.assertEqual(['res/a.xml','res/b.xml'], xml_resource_paths(table, '0x7f010001'))

    def test_missing_or_non_xml_reference_fails_closed(self):
        for table in ['', 'resource 0x7f010002 xml/other\n () (file) res/a.xml type=XML', 'resource 0x7f010001 xml/alias\n () @xml/other', 'resource 0x7f010001 xml/policy\n () (file) ../policy.xml type=XML']:
            with self.assertRaises(RuntimeError): xml_resource_paths(table, '0x7f010001')

if __name__ == '__main__': unittest.main()
