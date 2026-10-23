#!/usr/bin/env python3
"""Build and verify the academic app, preserving actual outputs in a NEW directory.

Requires JDK17 and Android SDK36/build-tools36.0.0. No new Python packages.
Pass --android-serial to execute real Android tests; otherwise Android stays pending.
The optional --gradle-helper selects the cloud's existing proxy/CA/toolchain helper.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def junit_results(directory, expected, not_before=0):
    files = sorted(directory.rglob("TEST-*.xml"))
    if not files:
        raise ValueError(f"No JUnit reports: {directory}")
    totals = dict.fromkeys(["tests", "failures", "errors", "skipped"], 0)
    identities = set()
    for path in files:
        if path.stat().st_mtime < not_before - 2:
            raise ValueError(f"Stale JUnit report: {path}")
        root = ET.parse(path).getroot()
        cases = list(root.iter("testcase"))
        if root.tag not in {"testsuite", "testsuites"} or int(root.get("tests", "-1")) != len(cases):
            raise ValueError(f"Unrecognized/mismatched JUnit report: {path}")
        if root.tag == "testsuites":
            suites = root.findall("testsuite")
            if not suites or len(suites) != len(list(root)):
                raise ValueError(f"Unrecognized aggregate suites: {path}")
            for suite in suites:
                if int(suite.get("tests", "-1")) != len(suite.findall("testcase")):
                    raise ValueError(f"Mismatched child suite: {path}")
            for key in totals:
                if int(root.get(key, "0")) != sum(int(s.get(key, "0")) for s in suites):
                    raise ValueError(f"Mismatched aggregate counter {key}: {path}")
        for key in totals:
            totals[key] += int(root.get(key, "0"))
        for case in cases:
            identity = (case.get("classname"), case.get("name"))
            if identity in identities:
                raise ValueError(f"Duplicate testcase: {identity}")
            identities.add(identity)
            if any(case.find(tag) is not None for tag in ["failure", "error", "skipped"]):
                raise ValueError(f"Unsuccessful testcase: {identity}")
    if totals != {"tests": expected, "failures": 0, "errors": 0, "skipped": 0}:
        raise ValueError(f"Expected {expected} successful cases, got {totals}")
    return totals


def source_digest(root):
    files = set()
    for directory in ["app/src", "domain/src", "gradle/wrapper", "tools"]:
        files.update(p for p in (root / directory).rglob("*") if p.is_file() and "__pycache__" not in p.parts)
    for name in ["build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
                 "app/build.gradle.kts", "domain/build.gradle.kts"]:
        files.add(root / name)
    digest = hashlib.sha256()
    for path in sorted(files):
        digest.update(path.relative_to(root).as_posix().encode() + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def instrumentation_report(log, destination, expected):
    status, started, finished = {}, set(), set()
    suite = ET.Element("testsuite", name="AuditLabTestRunner")
    failures = 0
    for line in log.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            identity = (status.get("class"), status.get("test"))
            if None in identity or int(status.get("numtests", "0")) != expected:
                raise ValueError("Missing or inconsistent instrumentation identity/count")
            if code == 1:
                if identity in started:
                    raise ValueError("Duplicate instrumentation start")
                started.add(identity)
            elif code in [0, -1, -2]:
                if identity not in started or identity in finished:
                    raise ValueError("Unexpected/duplicate instrumentation completion")
                finished.add(identity)
                case = ET.SubElement(suite, "testcase", classname=identity[0], name=identity[1])
                if code != 0:
                    failures += 1
                    ET.SubElement(case, "failure", message=status.get("stack", "instrumentation failure"))
            else:
                raise ValueError(f"Unexpected instrumentation status: {code}")
            status = {}
    suite.attrib.update(tests=str(len(finished)), failures=str(failures), errors="0", skipped="0")
    destination.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(destination, encoding="utf-8", xml_declaration=True)
    if started != finished or len(finished) != expected or f"Tests run: {expected}, Failures: 0" not in log:
        raise ValueError("Incomplete or unsuccessful instrumentation run; inspect its raw log")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-root", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--gradle-helper", type=Path)
    parser.add_argument("--android-serial")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.sdk_root is None:
        parser.error("Provide --sdk-root or ANDROID_HOME/ANDROID_SDK_ROOT")
    if args.gradle_helper and not args.gradle_helper.is_file():
        parser.error("Gradle helper not found")
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = (args.output or ROOT / "build/verification" / stamp).resolve()
    try:
        output.mkdir(parents=True, exist_ok=False)
    except FileExistsError:
        parser.error("Output already exists; choose a new path to preserve previous evidence")
    summary = {"started_utc": stamp, "status": "running", "commands": [], "source_sha256": source_digest(ROOT)}
    env = os.environ.copy()
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
    if args.android_serial:
        env["ANDROID_SERIAL"] = args.android_serial

    def run(label, command, timeout=1800):
        command = [str(part) for part in command]
        print(f"Running {label}...", flush=True)
        started = time.monotonic()
        with (output / f"{label}.log").open("w") as log:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        summary["commands"].append({"stage": label, "command": command, "exit_code": result.returncode,
                                    "seconds": round(time.monotonic() - started, 3)})
        if result.returncode:
            raise ValueError(f"{label} failed ({result.returncode}); inspect {output / (label + '.log')}")
        return (output / f"{label}.log").read_text()

    try:
        summary["base_commit"] = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True,
                                                text=True, check=True).stdout.strip()
        summary["source_worktree_dirty"] = bool(subprocess.run(["git", "status", "--porcelain", "--", "academic"],
            cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip())
        java = (Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
        java_version = run("java", [java, "-version"], timeout=30)
        if not re.search(r'version "17[.\"]', java_version):
            raise ValueError("JDK17 required; select it with --java-home")
        sdk = args.sdk_root.resolve()
        aapt2, signer, adb = [sdk / p for p in ["build-tools/36.0.0/aapt2", "build-tools/36.0.0/apksigner", "platform-tools/adb"]]
        if args.android_serial:
            api = run("device-api", [adb, "-s", args.android_serial, "shell", "getprop", "ro.build.version.sdk"], timeout=30).strip()
            if not api.isdigit() or int(api) < 26:
                raise ValueError("An authorized Android API26+ device must be connected")
            summary["device"] = {"serial": args.android_serial, "api": int(api)}
        for label, command, expected in [
            ("host-unit", [sys.executable, "-m", "unittest", "discover", "-s", ROOT / "tools", "-p", "test_*.py", "-v"], 23),
            ("host-sqlite", [sys.executable, ROOT / "tools/check_sqlite_schema.py"], 15),
        ]:
            log = run(label, command)
            count = re.search(r"\bRan (\d+) tests? in ", log)
            if not count or int(count.group(1)) != expected or not re.search(r"\nOK\s*$", log):
                raise ValueError(f"Unexpected/omitted host cases: {label}")
            summary[label] = {"tests": expected, "failures": 0, "errors": 0, "skipped": 0}
        run("source-policy", [sys.executable, ROOT / "tools/check_security.py"])
        gradle = [sys.executable, args.gradle_helper.resolve()] if args.gradle_helper else [ROOT / "gradlew"]
        tasks = [":domain:test", ":domain:build", ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                 ":app:assembleRelease", ":app:lintDebug", ":app:lintRelease"]
        build_started = time.time()
        run("gradle", gradle + tasks + ["--rerun-tasks", "--no-daemon", "--max-workers=1"])
        summary["jvm"] = junit_results(ROOT / "domain/build/test-results/test", 93, build_started)
        for variant in ["debug", "release"]:
            report = ROOT / f"app/build/reports/lint-results-{variant}.xml"
            if report.stat().st_mtime < build_started - 2 or list(ET.parse(report).getroot().iter("issue")):
                raise ValueError(f"Missing/stale/unclean lint report: {variant}")
        summary["lint_issues"] = {"debug": 0, "release": 0}
        if args.android_serial:
            # Gradle's connected installer may uninstall the app afterwards. Update in place,
            # fail on incompatible signing/version, and never uninstall or clear existing data.
            for label, path in [("app", "app/build/outputs/apk/debug/app-debug.apk"),
                                ("tests", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]:
                run(f"install-{label}", [adb, "-s", args.android_serial, "install", "-r", "-t", ROOT / path], timeout=120)
            instrumentation_started = time.time()
            raw = run("instrumentation", [adb, "-s", args.android_serial, "shell", "am", "instrument", "-w", "-r",
                "com.auditlab.academic.test/com.auditlab.academic.tests.AuditLabTestRunner"], timeout=600)
            instrumentation_report(raw, output / "reports/android/TEST-instrumentation.xml", 55)
            summary["android"] = junit_results(output / "reports/android", 55, instrumentation_started)
        else:
            summary["android"] = {"status": "not executed; device required"}
        artifacts = output / "artifacts"
        artifacts.mkdir()
        summary["apks"] = []
        certificates = []
        for variant, path in [
            ("debug", "app/build/outputs/apk/debug/app-debug.apk"),
            ("androidTest", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
            ("release", "app/build/outputs/apk/release/app-release-unsigned.apk"),
        ]:
            target = artifacts / Path(path).name
            shutil.copy2(ROOT / path, target)
            data = target.read_bytes()
            summary["apks"].append({"variant": variant, "file": f"artifacts/{target.name}", "bytes": len(data),
                                    "sha256": hashlib.sha256(data).hexdigest(), "signed": variant != "release"})
            if variant != "androidTest":
                run(f"policy-{variant}", [sys.executable, ROOT / "tools/check_security.py", "--apk", target,
                    "--aapt2", aapt2, "--variant", variant])
            if variant != "release":
                signature = run(f"signature-{variant}", [signer, "verify", "--verbose", "--print-certs", target])
                match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", signature)
                if not match:
                    raise ValueError("No verified signing certificate")
                certificates.append(match.group(1))
        if len(set(certificates)) != 1:
            raise ValueError("App/test signatures differ")
        summary["debug_signer_sha256"] = certificates[0]
        for label, directory in [("jvm", ROOT / "domain/build/test-results/test")]:
            for index, report in enumerate(sorted(directory.rglob("TEST-*.xml"))):
                dest = output / "reports" / label / f"{index}-{report.name}"
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(report, dest)
        if source_digest(ROOT) != summary["source_sha256"]:
            raise ValueError("Sources changed during verification; rerun against a fixed revision")
        summary["status"] = "passed" if args.android_serial else "host passed; Android pending"
    except (OSError, ValueError, ET.ParseError, subprocess.SubprocessError) as error:
        summary["status"], summary["error"] = "failed", str(error)
    finally:
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    if summary["status"] == "failed":
        print(summary["error"], file=sys.stderr)
        return 1
    (output / "LEEME.txt").write_text(
        "AuditLab académico: instalar artifacts/app-debug.apk. Es una firma de desarrollo.\n"
        "Resultados, límites y hashes en summary.json; XML en reports/.\n"
        "El release sin firma y el APK de instrumentación quedan fuera del ZIP de instalación.\n"
        "No se infiere validación física, IDE ni rúbrica a partir de estas pruebas.\n")
    with zipfile.ZipFile(output / "auditlab-verificacion.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.rglob("*")):
            if path.is_file() and path.name != "auditlab-verificacion.zip" and path.name not in {"app-debug-androidTest.apk", "app-release-unsigned.apk"}:
                archive.write(path, path.relative_to(output))
    print(json.dumps({"status": summary["status"], "output": str(output), "jvm": summary["jvm"],
                      "android": summary["android"]}), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
