# AuditLab

Sistema de monitoreo autorizado para dispositivos Android propios o administrados: **agente Android + centro de control Android + API Ktor/PostgreSQL**. Versión **0.4.0-preview**: metadatos reales de SMS/llamadas cuando Android concede los permisos, batería/conexión, pantalla compartida y vistas previas opcionales de notificaciones de WhatsApp. Requiere consentimiento e indicador visible. [Activación, alcance y límites](docs/CAPTURA_REAL.md).

## Versión 0.4.0-preview

Incluye el diseño Android de los mockups, pantalla compartida con autorización
MediaProjection por sesión y vistas previas de notificaciones nuevas de WhatsApp
con consentimiento adicional y permiso Android. No recupera historial de chats.
**Actualizar ambos APK y el servidor**: la API anterior no incluye estas rutas.

- [Descargas y pasos para Windows y dos teléfonos, sin USB](docs/INICIO_WINDOWS_0.4.md).
- [Pantalla compartida](docs/COMPARTIR_PANTALLA.md) y [notificaciones de WhatsApp](docs/WHATSAPP_NOTIFICACIONES.md).
- [Validación 0.4](docs/evidencias/WHATSAPP-0.4.0-2026-10-29.md): 197 pruebas JVM/Robolectric/PostgreSQL y recorrido en dos AVD aprobados con entradas de notificación sintéticas. Pendiente WhatsApp instalado y teléfonos físicos.

## Qué hace el MVP

- **Agente:** consentimiento e inscripción con código de un uso; Room con cola persistente, retención de siete días y capacidad de 10 000; WorkManager con lotes/reintentos; pausa visible; revocación local incluso sin red; credencial cifrada con Keystore.
- **Centro:** login admin/auditor, sesión renovable, dispositivos y última recepción, historial con filtros/páginas, detalle, CSV mediante el selector Android, auditoría y revocación con confirmación.
- **API:** autenticación, inscripción, recepción idempotente, validación estricta, consultas/exportación, revocación, auditoría, retención y HTTPS. PostgreSQL usa roles separados y migraciones.

La candidata 0.3 requiere sus propias comprobaciones de captura real, registradas en [AVANCE](docs/AVANCE.md).
La versión anterior **0.2** tuvo 278 casos aprobados, incluidas 17 etapas nativas con agente
Android 9/API28 y centro Android 8/API26; eso no acredita la ampliación de SMS/llamadas.
[Informe histórico](docs/evidencias/P7-P8-2026-10-26.md), [hashes históricos 0.2](docs/evidencias/artefactos-0.2.0-rc1-2026-10-26.json).
No se declara publicado un piloto ni firmada una distribución del responsable.

## Documentos de entrada

- [Manual de instalación y uso](docs/MANUAL_PRODUCTO.md).
- [Conectar Windows y los celulares sin USB](docs/PRUEBA_SIN_USB.md).
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

## Entrega para desarrollo local

Después de aprobar la suite nativa, `scripts/package_qa.py` genera un ZIP con las dos APKs instalables, la API compilada, los manuales y el código versionado con wrappers/pruebas. Incluye `LEEME.md`, manifiesto y checksums; exige que los hashes coincidan con las APKs probadas. [Comando y verificación](docs/PRUEBAS_INTEGRADAS.md#paquete-instalable-de-qa).

Para probar localmente se usan dos emuladores y el fixture en loopback; no hace falta dominio. La instalación en teléfonos y el piloto HTTPS siguen los requisitos de entrega del plan. El usuario confirmó que actualmente no dispone de servidor/dominio, clave de distribución ni teléfonos de prueba.

## Alcance de seguridad y entrega

El Agente solicita opcionalmente READ_SMS/READ_CALL_LOG para consultar únicamente tipo y fecha desde la activación. No consulta cuerpos ni números; no solicita contactos, cámara, micrófono, ubicación ni accesibilidad. No se oculta y permite retirar el consentimiento. HTTPS valida certificado y hostname; HTTP solo se permite en loopback. La revocación remota se conoce en el siguiente contacto del agente; un dispositivo offline no recibe una confirmación inmediata.

Quedan como condiciones externas de entrega: validación en Android reciente y teléfonos propios, clave de distribución del responsable y piloto HTTPS con acceso/autorización. Las APKs debug y los certificados QA sirven para desarrollo. El análisis de dependencias se limita al runtime Maven resuelto y a la base de avisos registrada; no acredita la seguridad de todo un despliegue.

## Entregas anteriores conservadas

- **App académica local `academic/`:** I01–I12 y versión 0.1.0-i12/12. [Entrega](docs/ENTREGA_ACADEMICA.md), [avance](docs/AVANCE_ACADEMICO.md), [manual](docs/USO_ACADEMICO.md), [evidencia en dos emuladores](docs/evidencias/academico/DOS-EMULADORES-2026-10-23.md). Es una entrega independiente; no sustituye el flujo de dos apps/API. Sus fuentes y datos se conservan.
- **Prototipo Python/web `backend/api`:** referencia local con SQLite y [contrato 0.1](contracts/prototype-v0.1.yaml). [Guía](backend/api/README.md). Mantenerlo en loopback; el servicio definitivo es Ktor/PostgreSQL.

Para continuar el trabajo, leer [AGENTS.md](AGENTS.md).
