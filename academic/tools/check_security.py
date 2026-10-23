#!/usr/bin/env python3
"""AuditLab's explicit local-app policy, not a vulnerability scanner or SSDF certification.

Standard library only. Inspect sources, or the actual packaged manifest/backup XML/Dex:
  python3 academic/tools/check_security.py
  python3 academic/tools/check_security.py --apk PATH --aapt2 PATH --variant debug
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {"root", "file", "database", "sharedpref", "external", "device_root",
           "device_file", "device_database", "device_sharedpref"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def manifest_policy(root, variant="source"):
    require(root.tag == "manifest", "Not an Android manifest")
    require(all(n.tag in {"uses-sdk", "application"} for n in root), "Unexpected permission/component/feature")
    apps = root.findall("application")
    require(len(apps) == 1, "Exactly one application required")
    app = apps[0]
    for name in ("allowBackup", "fullBackupContent", "usesCleartextTraffic"):
        require(app.get(ANDROID + name) == "false", f"{name} must be false")
    require(app.get(ANDROID + "dataExtractionRules") is not None, "Backup/transfer rules missing")
    require(app.get(ANDROID + "name") in {".AuditApplication", "com.auditlab.academic.AuditApplication"}, "Wrong Application")
    require(app.get(ANDROID + "testOnly", "false") == "false", "Test-only APK is not the app")
    components = list(app)
    require(len(components) == 1 and components[0].tag == "activity", "Only the launcher Activity may be declared")
    activity = components[0]
    require(activity.get(ANDROID + "name") in {".MainActivity", "com.auditlab.academic.MainActivity"}, "Unexpected Activity")
    require(activity.get(ANDROID + "exported") == "true", "Launcher must be explicitly exported")
    filters = activity.findall("intent-filter")
    require(len(filters) == 1 and len(list(activity)) == 1, "Unexpected Activity filter/metadata")
    nodes = {(n.tag, n.get(ANDROID + "name")) for n in filters[0]}
    require(len(list(filters[0])) == 2 and nodes == {
        ("action", "android.intent.action.MAIN"), ("category", "android.intent.category.LAUNCHER")}, "Only launcher intents allowed")
    if variant != "source":
        require(root.get("package") == "com.auditlab.academic", "Wrong APK package")
        sdk = root.findall("uses-sdk")
        require(len(sdk) == 1 and sdk[0].get(ANDROID + "minSdkVersion") == "26"
                and sdk[0].get(ANDROID + "targetSdkVersion") == "36", "Unexpected SDK range")
        require(app.get(ANDROID + "debuggable", "false") == str(variant == "debug").lower(), "Wrong debuggable flag")
    return app


def backup_policy(root):
    require(root.tag == "data-extraction-rules", "Unexpected backup resource")
    require([n.tag for n in root] == ["cloud-backup", "device-transfer"], "Both backup and device-transfer sections required")
    for section in root:
        require(len(list(section)) == len(DOMAINS), "All storage domains must be excluded exactly once")
        require(all(n.tag == "exclude" and n.get("path") == "." for n in section), "Backup include/unexpected path")
        require({n.get("domain") for n in section} == DOMAINS, "Incomplete backup exclusions")


def parse_aapt_xml(text):
    """Parse aapt2 xmltree's hierarchy; reject unknown output rather than approve it."""
    stack, root = [], None
    for line in text.splitlines():
        stripped = line.lstrip()
        indent = len(line) - len(stripped)
        if stripped.startswith("E: "):
            match = re.fullmatch(r"E: ([\w-]+)(?: \(line=\d+\))?", stripped)
            require(match is not None, "Unrecognized aapt2 element")
            node = ET.Element(match.group(1))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                stack[-1][1].append(node)
            else:
                require(root is None, "Multiple XML roots")
                root = node
            stack.append((indent, node))
        elif stripped.startswith("A: "):
            require(bool(stack), "Attribute without element")
            match = re.fullmatch(r'A: (.*?)(?:\(0x[0-9a-f]+\))?=(.*?)(?: \(Raw: .*\))?', stripped)
            require(match is not None, "Unrecognized aapt2 attribute")
            name, value = match.groups()
            name = name.replace("http://schemas.android.com/apk/res/android:", ANDROID)
            if value.startswith('"'):
                value = json.loads(value)
            stack[-1][1].set(name, value)
        elif stripped:
            require(stripped.startswith("N: "), "Unrecognized aapt2 XML output")
    require(root is not None, "Empty aapt2 XML output")
    return root


def source_policy(root):
    manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
    app = manifest_policy(manifest)
    require(app.get(ANDROID + "dataExtractionRules") == "@xml/data_extraction_rules", "Wrong source backup resource")
    backup_policy(ET.parse(root / "app/src/main/res/xml/data_extraction_rules.xml").getroot())
    sources = list((root / "app/src/main/java").rglob("*.kt")) + list((root / "domain/src/main").rglob("*.kt"))
    require(bool(sources), "No application sources found")
    forbidden = re.compile(r"android\.(?:telephony|service\.notification)|android\.accessibilityservice|"
                           r"(?:java|javax)\.net\.|okhttp3?\.|retrofit2?\.|android\.util\.Log|"
                           r"\b(?:println|printStackTrace|requestPermissions)\s*\(")
    for source in sources:
        require(not forbidden.search(source.read_text()), f"Capability/log call outside academic scope: {source.relative_to(root)}")
    build = (root / "build.gradle.kts").read_text()
    require('version "9.1.1"' in build and 'version "2.2.10"' in build, "Tool pins changed")
    wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text()
    require("gradle-9.3.1-bin.zip" in wrapper and
            "distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06" in wrapper,
            "Gradle distribution/version verification changed")
    require(not re.search(r'\b(?:implementation|api|runtimeOnly)\s*\(\s*["\']',
                          (root / "app/build.gradle.kts").read_text()), "Unreviewed production dependency")


def extraction_files(resources):
    """Release optimizers rename paths; validate every configuration of the referenced resource."""
    resource_id, paths, active = None, [], False
    for line in resources.splitlines():
        if re.match(r"\s*(?:resource |type )", line):
            if active:
                break
            match = re.fullmatch(r"\s*resource (0x[0-9a-f]+) xml/data_extraction_rules", line)
            if match:
                resource_id, active = match.group(1), True
        elif active and line.strip():
            match = re.fullmatch(r"\s*\([^)]*\) \(file\) (res/[^\s]+\.xml) type=XML", line)
            require(match is not None, "Unrecognized backup resource configuration")
            require(".." not in Path(match.group(1)).parts, "Invalid packaged resource path")
            paths.append(match.group(1))
    require(resource_id is not None and bool(paths), "Packaged backup resource missing")
    return resource_id, sorted(set(paths))


def apk_policy(apk, aapt2, variant):
    def dump(*args):
        return subprocess.run([str(aapt2), "dump", *args, str(apk)], check=True,
                              capture_output=True, text=True, timeout=30).stdout
    manifest = parse_aapt_xml(dump("xmltree", "--file", "AndroidManifest.xml"))
    app = manifest_policy(manifest, variant)
    resources = dump("resources")
    resource_id, paths = extraction_files(resources)
    require(app.get(ANDROID + "dataExtractionRules") == "@" + resource_id, "Effective backup resource mismatch")
    for path in paths:
        backup_policy(parse_aapt_xml(dump("xmltree", "--file", path)))
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            require(not re.search(r"\.(?:db|sqlite|jks|keystore|p12|csv)$|(?:^|/)\.env(?:$|\.)", name), "Data/key file packaged")
            if re.fullmatch(r"classes\d*\.dex", name):
                require(b"Lcom/auditlab/academic/tests/" not in archive.read(name), "Test fixtures leaked into app Dex")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--variant", choices=["debug", "release"], default="debug")
    args = parser.parse_args()
    if bool(args.apk) != bool(args.aapt2):
        parser.error("--apk and --aapt2 must be provided together")
    try:
        source_policy(ROOT)
        print("Academic source policy passed: manifest, backup/transfer exclusions, capabilities and tool pins.")
        if args.apk:
            apk_policy(args.apk.resolve(), args.aapt2.resolve(), args.variant)
            print(f"Packaged {args.variant} APK policy passed: manifest, effective backup XML and Dex isolation.")
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Security policy failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
