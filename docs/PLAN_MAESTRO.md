# AuditLab — Plan maestro de ejecución

Versión del plan base: 1.0 · Fecha: 17 de octubre de 2026.
Contexto y arquitectura actualizados al 29 de octubre: [producto](CONTEXTO_PRODUCTO.md),
[arquitectura](ARQUITECTURA.md) y [presentación](PRESENTACION_PPT.md). Las ampliaciones
fechadas conservan el historial; el alcance actual consolida las versiones posteriores.

**Centro 0.7.2 — 29 de octubre (America/Lima):** P6.2/P6.6/P6.4 integran vistas
previas temporales dentro de Resultados en Eventos → Todo y WhatsApp. Sin cambios
en captura/contrato/persistencia ni acceso a mensajes enviados dentro del chat.
[Alcance](WHATSAPP_EVENTOS_0.7.2.md); P7.6 de recepción real permanece pendiente.

**Ampliación 0.7 — 28 de octubre (America/Lima):** P5.3/P6.2 incluyen texto de
SMS nuevos enviados y recibidos, con autorización local adicional por sesión,
READ_SMS, indicador visible y retención/CSV de eventos. Máximo 2 000 caracteres;
sin importar historial previo, agenda, RCS ni emparejar respuestas por suposición.
WhatsApp mantiene contenido efímero y captura limitada a notificaciones.
[Guía y límites](ACTUALIZACION_0.7.md).

**Ampliación 0.6 — 28 de octubre (America/Lima):** P5.3/P6.2/P6.4 separan
lecturas de batería del historial principal, resumen eventos de comunicación y
ocultan identificadores técnicos. Números SMS/llamadas solo tras autorización local
adicional, con retención y CSV de eventos. WhatsApp temporal accesible en Eventos.
[Alcance y actualización](ACTUALIZACION_0.6.md).

**Ampliación 0.5 — 28 de octubre (America/Lima):** P3.8/P5.6/P6.6 incluyen
eventos persistentes de tipo/fecha de notificaciones WhatsApp recibidas y respuestas
propias observadas. Consentimiento v2; texto solo en sesión temporal. P6.4 corrige
recorte de tarjetas. No se asume que Android notifique todos los envíos dentro del
chat ni se confirma entrega. [Alcance y actualización](ACTUALIZACION_0.5.md).

**Ampliación del 29 de octubre (UTC):** vistas previas independientes de
notificaciones nuevas de WhatsApp/Business con autorización adicional por sesión y
permiso Android. [WHATSAPP_NOTIFICACIONES.md](WHATSAPP_NOTIFICACIONES.md) define
los límites. P3.8/P5.6/P6.6/P7.6 se registran aparte; no implican historial de chats
ni recepción real verificada por una entrada sintética de prueba.

**Ampliación del 28 de octubre:** compartir pantalla iniciado localmente, con permiso
MediaProjection por sesión, notificación persistente y parada accesible. Se rige por
[COMPARTIR_PANTALLA.md](COMPARTIR_PANTALLA.md). P3.7/P5.5/P6.5/P7.5 son tareas
adicionales, cuyo cierre exige ejecución nativa; no modifican evidencias anteriores.

**Ampliación vigente del 27 de octubre (America/Lima):** el usuario requiere eventos
reales de SMS/llamadas. P3.3, P5.3, P6.2 y P7/P8 se amplían según
[CAPTURA_REAL.md](CAPTURA_REAL.md): permisos opcionales, autorización adicional,
servicio visible y solo tipo/fecha, sin contenido ni números. Categorías `sms` y `call`,
eventos reales identificados con `isSimulated=false`. Las restricciones de permisos del
dispositivo y las evidencias nuevas se registran sin reutilizar el cierre histórico de 0.2.

Este documento guía la construcción del producto. Se utiliza junto con [AVANCE.md](AVANCE.md), donde se registran tareas, resultados y bloqueos. Las fases siguientes son trabajo previsto; una descripción o un archivo existente no demuestra su funcionamiento.

## 1. Meta y definición de entrega

Entregar dos aplicaciones Android instalables y una API HTTPS que permitan, en dispositivos propios o administrados, inscribir un agente con consentimiento explícito, registrar eventos permitidos, transmitirlos de forma autenticada y consultarlos desde un centro de control con roles y trazabilidad.

El proyecto se considera terminado cuando cumple simultáneamente estas condiciones:

1. Existen APKs identificables del agente y del centro de control, construidas desde una revisión registrada del repositorio.
2. La API Ktor y PostgreSQL se levantan con instrucciones reproducibles, migraciones y configuración externa al código.
3. El recorrido completo funciona en dos dispositivos Android propios; los emuladores se usan adicionalmente para automatizar y cubrir versiones.
4. Todos los criterios `CA-01` a `CA-15` cuentan con evidencia satisfactoria en la versión entregada.
5. No quedan defectos críticos o altos sin resolver. Las limitaciones menores están documentadas con su impacto.
6. Hay manuales de instalación, uso, consentimiento, revocación, retención, respaldo, restauración y operación.
7. El despliegue real, los certificados y las APKs firmadas se verifican cuando estén disponibles los accesos autorizados. Si falta alguno, la entrega se declara pendiente de ese requisito.

## 2. Punto de partida observado

| Elemento | Estado al crear este plan |
| --- | --- |
| `backend/api/server.py` | Código de un prototipo local Python/SQLite; funcionamiento no validado en esta sesión. |
| `backend/api/static/index.html` | Simula agente y centro de control en navegador; no es una app Android. |
| `contracts/openapi.yaml` | Borrador contractual que debe contrastarse con la implementación y completar sus errores y límites. |
| Apps Android / backend Ktor | No existen todavía en el checkout inspeccionado. |
| Pruebas y compilaciones | No hay evidencias registradas de ejecución. |
| Git | Rama local `work` sin commits y archivos sin seguimiento en la inspección inicial. Revalidar antes de operar. |
| Entorno | Se guardaron previamente instrucciones de arranque y nombres de variables; no equivale a ejecución, aplicación ni publicación. |

La ausencia de Gradle o del SDK Android debe volver a comprobarse en `P0.2`. No dar por actual un resultado de una sesión anterior. Conservar el prototipo como referencia mientras se construye el servicio definitivo; no invertir en convertirlo en servidor de producción.

## 3. Alcance y decisiones iniciales

### Producto incluido

- Agente Android en Kotlin/Compose: consentimiento, inscripción, monitoreo visible, Room, envío con WorkManager, retención, borrado y revocación.
- Centro de control Android en Kotlin/Compose: autenticación, roles admin/auditor, dispositivos, historial, detalle, filtros, CSV y auditoría.
- API Ktor, PostgreSQL, migraciones, validación, credenciales revocables de dispositivo y JWT de usuario con refresh.
- Un grupo administrado en la primera versión. Cada endpoint comprueba el rol y el recurso solicitado. No se presume soporte para varias organizaciones.
- Interfaz en español, timestamps UTC en almacenamiento/API y fechas locales identificadas en las pantallas.

### Límites

- Sin contenido de SMS, llamadas, chats, notificaciones de terceros, contactos, ubicación, cámara o micrófono.
- Sin accesibilidad para extraer datos, ocultamiento, arranque encubierto o mecanismos para impedir la revocación/desinstalación.
- Sin soporte iOS, analítica predictiva, múltiples organizaciones ni reportes avanzados en esta entrega.
- El pinning de certificados es opcional. HTTPS, validación de certificados y almacenamiento seguro de credenciales son obligatorios.

### Base técnica

Usar Kotlin/Compose, Room, WorkManager y Retrofit/OkHttp para Android; Ktor y PostgreSQL para la API. Fijar versiones compatibles en `P0.2` y `P2.1`, incluir Gradle Wrapper y registrar la combinación de JDK/Gradle/AGP/Kotlin/SDK comprobada. `minSdk` propuesto: 26; seleccionar `compileSdk` y `targetSdk` según herramientas disponibles y requisitos vigentes de entrega. Documentar cualquier ajuste.

No migrar datos reales del prototipo: usar fixtures ficticios. Mantener consistente el contrato al sustituir el backend. Las diferencias necesarias se registran antes de actualizar clientes y servidor.

## 4. Reglas operativas y de datos

### Consentimiento y revocación

- Estados del agente: `sin_consentimiento`, `inscribiendo`, `activo`, `pausado_por_visibilidad`, `revocacion_pendiente`, `revocado`.
- Antes de aceptar, no crear eventos ni programar trabajo de captura/envío. La aceptación registra versión de política, identificador y fecha; el servidor conserva la fecha de recepción.
- La inscripción comunica claramente datos, finalidad, plazo de retención y forma de detener el monitoreo. Un checkbox enviado a la API no sustituye la pantalla y la prueba de interacción en Android.
- Solo activar monitoreo cuando pueda mostrarse el indicador visible requerido. Si Android impide mostrarlo, mantener el agente pausado y explicar cómo habilitarlo. Comprobar permisos y restricciones por versión; no usar un servicio continuo sin un caso de uso permitido por Android.
- Revocación local: cambiar primero el estado a detenido, cancelar nuevos trabajos y vaciar la cola de eventos. Si no hay red, conservar únicamente una credencial protegida para reintentar la revocación; ningún worker de eventos puede usarla. Borrarla al confirmar la revocación en el servidor.
- Revocación remota: una vez confirmada por la API, ningún lote posterior se persiste. El agente se detiene al conocerla en su siguiente contacto; no prometer notificación inmediata a un dispositivo sin conexión.
- Serializar recepción y revocación respecto del estado del dispositivo. Probar solicitudes concurrentes y documentar cuál transacción quedó confirmada primero.
- Reinscribir un dispositivo exige nuevo código y nuevo consentimiento; las credenciales anteriores no se reactivan.

### Política inicial propuesta

Validar estos valores durante `P1.1`; los cambios se documentan y reflejan en las pruebas.

| Regla | Valor inicial y comportamiento |
| --- | --- |
| Eventos locales | 7 días y hasta 10000 pendientes; al alcanzar el límite se pausa la captura y se muestra el motivo. |
| Eventos del servidor | 30 días desde recepción; retención programada y comprobable sin depender de una consulta. |
| Auditoría | 90 días; eliminación solo por la tarea de retención autorizada. |
| Código de inscripción | Un uso, 10 minutos, generado aleatoriamente y almacenado como hash. |
| Lote | De 1 a 100 eventos, tamaño máximo definido en contrato; lote inválido rechazado sin inserción parcial. |
| Timestamps | RFC 3339 con zona; rechazar eventos anteriores al consentimiento o más de 5 minutos en el futuro. Conservar `receivedAt` del servidor. |
| Borrado al revocar | El agente elimina pendientes. Los eventos ya recibidos siguen la retención comunicada; la revocación no promete un borrado histórico automático. |

### Contrato mínimo del evento

`eventId`, `timestamp`, `category`, `direction`, `sourceDevice`, `consentContext` y `metadata` opcional. UUIDs y fechas se validan; los campos adicionales se rechazan. El dispositivo autenticado debe coincidir con `sourceDevice` y el contexto de consentimiento vigente.

Categorías: `app_lifecycle`, `sync`, `device_status`. Dirección: `inbound`, `outbound`, `occurred`. Metadatos: únicamente los enums y límites del contrato; no aceptar texto libre como una vía para introducir contenido de otras apps. Cualquier evento simulado se identifica como tal en el contrato final y en las pantallas.

La idempotencia usa `(deviceId, eventId)`: repetir el mismo contenido devuelve duplicado sin reinserción; reutilizar el ID con otro contenido devuelve conflicto. Un dispositivo no puede consultar ni modificar eventos ajenos.

### Matriz de acceso

| Acción | Admin | Auditor | Agente |
| --- | --- | --- | --- |
| Login/refresh/logout de usuario | Sí | Sí | No |
| Crear código de inscripción | Sí | No | No |
| Listar dispositivos, consultar eventos, exportar | Sí | Sí | No |
| Consultar auditoría | Sí | Sí | No |
| Revocar cualquier dispositivo del grupo | Sí | No | No |
| Enviar eventos propios | No | No | Sí, con consentimiento vigente |
| Revocar consentimiento propio | No | No | Sí |

Las identidades de usuario y de dispositivo son distintas. La auditoría registra actor estable, acción, filtros/recurso mínimos, resultado, hora UTC y correlación; no almacena tokens ni cuerpos completos. Las acciones auditables y la persistencia de su registro deben definirse transaccionalmente antes de devolver éxito.

## 5. Fases y tareas ejecutables

Todas las tareas comienzan pendientes. Su estado vivo se mantiene en [AVANCE.md](AVANCE.md).

### Fase 0 — Base reproducible

**Objetivo:** conocer lo que realmente funciona y preparar las herramientas. Entrada: checkout actual. Salida: prototipo revisado y capacidad de construir los componentes definitivos, o bloqueos externos identificados.

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P0.1 | Inventariar archivos, Git y configuración; ejecutar el recorrido del prototipo con datos/credenciales temporales en un directorio aislado. Registrar discrepancias código/contrato/documentación y defectos. | Evidencia de arranque y recorrido, resultados diferenciados y lista de fallos con reproducción; no usar la base existente para pruebas destructivas. |
| P0.2 | Comprobar/provisionar JDK, Gradle Wrapper, Android SDK y PostgreSQL. Crear matriz de versiones y guía reproducible. | Una aplicación Android mínima compila y una conexión PostgreSQL funciona; si falta una capacidad, registrar la operación exacta bloqueada. |
| P0.3 | Definir estructura, CI inicial y estrategia para conservar el prototipo. Revisar archivos ignorados y secretos. | Nuevos componentes tienen comandos documentados; CI ejecuta las comprobaciones disponibles y no declara éxito omitiendo las requeridas. |

### Fase 1 — Requisitos comprobables

**Objetivo:** convertir el producto en reglas que puedan verificarse. Depende de `P0.1`; puede avanzar mientras se provisionan herramientas.

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P1.1 | Documentar escenario de uso, datos exactos, política de retención/borrado y roles en `docs/requisitos.md`. | Cada campo tiene finalidad, origen, acceso y retención; se resuelven o registran las diferencias con la política inicial. |
| P1.2 | Diseñar pantallas y estados de consentimiento, visibilidad, falta de red y revocación. | Todos los estados y transiciones tienen condición de entrada/salida y mensaje al usuario, incluida revocación sin conexión. |
| P1.3 | Relacionar historias de usuario con `CA-01` a `CA-15` y escenarios negativos. | Ninguna función obligatoria carece de criterio, prueba prevista o componente responsable. |

### Fase 2 — Contratos, modelo y estructura definitiva

**Objetivo:** establecer interfaces y persistencia antes de conectar las apps. Depende de fase 1 y herramientas pertinentes de `P0.2`.

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P2.1 | Crear módulos `apps/agent`, `apps/control` y `backend/ktor`; compartir DTOs solo cuando sea útil. Registrar decisiones en `docs/decisiones/`. | Compilan los módulos base con versiones fijadas; el prototipo Python conserva su ruta y propósito. |
| P2.2 | Completar OpenAPI: login/refresh/logout, inscripción, lotes, filtros, detalle, paginación, CSV, revocación y auditoría. | Esquemas, límites, enums, códigos HTTP y ejemplos válidos/inválidos son consistentes. `204` no lleva cuerpo; contrato y servidor concuerdan en `Content-Type`. |
| P2.3 | Crear migraciones para usuarios/roles, sesiones, dispositivos, consentimientos, códigos, eventos y auditoría. | Migran desde BD vacía, soportan índices y restricciones de idempotencia; bootstrap de usuarios sin contraseñas en código. |

### Fase 3 — API definitiva

**Objetivo:** ofrecer el flujo seguro sobre PostgreSQL. Depende de fase 2. No transportar la autenticación artesanal del prototipo.

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P3.1 | Login con hash de contraseñas, JWT validado por biblioteca, refresh rotatorio y logout/revocación de sesión. Definir expiración, emisor y audiencia. | Rechaza tokens alterados, vencidos, de tipo incorrecto o sesión revocada; roles probados; límites contra intentos de login configurados. |
| P3.2 | Inscripción con código de un uso y consentimiento versionado; credencial de dispositivo revocable. | Consumo del código atómico, incluso con dos solicitudes simultáneas; credencial se devuelve una vez y se almacena protegida como hash. |
| P3.3 | Recepción transaccional de lotes, validación, idempotencia y control de estado/consentimiento. | Campos desconocidos y tipos JSON incorrectos producen errores controlados; identidad ajena, consentimiento inválido y lotes parciales se rechazan. |
| P3.4 | Consultas, filtros, detalle y CSV con autorización, orden estable y paginación real. | Sin pérdidas ni duplicados entre páginas en el escenario definido; filtros/exportación respetan permisos; límites visibles y errores sin trazas internas. |
| P3.5 | Revocación local/remota, auditoría y retención programada. | Una credencial revocada no escribe eventos; auditoría identifica acción/recurso/resultado; retención funciona sin tráfico de consultas. |
| P3.6 | Configuración, salud/disponibilidad y manejo de errores para despliegue HTTPS. | Reinicio conserva datos; secretos externos; no hay secretos en logs; endpoint de disponibilidad detecta BD inaccesible. |

### Fase 4 — Primer recorrido Android integrado

**Objetivo:** comprobar pronto la comunicación entre las dos apps y la API. Depende de `P3.1` a `P3.3` y consultas básicas de `P3.4`.

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P4.1 | Agente: consentimiento, inscripción, credencial protegida y botón para generar un evento simulado. | Sin aceptar no se inscribe ni genera eventos; el evento llega a la API con el consentimiento correcto. |
| P4.2 | Centro: login y listado mínimo de dispositivos/eventos con roles. | Una segunda app consulta el evento enviado por el agente y la consulta queda auditada. |
| P4.3 | Recorrido de revocación en ambas apps. | Revocación local impide nuevos eventos; revocación remota rechaza nuevos lotes y el agente refleja el estado al contactar. |

El cierre de esta fase produce el primer MVP Android integrado. Todavía requiere persistencia local, recuperación, funciones completas y validación de entrega.

### Fase 5 — Completar el agente

**Objetivo:** conservar correctamente estado y eventos ante fallos. Depende de fase 4.

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P5.1 | Room, migraciones, estados pendientes/enviados y límites de cola/retención. | Reiniciar proceso conserva consentimiento y pendientes válidos; retención elimina lo vencido y revocación borra la cola. |
| P5.2 | WorkManager, lotes, backoff y clasificación de errores recuperables/definitivos. | Recuperar red entrega pendientes una vez; `401`/revocación no provoca reintentos ilimitados; cada reintento reutiliza IDs. |
| P5.3 | Indicador visible y captura de metadatos permitidos según capacidades Android. | Denegar o retirar permisos de notificación produce el estado previsto y no deja monitoreo invisible. |
| P5.4 | Proteger credenciales con Keystore, exclusión de backups y revocación offline. | Captura/envío se detienen antes de intentar la revocación remota; reiniciar no reactiva una revocación pendiente. |

### Fase 6 — Completar el centro de control

**Objetivo:** permitir consulta y administración comprensibles. Depende de fase 4 y `P3.4`/`P3.5`; puede ejecutarse entre tareas independientes de fase 5.

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P6.1 | Sesión, expiración/refresh, logout y navegación por rol. | Reinicio y expiración tienen comportamiento definido; el auditor no obtiene acciones de administración por llamadas directas a la API. |
| P6.2 | Dispositivos, estado basado en última recepción, historial, detalle, búsqueda y filtros. | Búsqueda por ID/código permitido, filtros por dispositivo/categoría/fechas, paginación y estados vacío/error/cargando comprobados. |
| P6.3 | Exportación CSV mediante flujo de archivo/compartición Android y consulta de auditoría. | Reporte respeta filtros y límites comunicados, queda auditado y no expone tokens; auditoría identifica qué consulta/exportación se realizó. |
| P6.4 | Revocación con confirmación, errores y accesibilidad básica. | Cambio de estado confirmado por API; errores de red no se presentan como éxito; navegación, etiquetas y tamaños de texto utilizables. |

### Fase 7 — Validación integrada y correcciones

**Objetivo:** demostrar los criterios completos con las versiones que se entregarán. Depende de fases 3 a 6.

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P7.1 | Suite de aceptación, pruebas de contrato y matriz Android mínima/reciente compatible. | `CA-01` a `CA-15` tienen resultado y evidencia; pruebas de API usan PostgreSQL para transacciones/concurrencia. |
| P7.2 | Casos de token inválido, rol incorrecto, dispositivo ajeno, doble inscripción, revocación concurrente, red intermitente, proceso muerto y disco/cola limitada. | Sin aceptación indebida ni pérdida silenciosa; defectos reproducibles corregidos y pruebas afectadas repetidas. |
| P7.3 | Comprobar 10000 eventos ficticios y consultas paginadas en un entorno registrado. | Objetivo inicial: p95 de consulta paginada menor de 2 s con 5 consultas concurrentes; informar recursos y resultados, y resolver incumplimientos antes de fijar capacidad de piloto. |
| P7.4 | Auditoría de permisos, logs, almacenamiento local, errores, CSV y dependencias. | Sin defectos críticos/altos pendientes; revisión concreta de riesgos de fórmula CSV, exposición de datos y credenciales. |

### Ampliación — Pantalla compartida autorizada

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P3.7 | Relay efímero de cuadros autenticados, caducidad y auditoría. | Aislamiento, límites, borrado y revocación comprobados con PostgreSQL. |
| P5.5 | MediaProjection con autorización local por sesión y notificación con DETENER. | Captura real Android, cancelación sin captura y detención verificadas. |
| P6.5 | Visor autenticado del Centro. | Imagen decodificada, sesión/errores/estado vacío y cierre comprobados. |
| P7.5 | Pruebas de la ampliación de pantalla. | Recorrido nativo y de API con evidencia, límites y resultados diferenciados. |
| P3.8 | Sesiones efímeras de vistas previas de WhatsApp con autorización y auditoría sin texto. | Aislamiento, revocación, caducidad, límites y sesiones anteriores rechazadas en API/PostgreSQL. |
| P5.6 | Acceso Android a notificaciones y consentimiento adicional por sesión, servicio visible con DETENER. | Sin autorización no procesa; filtra paquetes, descarta historial/resúmenes y se detiene al retirar permiso. |
| P6.6 | Consulta autenticada de vistas previas desde detalle del dispositivo. | Actualización, estados vacíos/errores y borrado al detener o pasar al fondo; sin CSV. |
| P7.6 | Validación de notificaciones independientes. | Pruebas de parser/API y recorrido nativo con entradas sintéticas; diferenciar del ensayo pendiente con WhatsApp real. |

### Fase 8 — Entrega y operación

**Objetivo:** dejar una versión reproducible para el piloto autorizado. Depende de fase 7.

| ID | Trabajo y entregable | Criterio de cierre |
| --- | --- | --- |
| P8.1 | Procedimiento de despliegue, migraciones, HTTPS, salud, respaldo y restauración. | Restauración en BD vacía reproduce datos y permite consultar; certificados válidos desde clientes Android; rollback tiene procedimiento documentado. |
| P8.2 | Builds de entrega, firma externa al repositorio y configuración de URL por ambiente. | APKs instaladas en los dos dispositivos; revisión/build/checksums registrados; secretos y llaves excluidos de artefactos y logs. |
| P8.3 | Manual de usuario y operación, informe de evidencias y limitaciones. | Un tercero puede repetir instalación y recorrido usando los documentos; todos los criterios de entrega están trazados. |
| P8.4 | Ejecutar despliegue/publicación cuando exista autorización y acceso; realizar comprobación posterior. | URL y versión desplegada verificadas, piloto de dos dispositivos completo y responsable de operación identificado. Si falta acceso/autorización, registrar pendiente de entrega y preparar todo lo independiente. |

## 6. Matriz de aceptación obligatoria

Toda evidencia debe corresponder a una revisión y configuración identificadas. Las comprobaciones negativas verifican también que no se insertaron datos o produjeron acciones no autorizadas.

| ID | Escenario y resultado esperado | Evidencia principal |
| --- | --- | --- |
| CA-01 | Sin consentimiento o después de rechazarlo: cero eventos y cero trabajos de envío. | Prueba Android + inspección de Room/trabajos y ausencia de solicitudes de eventos. |
| CA-02 | Inscripción con consentimiento válido funciona; código ausente, vencido o reutilizado no inscribe. | Integración API + prueba simultánea del mismo código. |
| CA-03 | Sin autenticación válida, la API no acepta eventos ni devuelve consultas protegidas. | Respuestas `401` y verificación de BD. |
| CA-04 | Campos adicionales, tipos incorrectos, enums/fechas inválidos o lote fuera de límite se rechazan sin escritura parcial. | Casos de contrato y comprobación transaccional. |
| CA-05 | Dispositivo A no puede enviar como B; contexto de consentimiento ajeno o alterado no se acepta. | Prueba de aislamiento y BD. |
| CA-06 | Auditor consulta/exporta, pero no inscribe ni revoca dispositivos por UI o API. | Pruebas de autorización positivas/negativas. |
| CA-07 | Sin red, se informa el estado, se conservan pendientes válidos y se reenvían sin duplicarse al volver. | Recorrido Android + IDs comparados antes/después. |
| CA-08 | Revocar localmente detiene primero captura/envíos, incluso sin red; no se reactiva tras reiniciar. | Prueba offline, reinicio y revocación remota posterior. |
| CA-09 | Tras confirmar revocación remota, el servidor no persiste nuevos eventos con la credencial anterior. | Integración y carrera controlada recepción/revocación. |
| CA-10 | Sin indicador visible disponible, el agente respeta el estado de pausa definido por versión Android. | Denegación/retiro de permisos y prueba en versiones seleccionadas. |
| CA-11 | Retención local/servidor elimina lo vencido automáticamente y respeta lo vigente. | Reloj controlado/fixtures y ejecución del trabajo programado sin consultas. |
| CA-12 | Consultas, exportaciones y administración permiten identificar actor, acción, recurso/filtros, resultado y hora. | Comparación entre acciones realizadas y entradas de auditoría. |
| CA-13 | Login/refresh/logout y credenciales no filtran secretos; TLS inválido se rechaza. | Integración de sesiones, inspección de logs/artefactos y cliente Android con certificado inválido. |
| CA-14 | Filtros, detalle, paginación y CSV coinciden con los registros autorizados; duplicados no se reinsertan. | Datos ficticios conocidos, comparación de IDs y prueba de reintento/conflicto. |
| CA-15 | Construcción, instalación, reinicio y restauración son reproducibles con la versión entregada. | CI, APKs identificadas, recorrido en dos dispositivos y restauración registrada. |

## 7. Evidencias, estados y criterio de tarea terminada

Estados permitidos: `pendiente`, `en_curso`, `bloqueada`, `verificada`. Solo una tarea principal en curso por agente; no se requiere delegación para ejecutar este plan.

Para pasar una tarea a `verificada` deben existir:

1. Entregable presente y revisión de coherencia con OpenAPI, requisitos y documentación afectada.
2. Comprobaciones pertinentes ejecutadas, con fecha, comandos exactos, entorno/versión, resultado y código de salida cuando aplique.
3. Evidencia de la conducta esperada, incluidas condiciones negativas relevantes. Una compilación no sustituye una prueba de flujo.
4. Fallos y omisiones descritos; ningún requisito obligatorio omitido cuenta como aprobado.
5. Actualización de `docs/AVANCE.md` con tarea siguiente y enlaces a evidencia.

Guardar informes en `docs/evidencias/` con nombres `<ID>-<fecha>.md`. Registrar allí datos ficticios, pasos y resultados; adjuntar solo artefactos pequeños y saneados. No guardar bases con información personal, APKs grandes o secretos en Git. Si aún no hay commit, identificar explícitamente la revisión de trabajo sin inventar un SHA.

Formato mínimo de informe:

```text
Tarea y criterios relacionados:
Fecha / revisión de código / herramientas / dispositivo o entorno:
Precondiciones y datos ficticios:
Comandos o pasos ejecutados:
Resultado esperado y resultado observado:
Código de salida / conteos / archivos de resultado:
Fallos, omitidos y no ejecutados:
Conclusión y siguiente acción:
```

## 8. Secuencia de trabajo y bloqueos

Ruta principal: fase 0 → fase 1 → fase 2 → API → primer recorrido Android → completar ambas apps → validación → entrega. La fase 1 puede avanzar durante la instalación de herramientas; las tareas independientes de agente y centro pueden intercalarse una vez integrados.

No fijar fechas prometidas sin medir el ritmo inicial. Al cerrar fase 0, estimar cada tarea en jornadas, registrar disponibilidad y actualizar la previsión por fase con resultados reales. El avance se mide por tareas verificadas y criterios de aceptación, no por cantidad de archivos o líneas.

Ante un bloqueo externo, registrar: tarea, operación fallida, causa confirmada, intentos útiles, trabajo independiente disponible y acción mínima necesaria. Credenciales se suministran por configuración segura; nunca se solicitan valores en chat. Si el SDK, el dispositivo o el despliegue no están disponibles, continuar contratos, backend, pruebas y documentación que no dependan de ellos y conservar el estado pendiente de la capacidad no comprobada.

## 9. Instrucción de reanudación para el agente

Al recibir «continúa el proyecto», leer `AGENTS.md` y `docs/AVANCE.md`, comprobar el estado real y comenzar por la primera tarea disponible. Registrar una actualización breve, ejecutar el trabajo autorizado y sus comprobaciones, corregir fallos y actualizar evidencias antes de avanzar. No volver a diseñar el producto desde cero, sustituir el stack por comodidad ni declarar terminadas las apps porque funcione el simulador web.
