# AuditLab

**Monitoreo visible y autorizado para dispositivos Android propios o administrados.**

AuditLab conecta un **Agente** instalado en el dispositivo supervisado con un
**Centro de control** desde el que se consulta su actividad. Una **API Ktor/PostgreSQL**
autentica las conexiones, recibe los eventos y registra cada acceso. La persona del
dispositivo ve qué se registra, acepta antes de activar y puede pausar o retirar el
consentimiento en cualquier momento.

| Pieza | Tecnología | Rol |
| --- | --- | --- |
| Agente Android | Kotlin, Compose, Room, WorkManager | Consentimiento, captura autorizada, cola local y sincronización. |
| Centro Android | Kotlin, Compose | Sesión de usuario, consulta de eventos, reportes y administración por rol. |
| API | Ktor, PostgreSQL, Flyway | Autenticación, validación, recepción, consultas, revocación y auditoría. |

## Funciones

| Función | Alcance |
| --- | --- |
| Estado del dispositivo | Batería, conexión y último contacto con el servidor. |
| SMS y llamadas | Tipo y fecha desde la activación, con permiso Android. Número y texto SMS opcionales con autorizaciones adicionales; sin audio de llamadas. |
| WhatsApp | Vistas previas temporales de notificaciones nuevas y eventos de tipo/fecha, con autorización por sesión. Sin historial de chats. |
| Pantalla compartida | Vista remota iniciada por su propietario con permiso de Android; sin audio ni grabación. |
| Texto manual | Texto elegido y confirmado por el usuario en cada envío. |
| Historial y reportes | Filtros, búsqueda, detalle, paginación y exportación CSV. |
| Administración | Inscripción con código de un uso, roles administrador/auditor, revocación, auditoría y actualizaciones verificadas. |

Cada función sensible exige **acción local confirmada, indicador visible y
notificación permanente**. Nunca hay activación remota ni oculta.

## Empezar en 6 pasos

1. **Prepara el servidor:** Java 21 + Docker Desktop, extrae el paquete Windows y
   ejecuta `Iniciar-AuditLab.ps1`; crea la contraseña de `admin`.
2. **Conecta los equipos:** publica la API por HTTPS (Tailscale sirve para PC y dos
   teléfonos en redes distintas, sin abrir puertos). Los teléfonos deben abrir la
   URL terminada en `/ready`.
3. **Inicia sesión en el Centro** con la URL HTTPS (sin `/ready` ni `/v1`).
4. **Inscribe el Agente:** genera un código en el Centro, acepta el consentimiento
   en el Agente e introduce la misma URL y el código. Habilita el indicador visible.
5. **Activa solo lo necesario:** cada fuente tiene su propia autorización y permisos.
6. **Consulta y administra** desde el Centro; la retirada de consentimiento está
   siempre disponible en el Agente.

Guía completa: [Manual del producto](docs/MANUAL_PRODUCTO.md) ·
[Instalación sin USB](docs/PRUEBA_SIN_USB.md).

## Descargas

**Última versión publicada:** Agente 0.7.5-preview · Centro 0.7.4-preview ·
Servidor 0.7 · Android 8.0 o superior. El código fuente va por delante
(0.8.8-preview); las APK de 0.8 aún no se publican (firma de distribución y
análisis antimalware pendientes).

| Componente | Descargar |
| --- | --- |
| Agente | [auditlab-agent-0.7.5-preview.apk](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.7.5-preview.apk) |
| Centro | [auditlab-control-0.7.4-preview.apk](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.7.4-preview.apk) |
| Servidor Windows | [AuditLab-Servidor-Windows-0.7.0-preview.zip](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.7.0-preview.zip) |

[Archivos y checksums](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/tree/descargas-auditlab).
Las APK usan firma debug; la firma de distribución está pendiente.

## Privacidad y control

- **Consentimiento versionado** antes de cualquier captura; revocación local e
  inmediata, y remota en el siguiente contacto.
- **Indicador visible** obligatorio: sin él no hay captura.
- WhatsApp comparte solo vistas previas nuevas entregadas por Android; el texto vive
  en memoria temporal (máximo 20 por dispositivo, 5 minutos) y no va a la base de
  datos, CSV ni respaldos.
- La pantalla compartida usa un cuadro temporal de 5 segundos; sin grabación.
- Los eventos siguen una retención declarada (30 días por defecto; auditoría 90 días)
  con limpieza programada. Las credenciales se guardan cifradas (Keystore/Argon2id).
- La auditoría registra consultas, exportaciones y acciones administrativas sin
  contenido sensible.

## Estado de desarrollo

- **Versión actual del código: 0.8.8-preview** (Agente código 22, Centro código 21,
  API 0.8.1/V11). 72 pruebas JVM/Robolectric aprobadas en las apps; lint sin errores.
- **Próximo: plan de mejoras consentidas 0.9** — consentimiento por función,
  galería seleccionada, ubicación aproximada continua y video sin audio
  ([plan](docs/PLAN_MEJORAS_CONSENTIDAS.md) · [revisión de seguridad](docs/REVISION_SEGURIDAD_M0.md)).
- **Pendiente de validación en teléfonos físicos:** recepción real de SMS, visor de
  pantalla, texto manual y F1–F6 de administración. Nada de esto se declara
  validado sin evidencia nativa; el registro completo está en
  [AVANCE](docs/AVANCE.md).

## Compilar desde el código

Requisitos: JDK 21, Android SDK 35 y Python 3 (versiones fijadas en el repositorio).

```bash
python3 scripts/gradle.py :apps:agent:assembleDebug :apps:control:assembleDebug :backend:ktor:installDist --no-daemon --max-workers=1
```

- Agente: `apps/agent/build/outputs/apk/debug/agent-debug.apk`
- Centro: `apps/control/build/outputs/apk/debug/control-debug.apk`
- API: `backend/ktor/build/install/ktor/`

Guías: [desarrollo](docs/desarrollo.md) (herramientas y comprobaciones) ·
[despliegue](docs/despliegue.md) (PostgreSQL, migraciones, HTTPS, respaldo).

## Documentación

| Tema | Documento |
| --- | --- |
| Qué es y para qué sirve | [Contexto del producto](docs/CONTEXTO_PRODUCTO.md) |
| Estado real y límites | [Estado actual](docs/ESTADO_ACTUAL.md) |
| Arquitectura y flujos | [Arquitectura](docs/ARQUITECTURA.md) |
| Uso paso a paso | [Manual del producto](docs/MANUAL_PRODUCTO.md) |
| Planes y avance | [Plan maestro](docs/PLAN_MAESTRO.md) · [AVANCE](docs/AVANCE.md) · [Índice](docs/INDICE.md) |
| Contratos | [API principal](contracts/openapi.yaml) · [pantalla](contracts/screen-share.openapi.yaml) · [WhatsApp](contracts/whatsapp-preview.openapi.yaml) · [actualizaciones](contracts/app-updates.openapi.yaml) |

El [índice completo](docs/INDICE.md) reúne ampliaciones, operación, pruebas,
documentación académica e histórico.
