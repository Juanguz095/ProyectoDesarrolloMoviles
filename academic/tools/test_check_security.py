"""Negative cases for the local policy checker; no Android coverage is inferred."""
import copy
import unittest
import xml.etree.ElementTree as ET

from check_security import ANDROID, ROOT, backup_policy, manifest_policy, parse_aapt_xml, source_policy, extraction_files


class SecurityPolicyTests(unittest.TestCase):
    def setUp(self):
        self.manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        self.rules = ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml").getroot()

    def test_actual_sources_pass(self):
        source_policy(ROOT)

    def test_permissions_and_root_components_are_rejected(self):
        for tag in ["uses-permission", "uses-permission-sdk-23", "permission", "instrumentation", "queries"]:
            with self.subTest(tag=tag):
                root = copy.deepcopy(self.manifest)
                ET.SubElement(root, tag, {ANDROID + "name": "android.permission.INTERNET"})
                with self.assertRaises(ValueError):
                    manifest_policy(root)

    def test_additional_components_are_rejected(self):
        for tag in ["service", "receiver", "provider", "activity", "activity-alias"]:
            root = copy.deepcopy(self.manifest)
            ET.SubElement(root.find("application"), tag, {ANDROID + "exported": "false"})
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                manifest_policy(root)

    def test_backup_and_cleartext_flags_must_be_false(self):
        for flag in ["allowBackup", "fullBackupContent", "usesCleartextTraffic"]:
            root = copy.deepcopy(self.manifest)
            root.find("application").set(ANDROID + flag, "true")
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                manifest_policy(root)

    def test_launcher_must_be_explicit_and_not_receive_data(self):
        root = copy.deepcopy(self.manifest)
        root.find("application/activity").attrib.pop(ANDROID + "exported")
        with self.assertRaises(ValueError):
            manifest_policy(root)
        root = copy.deepcopy(self.manifest)
        ET.SubElement(root.find("application/activity/intent-filter"), "data", {ANDROID + "scheme": "content"})
        with self.assertRaises(ValueError):
            manifest_policy(root)

    def test_test_application_is_rejected(self):
        self.manifest.find("application").set(ANDROID + "name", "com.auditlab.academic.tests.TestApplication")
        with self.assertRaises(ValueError):
            manifest_policy(self.manifest)

    def test_release_must_not_be_debuggable(self):
        self.manifest.set("package", "com.auditlab.academic")
        ET.SubElement(self.manifest, "uses-sdk", {ANDROID + "minSdkVersion": "26", ANDROID + "targetSdkVersion": "36"})
        app = self.manifest.find("application")
        app.set(ANDROID + "debuggable", "true")
        manifest_policy(self.manifest, "debug")
        with self.assertRaises(ValueError):
            manifest_policy(self.manifest, "release")
        app.set(ANDROID + "debuggable", "false")
        manifest_policy(self.manifest, "release")

    def test_all_backup_and_transfer_domains_are_required(self):
        backup_policy(self.rules)
        for index in [0, 1]:
            rules = copy.deepcopy(self.rules)
            rules[index].remove(rules[index][0])
            with self.subTest(section=index), self.assertRaises(ValueError):
                backup_policy(rules)

    def test_backup_include_and_partial_exclusion_are_rejected(self):
        for tag, path in [("include", "."), ("exclude", "events.db")]:
            rules = copy.deepcopy(self.rules)
            rules[0][0].tag = tag
            rules[0][0].set("path", path)
            with self.subTest(tag=tag, path=path), self.assertRaises(ValueError):
                backup_policy(rules)

    def test_aapt_hierarchy_and_namespaced_attributes(self):
        root = parse_aapt_xml('''N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: package="com.auditlab.academic" (Raw: "com.auditlab.academic")
      E: application (line=4)
        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
          E: activity (line=8)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="Main Activity" (Raw: "Main Activity")
''')
        self.assertEqual("com.auditlab.academic", root.get("package"))
        self.assertEqual("false", root.find("application").get(ANDROID + "allowBackup"))
        self.assertEqual("Main Activity", root.find("application/activity").get(ANDROID + "name"))

    def test_empty_or_unknown_aapt_output_is_rejected(self):
        for text in ["", "invalid", "A: package=test", "E: manifest\nE: application"]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_aapt_xml(text)

    def test_wrong_extraction_root_is_rejected(self):
        with self.assertRaises(ValueError):
            backup_policy(ET.fromstring("<full-backup-content />"))

    def test_optimized_and_qualified_backup_paths_are_all_inspected(self):
        resources = """  type xml id=06 entryCount=2
    resource 0x7f060000 xml/data_extraction_rules
      () (file) res/4j.xml type=XML
      (v31) (file) res/xml-v31/data_extraction_rules.xml type=XML
    resource 0x7f060001 xml/other
      () (file) res/other.xml type=XML
"""
        self.assertEqual(("0x7f060000", ["res/4j.xml", "res/xml-v31/data_extraction_rules.xml"]), extraction_files(resources))

    def test_unknown_backup_resource_configuration_is_rejected(self):
        for resources in ["", "resource 0x7f060000 xml/data_extraction_rules\n () @null",
                          "resource 0x7f060000 xml/data_extraction_rules\n () (file) res/../other.xml type=XML"]:
            with self.subTest(resources=resources), self.assertRaises(ValueError):
                extraction_files(resources)


if __name__ == "__main__":
    unittest.main(verbosity=2)
