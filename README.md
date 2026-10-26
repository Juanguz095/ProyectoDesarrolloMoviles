# AuditLab

Sistema de monitoreo autorizado para dispositivos Android propios o administrados: **agente Android + centro de control Android + API Ktor/PostgreSQL**. Versión candidata **0.2.0-rc1**. Solo registra eventos simulados, propios de AuditLab y metadatos permitidos de batería/conectividad. Requiere consentimiento y un indicador visible.

## Qué hace el MVP

- **Agente:** consentimiento e inscripción con código de un uso; Room con cola persistente, retención de siete días y capacidad de 10 000; WorkManager con lotes/reintentos; pausa visible; revocación local incluso sin red; credencial cifrada con Keystore.
- **Centro:** login admin/auditor, sesión renovable, dispositivos y última recepción, historial con filtros/páginas, detalle, CSV mediante el selector Android, auditoría y revocación con confirmación.
- **API:** autenticación, inscripción, recepción idempotente, validación estricta, consultas/exportación, revocación, auditoría, retención y HTTPS. PostgreSQL usa roles separados y migraciones.

La implementación del MVP está presente. Se ejecutaron flujos reales con el agente en Android 9/API28 y el centro en Android 8/API26; la evidencia diferencia los casos individuales de la repetición completa, así como los fallos corregidos. No se declara publicado un piloto ni firmada una distribución del responsable. **Resultado final local: 263 pruebas aprobadas**, incluidas las 17 etapas nativas completas, más 23 respuestas HTTPS validadas. [Resultados y hashes de las APKs probadas](docs/evidencias/artefactos-0.2.0-rc1.json).

## Documentos de entrada

- [Manual de instalación y uso](docs/MANUAL_PRODUCTO.md).
- [Estado real por fase](docs/AVANCE.md) y [plan maestro P0–P8](docs/PLAN_MAESTRO.md).
- [Informe de implementación, pruebas y límites](docs/evidencias/P5-P8-2026-10-25.md).
- [Entorno y pruebas integradas](docs/PRUEBAS_INTEGRADAS.md), [herramientas](docs/desarrollo.md).
- [Despliegue HTTPS, respaldo y restauración](docs/despliegue.md).
- [Contrato OpenAPI](contracts/openapi.yaml), [requisitos](docs/requisitos.md), [estados](docs/estados.md) e [historias/criterios](docs/historias.md).

## Compilar y ejecutar

Desde la raíz, con JDK 21, Android SDK35 y las versiones fijadas en el proyecto:

```bash
python3 scripts/gradle.py :apps:agent:assembleDebug :apps:control:assembleDebug :backend:ktor:installDist --no-daemon --max-workers=1
```

APKs de desarrollo:

- `apps/agent/build/outputs/apk/debug/agent-debug.apk` → dispositivo A.
- `apps/control/build/outputs/apk/debug/control-debug.apk` → dispositivo B.

Preparar PostgreSQL/cuentas/HTTPS según la guía de despliegue. Para desarrollo aislado, [el fixture QA](docs/PRUEBAS_INTEGRADAS.md) genera credenciales privadas, escucha en loopback y conserva los datos. En el emulador se usa `http://10.0.2.2:8081`; en teléfonos, la URL HTTPS del servidor autorizado.

## Comprobaciones principales

```bash
python3 scripts/gradle.py :apps:common:test :apps:agent:testDebugUnitTest :apps:control:testDebugUnitTest :backend:ktor:test --no-daemon --max-workers=1
python3 scripts/check_database.py
python3 scripts/check_apk.py
python3 scripts/local_fixture.py run -- bash scripts/check_tls.sh
python3 scripts/check_dependencies.py --prepare
```

La suite nativa requiere compilar también `assembleDebugAndroidTest` de ambas apps y dos emuladores dedicados ya arrancados:

```bash
.venv/bin/python scripts/check_android.py --agent-serial emulator-5580 --control-serial emulator-5582
```

No borra datos para poder ejecutarse: rechaza inscripciones/sesiones existentes y exige retirarlas/cerrarlas desde las apps. Comprueba los 17 pasos completos y devuelve error si falla o queda incompleto alguno. [Precondiciones y límites](docs/PRUEBAS_INTEGRADAS.md).

## Alcance de seguridad y entrega

Sin permisos de SMS, llamadas, contactos, cámara, micrófono, ubicación ni accesibilidad. No lee contenido de otras apps, no se oculta y permite retirar el consentimiento. HTTPS valida certificado y hostname; HTTP solo se permite en loopback. La revocación remota se conoce en el siguiente contacto del agente; un dispositivo offline no recibe una confirmación inmediata.

Quedan como condiciones externas de entrega: validación en Android reciente y teléfonos propios, clave de distribución del responsable y piloto HTTPS con acceso/autorización. Las APKs debug y los certificados QA sirven para desarrollo. El análisis de dependencias se limita al runtime Maven resuelto y a la base de avisos registrada; no acredita la seguridad de todo un despliegue.

## Entregas anteriores conservadas

- **App académica local `academic/`:** I01–I12 y versión 0.1.0-i12/12. [Entrega](docs/ENTREGA_ACADEMICA.md), [avance](docs/AVANCE_ACADEMICO.md), [manual](docs/USO_ACADEMICO.md), [evidencia en dos emuladores](docs/evidencias/academico/DOS-EMULADORES-2026-10-23.md). Es una entrega independiente; no sustituye el flujo de dos apps/API. Sus fuentes y datos se conservan.
- **Prototipo Python/web `backend/api`:** referencia local con SQLite y [contrato 0.1](contracts/prototype-v0.1.yaml). [Guía](backend/api/README.md). Mantenerlo en loopback; el servicio definitivo es Ktor/PostgreSQL.

Para continuar el trabajo, leer [AGENTS.md](AGENTS.md).
