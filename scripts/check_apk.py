"""Audita los APK ya construidos: permisos, tráfico en claro, componentes y contenido.

No ejecuta la app. Comprueba el artefacto real (manifiesto compilado, política de red,
firma y dex) para respaldar afirmaciones que no deben quedar solo en el código fuente.

Uso:
    python3 scripts/check_apk.py                 # ambos APK debug
    python3 scripts/check_apk.py --profile debug apps/agent/build/.../agent-debug.apk
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

EXPECTED = {
    "com.auditlab.agent": {
        "min_sdk": "26",
        "target_sdk": "35",
        "classes": [
            "Lcom/auditlab/agent/MainActivity;",
            "Lcom/auditlab/agent/AgentSession;",
            "Lcom/auditlab/agent/KeystoreCredentialStore;",
            "Lcom/auditlab/agent/RealMonitoringService;",
            "Lcom/auditlab/agent/PhoneMetadata;",
            "Lcom/auditlab/common/AgentCore;",
            "Lcom/auditlab/common/ApiClient;",
        ],
    },
    "com.auditlab.control": {
        "min_sdk": "26",
        "target_sdk": "35",
        "classes": [
            "Lcom/auditlab/control/MainActivity;",
            "Lcom/auditlab/control/ControlSession;",
            "Lcom/auditlab/control/KeystoreSessionStore;",
            "Lcom/auditlab/common/ControlCore;",
            "Lcom/auditlab/common/ApiClient;",
        ],
    },
}

# Scope approved: only the agent may request READ_SMS/READ_CALL_LOG for metadata.
FORBIDDEN = [
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.CALL_PHONE",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.PACKAGE_USAGE_STATS",
]

# Permisos esperados además de INTERNET: el que AndroidX añade para receptores dinámicos.
DYNAMIC_RECEIVER_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

failures: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    failures.append(message)
    print(f"  FALLO  {message}")


def ok(message: str) -> None:
    print(f"  ok     {message}")


def run(command: list[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, errors="replace")
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} -> {result.returncode}: {result.stderr.strip()[:400]}")
    return result.stdout


def sdk_tool(name: str) -> Path:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and (ROOT / ".tooling/android-sdk").is_dir():
        sdk = str(ROOT / ".tooling/android-sdk")
    if not sdk:
        raise SystemExit("Falta ANDROID_HOME o .tooling/android-sdk")
    versions = sorted((Path(sdk) / "build-tools").iterdir(), key=lambda p: (p.name == "35.0.0", p.name), reverse=True)
    for version in versions:
        candidate = version / name
        if candidate.exists():
            return candidate
    raise SystemExit(f"No se encontró {name} en {sdk}/build-tools")


def manifest_tree(aapt2: Path, apk: Path) -> list[str]:
    return run([str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)]).splitlines()


def xml_resource_paths(resources: str, resource_id: str) -> list[str]:
    """Resolve every compiled variant, including release's shortened filenames."""
    selected = False
    paths = []
    for line in resources.splitlines():
        entry = re.match(r"\s*resource (0x[0-9a-fA-F]+)\s", line)
        if entry: selected = int(entry.group(1), 16) == int(resource_id, 16)
        elif re.match(r"\s*(?:type|package)\s", line): selected = False
        elif selected:
            value = re.search(r"\(file\) (\S+) type=XML", line)
            if value:
                path = value.group(1)
                if not path.startswith("res/") or not path.endswith(".xml") or ".." in path.split("/"):
                    raise RuntimeError("ruta XML compilada no válida")
                paths.append(path)
    if not paths: raise RuntimeError("el recurso del manifiesto no resuelve a una política XML")
    return list(dict.fromkeys(paths))


def audit(apk: Path, profile: str) -> None:
    print(f"\nAPK {apk.relative_to(ROOT) if apk.is_relative_to(ROOT) else apk} ({apk.stat().st_size / 1_000_000:.1f} MB)")
    aapt2 = sdk_tool("aapt2")
    badging = run([str(aapt2), "dump", "badging", str(apk)])

    package = re.search(r"package: name='([^']+)'", badging)
    if not package:
        fail("no se pudo leer el paquete")
        return
    name = package.group(1)
    expected = EXPECTED.get(name)
    if expected is None:
        fail(f"paquete inesperado {name}")
        return
    ok(f"paquete {name}")

    min_sdk = re.search(r"minSdkVersion:'([^']+)'", badging)
    target_sdk = re.search(r"targetSdkVersion:'([^']+)'", badging)
    if min_sdk and min_sdk.group(1) == expected["min_sdk"]:
        ok(f"minSdk {min_sdk.group(1)}")
    else:
        fail(f"minSdk {min_sdk.group(1) if min_sdk else 'ausente'} != {expected['min_sdk']}")
    if target_sdk and target_sdk.group(1) == expected["target_sdk"]:
        ok(f"targetSdk {target_sdk.group(1)}")
    else:
        fail(f"targetSdk {target_sdk.group(1) if target_sdk else 'ausente'} != {expected['target_sdk']}")

    # Permisos declarados en el manifiesto compilado.
    permissions = re.findall(r"uses-permission: name='([^']+)'", badging)
    allowed = {"android.permission.INTERNET", name + DYNAMIC_RECEIVER_SUFFIX}
    if name == "com.auditlab.agent":
        allowed.update({"android.permission.ACCESS_NETWORK_STATE", "android.permission.POST_NOTIFICATIONS",
                        "android.permission.WAKE_LOCK", "android.permission.RECEIVE_BOOT_COMPLETED",
                        "android.permission.READ_SMS", "android.permission.READ_CALL_LOG",
                        "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"})
    unexpected = [p for p in permissions if p not in allowed]
    if unexpected:
        fail(f"permisos no previstos: {', '.join(unexpected)}")
    else:
        ok(f"permisos: {', '.join(sorted(permissions)) or 'ninguno'}")
    metadata_permissions = {"android.permission.READ_SMS", "android.permission.READ_CALL_LOG"} if name == "com.auditlab.agent" else set()
    forbidden = [p for p in permissions if p in FORBIDDEN and p not in metadata_permissions]
    if forbidden:
        fail(f"permisos prohibidos presentes: {', '.join(forbidden)}")

    # Atributos de seguridad de <application>.
    tree = manifest_tree(aapt2, apk)
    joined = "\n".join(tree)
    for attribute, value, label in [
        ("usesCleartextTraffic", "false", "tráfico en claro desactivado por defecto"),
        ("allowBackup", "false", "copias de seguridad desactivadas"),
        ("networkSecurityConfig", None, "política de red declarada"),
        ("dataExtractionRules", None, "reglas de extracción declaradas"),
    ]:
        pattern = rf"{attribute}\(0x[0-9a-f]+\)={re.escape(value) if value else r'[^ ]+'}"
        if re.search(pattern, joined):
            ok(label)
        else:
            fail(f"falta {attribute}" + (f"={value}" if value else ""))

    debuggable = re.search(r"debuggable\(0x[0-9a-f]+\)=(\w+)", joined)
    if profile == "debug":
        if debuggable and debuggable.group(1) == "true":
            notes.append(f"{name}: APK debug es depurable (esperado en perfil debug)")
            ok("debuggable=true (esperado en debug)")
        else:
            fail("el APK debug debería ser debuggable")
    elif debuggable and debuggable.group(1) == "true":
        fail("el APK de release no debe ser depurable")

    # Componentes exportados: solo la actividad de lanzamiento y el receptor de perfiles,
    # este último protegido por el permiso de sistema DUMP.
    components = []
    current = None
    for index, line in enumerate(tree):
        match = re.match(r"\s+E: (activity|activity-alias|service|receiver|provider) ", line)
        if match:
            indent = len(line) - len(line.lstrip())
            end = index + 1
            while end < len(tree):
                other = tree[end]
                if other.strip() and len(other) - len(other.lstrip()) <= indent:
                    break
                end += 1
            block = "\n".join(tree[index:end])
            component_name = re.search(r'name\(0x[0-9a-f]+\)="([^"]+)"', block)
            exported = re.search(r"exported\(0x[0-9a-f]+\)=(true|false)", block)
            permission = re.search(r'permission\(0x[0-9a-f]+\)="([^"]+)"', block)
            current = {
                "type": match.group(1),
                "name": component_name.group(1) if component_name else "?",
                "exported": bool(exported and exported.group(1) == "true"),
                "permission": permission.group(1) if permission else None,
            }
            components.append(current)
    exported = [c for c in components if c["exported"]]
    unexpected_exported = [
        c for c in exported
        if not (c["name"] == name + ".MainActivity" or
                (c["name"] == "androidx.profileinstaller.ProfileInstallReceiver" and c["permission"] == "android.permission.DUMP") or
                (name == "com.auditlab.agent" and c["name"] == "androidx.work.impl.diagnostics.DiagnosticsReceiver" and c["permission"] == "android.permission.DUMP") or
                (name == "com.auditlab.agent" and c["name"] == "androidx.work.impl.background.systemjob.SystemJobService" and c["permission"] == "android.permission.BIND_JOB_SERVICE"))
    ]
    if unexpected_exported:
        fail("componentes exportados sin justificar: " + ", ".join(c["name"] for c in unexpected_exported))
    else:
        ok("exportados: " + ", ".join(f"{c['name']} ({c['permission'] or 'launcher'})" for c in exported))

    # Política de red compilada.
    try:
        resource_id = re.search(r"networkSecurityConfig\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)", joined)
        if resource_id is None: raise RuntimeError("referencia compilada de política ausente")
        resources = run([str(aapt2), "dump", "resources", str(apk)])
        paths = xml_resource_paths(resources, resource_id.group(1))
        policies = [(path, run([str(aapt2), "dump", "xmltree", "--file", path, str(apk)])) for path in paths]
    except RuntimeError as error:
        fail(f"no se pudo leer la política de red: {error}")
    else:
        for path, policy in policies:
            if "cleartextTrafficPermitted=false" not in policy:
                fail(f"la política {path} permite tráfico en claro por defecto")
            else:
                ok(f"política de red {path}: HTTP solo en dominios de loopback")
            domains = re.findall(r"T: '([^']+)'", policy)
            if sorted(domains) != ["10.0.2.2", "127.0.0.1", "localhost"]:
                fail(f"dominios con HTTP permitido inesperados: {domains}")
            else:
                ok("dominios permitidos: 10.0.2.2, 127.0.0.1, localhost")

    # Firma verificable.
    apksigner = sdk_tool("apksigner")
    try:
        signature = run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)])
    except RuntimeError as error:
        fail(f"firma no verificable: {error}")
    else:
        schemes = re.findall(r"Verified using (v\d+) scheme[^:]*: true", signature)
        digest = re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F]+)", signature)
        ok(f"firma verificada ({', '.join(schemes) or 'esquema no informado'}); SHA-256 {digest.group(1)[:16] + '…' if digest else '?'}")

    # Clases esperadas presentes en el dex (no basta con que compile el módulo).
    with tempfile.TemporaryDirectory() as directory:
        with zipfile.ZipFile(apk) as archive:
            dex_files = [n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            if not dex_files:
                fail("el APK no contiene dex")
                return
            descriptors = ""
            dexdump = sdk_tool("dexdump")
            for dex in dex_files:
                target = Path(directory) / dex
                target.write_bytes(archive.read(dex))
                descriptors += run([str(dexdump), "-f", str(target)])
    test_descriptors = ["Lorg/junit/", "Lorg/robolectric/", "Landroidx/compose/ui/test/",
                        "Lcom/auditlab/agent/NativeAgentFlowTest;", "Lcom/auditlab/control/NativeControlFlowTest;"]
    if any(marker in descriptors for marker in test_descriptors):
        fail("clases de pruebas presentes en el APK de la app")
    else:
        ok("sin clases JUnit/Robolectric/instrumentación dentro de la app")
    missing = [c for c in expected["classes"] if c not in descriptors]
    if missing:
        fail("clases ausentes del dex: " + ", ".join(missing))
    else:
        ok(f"{len(expected['classes'])} clases propias presentes en el dex")

    # No permissions beyond the approved optional metadata readers and normal runtime needs.
    dangerous = [p for p in permissions if p in FORBIDDEN and p not in metadata_permissions]
    if not dangerous:
        ok("sin permisos fuera del alcance autorizado; lectura opcional de metadatos solo en el agente")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="*", type=Path)
    parser.add_argument("--profile", choices=["debug", "release"], default="debug")
    args = parser.parse_args()
    apks = args.apk or [
        ROOT / "apps/agent/build/outputs/apk/debug/agent-debug.apk",
        ROOT / "apps/control/build/outputs/apk/debug/control-debug.apk",
    ]
    for apk in apks:
        if not apk.exists():
            print(f"Falta {apk}. Construye los APK primero.")
            return 2
        audit(apk, args.profile)
    print()
    for note in notes:
        print(f"nota: {note}")
    if failures:
        print(f"\n{len(failures)} comprobación(es) del APK fallaron.")
        return 1
    print("Auditoría de APK correcta.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
