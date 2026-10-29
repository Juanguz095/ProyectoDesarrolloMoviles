# AuditLab

> **Revisión de Defender completada — 29/10/2026 (America/Lima).**
> El ZIP original 0.7 pasó un análisis en Windows Server 2022 con Defender activo,
> firmas actualizadas, marca de zona Internet y sin exclusiones que cubran el ZIP.
> Motor `1.1.26100.4`, firmas `1.463.16.0`: sin amenazas detectadas.
> La alerta reportada no se reprodujo; no hay confirmación de falso positivo del proveedor.
> Mantén Defender activo. Si vuelve a bloquearlo, déjalo bloqueado y no añadas exclusiones.
> [Informe de la revisión](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/REVISION_DEFENDER_0.7.md).


**Monitoreo visible y autorizado para dispositivos Android propios o administrados.**

AuditLab conecta un Agente instalado en el dispositivo supervisado con un Centro
de control para consultar su actividad, revisar eventos y gestionar accesos.
Una API central autentica las conexiones, recibe los eventos y registra las consultas.

## Dispositivos en Centro 0.7.3

Nombre editable, inscripción y contacto visibles; archivo/restauración local de equipos
anteriores. [Uso y límites](docs/DISPOSITIVOS_0.7.3.md).

## Descargas

**Agente: 0.7.1-preview · Centro: 0.7.3-preview · Servidor: 0.7 · Android 8.0 o superior.**

| Componente | Instalación | Descargar |
| --- | --- | --- |
| Agente | Dispositivo que comparte información | [APK Agente](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.7.1-preview.apk) |
| Centro de control | Dispositivo desde el que se consulta | [APK Centro](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.7.3-preview.apk) |
| Servidor Windows | PC que ejecuta la API y la base de datos | [Servidor Windows 0.7](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.7.0-preview.zip) |

**Centro 0.7.2:** mensajes de notificaciones WhatsApp dentro de Eventos → Todo o
WhatsApp, con título, texto expandible y hora. Son vistas previas temporales,
separadas del historial/CSV; no captura el texto enviado dentro del chat.
[Cómo usarlo y límites](docs/WHATSAPP_EVENTOS_0.7.2.md).

**Corrección del Agente 0.7.1:** instala los APK actuales como actualización. Compatible
con servidor 0.7 y datos/configuración existentes. El ZIP Windows 0.7 conserva los
APK anteriores; para este cambio usa los enlaces separados de Agente y Centro.
[Qué corrige y cómo activarlo](docs/CORRECCION_WHATSAPP_0.7.1.md).
Las APK publicadas usan firma debug; la firma de distribución sigue pendiente.

[Instalación paso a paso en Windows, sin USB](docs/INICIO_WINDOWS_0.4.md) ·
[Archivos y checksums](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/tree/descargas-auditlab) ·
[Entrega de las apps 0.7.1](docs/evidencias/ENTREGA-0.7.1-2026-10-29.json)
· [Entrega Centro 0.7.2](docs/evidencias/ENTREGA-CENTRO-0.7.2-2026-10-29.json)

## Cambios en 0.7

- Texto de SMS nuevos enviados/recibidos con autorización local adicional,
  inicialmente desmarcada, permiso Android e indicador visible. De/Para y hora;
  máximo 2 000 caracteres, resumen en tarjeta y texto en detalle. Usa retención y
  exportación CSV de eventos; no recupera mensajes anteriores ni RCS.
- WhatsApp: heartbeat también para respuestas observadas, cola de eventos separada
  de vistas previas, reintentos transitorios acotados y avisos específicos de error.
  Se mantiene la autorización por sesión y contenido entrante temporal. No ofrece
  todos los envíos dentro del chat ni historial completo; pendiente validación real.
- Historial sin batería/simulaciones, alias locales e información técnica plegable.

[Cómo actualizar, autorizar y comprobar](docs/ACTUALIZACION_0.7.md).

## Funciones

| Función | Alcance |
| --- | --- |
| Estado del dispositivo | Lecturas de batería y conexión, eventos propios de la app y último contacto con el servidor. |
| SMS y llamadas | Metadatos de tipo y fecha desde la activación, sujetos a permisos Android. Número y texto SMS opcionales con autorizaciones adicionales; sin audio. |
| WhatsApp | Vistas previas temporales de notificaciones nuevas y eventos de tipo/fecha de notificaciones recibidas o respuestas propias observadas, con autorización adicional por sesión. |
| Pantalla compartida | Vista remota de la pantalla iniciada por su propietario con permiso de Android; aproximadamente una actualización por segundo, sin audio ni grabación. |
| Historial y reportes | Filtros por dispositivo, categoría y fechas; búsqueda, detalle, paginación y exportación CSV de eventos. |
| Administración | Inscripción con código de un uso, roles administrador/auditor, revocación y registro de accesos. |

El estado de inscripción y la fecha del último contacto permiten revisar la
actividad recibida; una inscripción activa no garantiza que el dispositivo esté
conectado en ese momento.

## Cómo empezar

1. **Prepara el servidor.** Instala Java 21 y Docker Desktop y extrae el paquete
   Windows. Ejecuta `Iniciar-AuditLab.ps1` y crea la contraseña de `admin`.
2. **Conecta los equipos.** Publica la API mediante HTTPS. Para una PC y dos
   teléfonos sin USB, utiliza la [configuración privada con Tailscale](docs/PRUEBA_SIN_USB.md).
   Ambos teléfonos deben poder abrir la URL del servidor terminada en `/ready`.
3. **Inicia sesión en el Centro.** Introduce la URL HTTPS sin `/ready` ni `/v1`,
   el usuario `admin` y la contraseña creada en el servidor.
4. **Inscribe el Agente.** Genera un código en el Centro, acepta el consentimiento
   en el Agente e introduce la misma URL y el código. Habilita el indicador visible.
5. **Activa las funciones necesarias.** La captura real, las notificaciones de
   WhatsApp y la pantalla compartida tienen sus propias autorizaciones y permisos.
6. **Consulta y administra.** Abre el dispositivo desde el Centro para revisar
   eventos, acceder a las sesiones autorizadas o revocar la inscripción.

La API debe permanecer funcionando en una PC o servidor accesible. La conexión
puede atravesar redes distintas cuando ambos dispositivos alcanzan la misma URL
HTTPS.

## Componentes

| Componente | Tecnología | Responsabilidad |
| --- | --- | --- |
| Agente Android | Kotlin, Jetpack Compose, Room y WorkManager | Consentimiento, captura autorizada, cola de eventos y sincronización. |
| Centro Android | Kotlin y Jetpack Compose | Sesión de usuario, consulta, reportes y administración por rol. |
| API | Ktor, PostgreSQL y Flyway | Autenticación, validación, recepción, consultas, revocación y auditoría. |
| Acceso | HTTPS, JWT con refresh y Android Keystore | Transporte autenticado, sesiones y protección de credenciales. |

## Privacidad y control

- La captura requiere consentimiento y un indicador visible. El propietario puede
  pausar el monitoreo o retirar su consentimiento desde el Agente.
- WhatsApp y pantalla compartida requieren autorización local adicional por sesión.
  El Centro consulta las sesiones activadas por el propietario.
- WhatsApp comparte únicamente vistas previas nuevas entregadas por Android. No
  recupera historial de chats, archivos ni mensajes sin notificación o con texto oculto.
- El servidor conserva hasta 20 vistas previas por dispositivo durante un máximo
  de 5 minutos. Se eliminan al detener la sesión o tras 30 segundos sin comunicación del Agente.
  El texto no se guarda en Room, PostgreSQL, reportes CSV ni respaldos SQL.
- La pantalla compartida mantiene un cuadro temporal en memoria, que caduca a los
  5 segundos sin renovación. Android puede ocultar ventanas protegidas.
- La auditoría registra consultas y acciones administrativas. En las funciones de
  contenido registra metadatos de acceso y consentimiento, sin cuerpos de mensajes
  ni imágenes.
- Las credenciales Android se almacenan cifradas mediante Keystore. HTTPS valida
  certificado y hostname; HTTP se limita a las direcciones locales de depuración.
- La revocación local bloquea nuevas capturas y transmisiones. La remota se conoce
  en el siguiente contacto con el servidor; sin red no se recibe inmediatamente.

Los eventos, incluido el texto SMS autorizado, utilizan una cola persistente con
reintentos; el contenido de pantalla y vistas previas WhatsApp utiliza sesiones
temporales, sin cola en disco.
El ahorro de batería, retirar permisos o forzar la detención pueden interrumpir el
servicio. Las sesiones de contenido no se reactivan automáticamente tras reiniciar.

## Compilar desde el código

Requisitos: JDK 21, Android SDK 35 y Python 3. Usa las versiones de Gradle y
bibliotecas fijadas en el repositorio.

```bash
python3 scripts/gradle.py :apps:agent:assembleDebug :apps:control:assembleDebug :backend:ktor:installDist --no-daemon --max-workers=1
```

Artefactos generados:

- Agente: `apps/agent/build/outputs/apk/debug/agent-debug.apk`.
- Centro: `apps/control/build/outputs/apk/debug/control-debug.apk`.
- API: `backend/ktor/build/install/ktor/`.

Consulta la [guía de desarrollo](docs/desarrollo.md) para las herramientas y la
[guía de despliegue](docs/despliegue.md) para PostgreSQL, roles, migraciones,
HTTPS, respaldo y restauración.

## Estado de la versión

Centro 0.7.2: compilación, lint y auditoría estática aprobados; firma compatible
con 0.7.1. Sin ejecución funcional en dispositivos en esta revisión.
[Registro](docs/evidencias/WHATSAPP-EVENTOS-0.7.2-2026-10-29.json).

Apps 0.7.1: compilación, lint, auditoría estática y firma de actualización correctos.
No se ejecutaron suites automáticas ni recepción real en teléfonos en esta revisión;
[alcance y registro](docs/evidencias/WHATSAPP-0.7.1-2026-10-29.json).


La entrega 0.7 documenta compilación, lint, revisión estática y migración del servidor
de desarrollo. La recepción desde WhatsApp instalado y el
recorrido completo en teléfonos siguen pendientes; no se ejecutan suites
automáticas en esta revisión.

La validación registrada para **0.4.0** incluye **197 pruebas JVM/Robolectric/PostgreSQL**,
32 de herramientas, 49 de contrato y 23 respuestas verificadas contra el contrato
con la API en ejecución. El recorrido de notificaciones entre dos emuladores aprobó
consentimiento, permiso Android, envío, visualización y detención con entradas
sintéticas. La pantalla compartida cuenta con un recorrido simultáneo de captura
real entre dos emuladores.

Quedan por verificar la instalación Windows, Android reciente, teléfonos físicos
y la recepción de notificaciones desde WhatsApp instalado. La firma de distribución
y el despliegue operativo HTTPS también están pendientes. Los informes distinguen
las versiones y el alcance de cada comprobación.

- [Validación de notificaciones y APK 0.4](docs/evidencias/WHATSAPP-0.4.0-2026-10-29.md).
- [Validación funcional y pantalla compartida](docs/evidencias/VALIDACION-FUNCIONAL-2026-10-28.md).
- [Procedimientos de pruebas integradas](docs/PRUEBAS_INTEGRADAS.md).

## Contexto y presentación

- [Contexto del producto: problema, meta, objetivos y funciones](docs/CONTEXTO_PRODUCTO.md).
- [Arquitectura, flujos y componentes reales](docs/ARQUITECTURA.md).
- [Contenido para un PPT de 14 diapositivas](docs/PRESENTACION_PPT.md).
- [Diagrama de arquitectura para insertar en el PPT](docs/presentacion/arquitectura.png).

## Documentación

- [Instalación y conexión sin USB](docs/INICIO_WINDOWS_0.4.md).
- [Actualizar a 0.7 y consultar comunicaciones](docs/ACTUALIZACION_0.7.md).
- [Manual del producto](docs/MANUAL_PRODUCTO.md).
- [Captura de metadatos](docs/CAPTURA_REAL.md).
- [Notificaciones de WhatsApp](docs/WHATSAPP_NOTIFICACIONES.md).
- [Pantalla compartida](docs/COMPARTIR_PANTALLA.md).
- [Contrato de la API](contracts/openapi.yaml), [pantalla](contracts/screen-share.openapi.yaml) y [notificaciones](contracts/whatsapp-preview.openapi.yaml).
- [Despliegue y operación](docs/despliegue.md).
- [Registro de cambios y estado](docs/AVANCE.md).
