# Requisitos de AuditLab — Línea base 1

Fecha: 17 de octubre de 2026. Relación: P1.1 del [plan maestro](PLAN_MAESTRO.md).

## Escenario y alcance

Piloto académico sobre dos dispositivos Android propios o administrados con autorización: A ejecuta el agente y B el centro de control. Un único grupo administrado. La persona de A ve qué datos se registran, acepta antes de activar y puede revocar. Solo se admiten eventos simulados, acciones de AuditLab y metadatos de red/batería consultados mediante APIs Android permitidas.

La API y las aplicaciones definitivas usan Kotlin/Compose, Room, WorkManager, Retrofit/OkHttp, Ktor y PostgreSQL. El prototipo Python/web es una referencia local. Ninguna función del producto se considera validada por existir en ese prototipo.

No se identifica al dispositivo por IMEI, número telefónico, serial, contactos o cuentas del sistema. Se asigna UUID al inscribir. No se solicitan permisos de SMS, llamadas, ubicación, cámara, micrófono ni accesibilidad. No se leen notificaciones ajenas. No se requiere una tienda pública para el piloto, pero las restricciones de ejecución de Android sí se respetan.

## Datos y finalidad

Todos los campos de un evento se validan mediante lista permitida. Los campos `isSimulated` y `receivedAt` son requisitos del contrato definitivo (P2.2), todavía no del prototipo.

| Campo / registro | Origen y finalidad | Acceso | Retención |
| --- | --- | --- | --- |
| `eventId` | UUID generado una vez por el agente; reintentos e idempotencia. | Agente propio, admin/auditor. | Con el evento. |
| `timestamp` | Hora del evento con zona, convertida a UTC; orden y filtros. | Agente propio, admin/auditor. | Con el evento. |
| `receivedAt` | Hora UTC asignada por servidor al persistir; retención y comparación de relojes. | Admin/auditor. | Con el evento. |
| `category` | `app_lifecycle`, `sync`, `device_status`; clasificar eventos. | Agente propio, admin/auditor. | Con el evento. |
| `direction` | `inbound`, `outbound`, `occurred`; referido a operaciones propias de AuditLab. | Agente propio, admin/auditor. | Con el evento. |
| `sourceDevice` | UUID inscrito; validar identidad y filtrar. | Agente propio, admin/auditor. | Con el evento/dispositivo. |
| `consentContext` | `consentId`, `policyVersion`, `acceptedAt`; verificar consentimiento exacto vigente. | Agente propio, admin/auditor. | Con el evento y consentimiento. |
| `isSimulated` | Booleano explícito para identificar demostraciones y no confundirlas con datos observados. | Agente propio, admin/auditor. | Con el evento. |
| `metadata.networkState` | `online`, `offline`, `unknown`; estado permitido, sin SSID, IP ni historial de redes. | Agente propio, admin/auditor. | Con el evento. |
| `metadata.batteryPercent` | Entero 0–100 consultado por la app; estado de batería. | Agente propio, admin/auditor. | Con el evento. |
| `metadata.appEventCode` | `agent_started`, `sync_succeeded`, `sync_failed`, `consent_revoked`; acción propia clasificada. | Agente propio, admin/auditor. | Con el evento. |
| Credencial de dispositivo | Aleatoria; autenticar agente. Hash en servidor; Keystore en Android; nunca en reportes. | Solo agente y verificador del servidor. | Hasta revocación; borrar material local al confirmarla. |
| Usuario, rol y sesión | Identidad de cuenta del grupo; autorización y atribución de consultas. Contraseña como hash seguro. | Titular y servicio de autenticación; consultas limitadas. | Sesiones: hasta expiración/revocación y limpieza. |
| Auditoría | Actor estable, tipo, acción, recurso/filtros normalizados mínimos, resultado, correlación y hora. | Admin/auditor; escritura solo del servicio. | 90 días. |

Los registros de dispositivo/consentimiento revocados se conservan mientras existan eventos o auditoría que los referencien. Tras el mayor plazo aplicable (90 días desde revocación/última referencia), la limpieza elimina o anonimiza el registro de control según las relaciones, dejando la credencial invalidada. El historial ya recibido no se borra por el mero acto de revocar; esta regla debe aparecer en el consentimiento.

## Requisitos funcionales

| ID | Regla obligatoria | Criterios de aceptación |
| --- | --- | --- |
| RF-01 | Consentimiento versionado previo a captura/envío y revocación visible en A. | CA-01, CA-08 |
| RF-02 | Admin emite código de inscripción de un uso por 10 minutos; consumo atómico. | CA-02, CA-06 |
| RF-03 | Cada agente solo envía bajo su identidad y consentimiento exactos; no consulta historiales. | CA-03, CA-05 |
| RF-04 | Generación manual simulada y eventos propios permitidos; indicar fuente simulada en UI y datos. | CA-01, CA-04, CA-10 |
| RF-05 | Room guarda cola durable con IDs estables y estado; WorkManager reintenta errores recuperables. | CA-07, CA-08, CA-14 |
| RF-06 | Solo monitorear con indicador visible habilitado; pausar si deja de estar disponible. | CA-10 |
| RF-07 | API autentica, valida tipos/campos/límites y procesa lotes transaccionales. | CA-03, CA-04, CA-05 |
| RF-08 | Admin/auditor consultan dispositivos, filtros, búsqueda por ID/código permitido y detalle. | CA-06, CA-12, CA-14 |
| RF-09 | Admin/auditor exportan CSV con los filtros solicitados y un límite explícito; sin truncado silencioso. | CA-06, CA-12, CA-14 |
| RF-10 | Solo admin revoca otros dispositivos. Agente puede revocar su propia credencial. | CA-06, CA-08, CA-09 |
| RF-11 | Login, refresh rotatorio, logout y roles comprobados por el servidor. | CA-03, CA-06, CA-13 |
| RF-12 | Auditoría de consultas, exportación, códigos y revocaciones con resultado; API sin edición/borrado manual de auditoría. | CA-12 |
| RF-13 | Limpieza programada local/servidor, independiente de consultas, con evidencia de resultados. | CA-11 |
| RF-14 | Configuración, migraciones, respaldo/restauración y construcción/instalación reproducibles. | CA-15 |

## Reglas y límites adoptados

- Cola local: 7 días desde encolado y máximo 10000 pendientes. Al llegar al máximo se pausa captura y se informa; no sobrescribir silenciosamente pendientes.
- Servidor: eventos durante 30 días desde `receivedAt`; limpieza al menos diaria. Cola local limpia al arrancar y al ejecutar trabajos; no prometer ejecución mientras Android mantiene la aplicación forzada a detenerse.
- Auditoría: 90 días con limpieza diaria. Exportar genera una copia externa que el usuario debe guardar y eliminar conforme al plazo comunicado; la app no promete borrar copias compartidas fuera de su control.
- Lote: 1–100 eventos y máximo 256 KiB UTF-8. Rechazar campos desconocidos, tipos incorrectos, timestamps sin zona o previos al consentimiento, y eventos más de 5 minutos futuros.
- Idempotencia: `(deviceId,eventId)`. Igual contenido normalizado devuelve duplicado; contenido distinto devuelve `409` y el lote no se inserta parcialmente.
- Historial: `from` inclusivo, `to` exclusivo; validar `from < to`. Paginación estable por `(timestamp,eventId,deviceId)` con cursor opaco y límites 1–100. Registrar filtros mínimos, no texto libre sensible.
- CSV: hasta 10000 filas; si el conjunto supera el límite, pedir restringir filtros sin devolver éxito con un archivo incompleto. Neutralizar fórmulas al producir celdas potencialmente controladas por usuario.
- Access token de usuario: 15 minutos por defecto; refresh de 7 días, rotatorio, almacenado como hash y ligado a una sesión revocable. Comprobar emisor, audiencia, algoritmo permitido y estado de sesión; logout invalida la sesión. Configuración, límites compartidos y comportamiento implementado de P3.1 en [autenticación](autenticacion.md).
- Una credencial de dispositivo solo sirve para lotes propios y revocación propia. Invalidarla impide cualquier nuevo lote; nueva inscripción crea identidad/consentimiento nuevos.
- `lastSeenAt` significa última comunicación autenticada aceptada; tras 30 minutos sin contacto mostrar «sin contacto reciente», sin afirmar que el equipo está desconectado.
- WorkManager no ofrece entrega inmediata ni una cadencia exacta. La demostración manual puede solicitar un envío puntual; no presentar sincronización periódica como un canal en tiempo real.

## Requisitos no funcionales

| ID | Meta verificable |
| --- | --- |
| RNF-01 | HTTPS y validación de certificados en piloto; cliente rechaza certificado inválido. Backend de desarrollo restringido a loopback. |
| RNF-02 | Secretos externos al código/logs/APKs; tokens locales protegidos con Keystore y excluidos del backup. |
| RNF-03 | Datos inválidos dan errores de contrato sin stack traces, contraseñas o SQL; UI distingue vacío, cargando, fallo y éxito. |
| RNF-04 | Con 10000 eventos y 5 consultas concurrentes, p95 de página menor de 2 s en los recursos registrados en P7.3. |
| RNF-05 | Apps compilables para minSdk 26, compile/target 35 iniciales; verificar versión mínima y reciente compatible, y requisitos vigentes antes de entregar. |
| RNF-06 | Todas las acciones principales tienen etiquetas accesibles, estados legibles y soporte de texto ampliado sin ocultar revocación. |
| RNF-07 | Backups recuperables sin compartir contraseñas ni ampliar privilegios; restauración ensayada en BD aislada. |

## Cierre de P1.1

Esta línea base adopta los valores del plan y define las diferencias respecto al prototipo. El [contrato 0.2](contrato.md) formaliza estas interfaces; la implementación de negocio sigue pendiente de fase 3. La trazabilidad completa está en [historias.md](historias.md), y las transiciones de consentimiento en [estados.md](estados.md).
