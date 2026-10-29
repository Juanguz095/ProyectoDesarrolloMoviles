# AuditLab — contexto del producto

Actualizado: **29 de octubre de 2026, America/Lima**.
Apps: **0.7.1-preview**, código Android 10. Servidor compatible: **0.7**, esquema V9.

## 1. Descripción breve

AuditLab es un sistema de monitoreo visible y autorizado para dispositivos Android
propios o administrados. Una app Agente registra información permitida del teléfono;
una API central recibe, valida y organiza esos datos; otra app, el Centro de control,
permite consultarlos y administrar dispositivos mediante roles.

El sistema separa la captura, el almacenamiento y la consulta. El propietario del
Agente acepta el monitoreo, ve un indicador persistente y puede detenerlo o revocarlo.

## 2. Problema que aborda

La actividad de un dispositivo y su estado suelen consultarse por separado y desde
el propio teléfono. Para un responsable autorizado resulta difícil centralizar esa
información y conocer quién la consultó, qué permiso la habilitó y cuándo se recibió.
AuditLab reúne eventos, estado y accesos en un mismo sistema con trazabilidad.

## 3. Meta y objetivos

**Meta:** disponer de dos apps Android conectadas a una API para supervisar actividad
autorizada, con consentimiento, control de acceso y registros verificables.

| Objetivo | Resultado observable |
| --- | --- |
| Inscribir dispositivos de forma controlada | Código de un uso, consentimiento e identidad del Agente. |
| Recoger datos autorizados | Estado del teléfono y eventos de comunicación sujetos a permisos. |
| Gestionar fallos de red | Eventos pendientes en una cola persistente y reintentos. |
| Facilitar la consulta | Resumen, historial, filtros, detalle y exportación CSV. |
| Proteger el acceso | Credencial por dispositivo, usuarios con roles y transporte HTTPS. |
| Mantener el control del propietario | Indicador visible, pausa, sesiones opcionales y revocación. |
| Registrar trazabilidad | Auditoría de consultas y acciones; contexto de consentimiento por evento. |

Son objetivos del producto; su presencia en esta tabla no acredita todas las pruebas
de aceptación. El estado comprobado se mantiene en [AVANCE](AVANCE.md).

## 4. Componentes y usuarios

| Elemento | Dónde funciona | Responsabilidad |
| --- | --- | --- |
| Agente, dispositivo A | Teléfono Android supervisado | Consentimiento, permisos, captura, cola local y envío. |
| Centro, dispositivo B | Teléfono Android del responsable | Login, consulta, reportes y administración autorizada. |
| API Ktor | PC o servidor accesible | Autenticación, reglas, recepción, consulta y auditoría. |
| PostgreSQL | Servidor; Docker en instalación Windows | Eventos, dispositivos, usuarios, sesiones, consentimientos y auditoría. |

**Roles:** el administrador consulta, exporta, crea códigos y revoca dispositivos.
El auditor consulta y exporta; la API restringe las operaciones administrativas.
El propietario del Agente controla los permisos y las sesiones de contenido.

## 5. Funciones implementadas y límites

| Función | Información disponible | Condición o límite |
| --- | --- | --- |
| Estado del teléfono | Batería, conexión y hora de la lectura | Captura visible activada y comunicación con la API. |
| SMS | Enviado/recibido, fecha; número y texto opcionales | Casillas adicionales, READ_SMS y eventos nuevos. Texto hasta 2 000 caracteres; no RCS. |
| Llamadas | Recibida, saliente, perdida o rechazada/bloqueada; número opcional | READ_CALL_LOG y autorización. Aparecen al escribirse el registro, normalmente al finalizar; sin audio. |
| WhatsApp/Business | Texto entrante de notificaciones nuevas; eventos de tipo/fecha | Acceso Android a notificaciones y sesión local autorizada. Sin historial completo de chats. |
| Respuestas WhatsApp observadas | Evento de tipo/fecha cuando Android expone una respuesta propia | Sin texto saliente; no detecta todos los envíos dentro del chat ni confirma entrega. |
| Pantalla compartida | Imagen periódica de la pantalla del Agente | MediaProjection por sesión; aproximadamente 1 actualización/s, sin audio ni grabación. |
| Historial | Filtros, detalle, páginas y búsqueda exacta por ID/código | No es búsqueda libre por cuerpo de mensaje. |
| Reportes | CSV de eventos coincidentes con filtros | Hasta 10 000; incluye texto SMS autorizado, excluye texto WhatsApp e imágenes. |
| Administración | Inscripción, revocación y auditoría | Según rol; inscripción activa no significa conexión en vivo. |

En la interfaz actual la batería se consulta en el resumen. Eventos abre en
comunicaciones reales, evitando llenar el historial con estado y simulaciones.
Los UUID y códigos están disponibles en Información técnica.

## 6. Datos persistentes y temporales

| Datos | Dónde se conservan | Retención |
| --- | --- | --- |
| Eventos y SMS con texto autorizado | Room/SQLite del Agente y PostgreSQL | Local hasta 7 días/10 000 registros; servidor 30 días por defecto. |
| Auditoría | PostgreSQL | 90 días por defecto; configurable. |
| Texto de vistas previas WhatsApp | Memoria temporal del Agente/servidor y vista del Centro | Servidor: últimas 20 vistas previas, máximo 5 minutos; sesión vence tras 30 s sin actividad. |
| Pantalla compartida | Memoria del Agente/servidor/visor | Solo cuadro temporal; servidor lo descarta tras 5 s sin renovación. |
| Credenciales Android | Almacenamiento cifrado mediante Keystore | Hasta cierre/revocación correspondiente; no se incluyen en reportes. |

El texto WhatsApp y los cuadros no van a Room, PostgreSQL ni CSV. Los **eventos de
tipo/fecha** de WhatsApp sí siguen la retención ordinaria. El texto SMS opcional
**sí persiste** y puede exportarse: su consentimiento explica esa diferencia.

## 7. Arquitectura resumida

```text
Agente Android ── HTTPS ── API Ktor ── PostgreSQL
                              │
Centro Android ── HTTPS ───────┘
```

Las dos apps utilizan la misma API; no se conectan directamente entre sí.
El servidor conserva además memoria temporal para WhatsApp y pantalla.
Pueden usar redes diferentes si alcanzan la misma URL HTTPS. En la instalación
Windows existente se usa Tailscale como acceso privado y HTTPS; la PC permanece
encendida, con API y PostgreSQL funcionando. USB solo es una opción de instalación/
depuración; no es necesario para la operación entre redes.

[Arquitectura técnica y diagramas](ARQUITECTURA.md).

## 8. Stack realmente utilizado

- Apps: Kotlin, Jetpack Compose/Material 3, coroutines y ViewModel.
- Agente: Room/SQLite, WorkManager y servicios visibles de Android.
- Captura: proveedores SMS/llamadas, NotificationListenerService y MediaProjection.
- Cliente de API propio sobre HttpURLConnection, con JSON; sin Retrofit implementado.
- Backend: Ktor/Netty, JDBC/HikariCP, PostgreSQL y Flyway.
- Seguridad: JWT/refresh para usuarios del Centro, token opaco para el Agente,
  hash Argon2id de contraseñas y Android Keystore.
- Operación: Java 21; Docker Desktop para PostgreSQL en Windows; Tailscale opcional
  según el despliegue HTTPS. No requiere Kali Linux.

## 9. Estado actual que debe reflejar una presentación

**Implementado:** los componentes y funciones descritos arriba existen en el código.
**Comprobado en 0.7.1:** compilación, lint, auditoría estática de APK, coincidencia de
firma para actualizar, y descargas verificadas. [Registro](evidencias/WHATSAPP-0.7.1-2026-10-29.json).

**Pendiente:** confirmar recepción real de WhatsApp en el teléfono del usuario y
recorridos completos de la revisión actual en dispositivos físicos. El usuario ha
reportado fallos de WhatsApp; la corrección 0.7.1 no demuestra por sí sola su resolución.
El registro citado de 0.7.1 no aporta resultados nuevos de suites automáticas. Las pruebas de 0.4 son
históricas y no deben presentarse como si validaran 0.7.1.

**Entrega:** APK con firma debug; firma de distribución/piloto operativo pendientes.
**Seguridad del ZIP Windows 0.7:** la alerta reportada no se reprodujo en el análisis
Defender registrado; no existe confirmación del proveedor de falso positivo. Un
bloqueo nuevo debe mantenerse. [Informe](REVISION_DEFENDER_0.7.md).

## 10. Material para presentar

- [Contenido listo para diapositivas](PRESENTACION_PPT.md).
- [Arquitectura](ARQUITECTURA.md) y [modelo de datos](base-datos.md).
- [Manual actual](MANUAL_PRODUCTO.md), [plan](PLAN_MAESTRO.md) y [avance](AVANCE.md).

No presentar lectura completa de WhatsApp, cámara remota, audio de llamadas,
servicio imposible de detener ni producto validado sin errores: esas afirmaciones
no corresponden al alcance/evidencia actuales.
