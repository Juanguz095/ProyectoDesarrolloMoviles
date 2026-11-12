# Histórico consolidado — especificaciones y guías 0.4–0.7

11 de noviembre de 2026. Documento que conserva el contenido rescatable de las
especificaciones y notas históricas retiradas al consolidar la documentación.
La documentación vigente está en [INDICE](INDICE.md); el registro de ejecución
completo está en [AVANCE](AVANCE.md).

## Requisitos del piloto (requisitos.md)

> Origen: `docs/requisitos.md` — 17 de octubre de 2026 (Línea base 1, P1.1), con
> ampliación 0.3 solicitada el 27 de octubre de 2026 (alcance ampliado en `CAPTURA_REAL.md`).

Piloto académico con dos dispositivos Android propios o administrados con autorización:
A ejecuta el agente y B el centro de control, un único grupo administrado. La persona de A
ve qué datos se registran, acepta antes de activar y puede revocar. Solo se admiten eventos
simulados, acciones de AuditLab y metadatos de red/batería por APIs Android permitidas.
Producto definitivo: Kotlin/Compose, Room, WorkManager, Retrofit/OkHttp, Ktor y PostgreSQL;
el prototipo Python/web es solo referencia local y no valida funciones. Sin identificación
por IMEI, número, serial, contactos ni cuentas: UUID al inscribir. Sin permisos de SMS,
llamadas, ubicación, cámara, micrófono ni accesibilidad en esta línea base. Todo campo de
evento se valida por lista permitida; `isSimulated` y `receivedAt` son requisitos del
contrato definitivo (P2.2).

### Datos y finalidad

| Campo/registro | Origen y finalidad | Acceso | Retención |
| --- | --- | --- | --- |
| `eventId` | UUID generado una vez por agente; reintentos e idempotencia. | Agente propio, admin/auditor. | Con el evento. |
| `timestamp` | Hora del evento con zona, convertida a UTC; orden y filtros. | Agente propio, admin/auditor. | Con el evento. |
| `receivedAt` | Hora UTC del servidor al persistir; retención y comparación de relojes. | Admin/auditor. | Con el evento. |
| `category` | `app_lifecycle`, `sync`, `device_status`; clasificar eventos. | Agente propio, admin/auditor. | Con el evento. |
| `direction` | `inbound`, `outbound`, `occurred`; operaciones propias de AuditLab. | Agente propio, admin/auditor. | Con el evento. |
| `sourceDevice` | UUID inscrito; validar identidad y filtrar. | Agente propio, admin/auditor. | Con evento/dispositivo. |
| `consentContext` | `consentId`, `policyVersion`, `acceptedAt`; consentimiento vigente exacto. | Agente propio, admin/auditor. | Con evento y consentimiento. |
| `isSimulated` | Booleano que distingue demostraciones de datos observados. | Agente propio, admin/auditor. | Con el evento. |
| `metadata.networkState` | `online`, `offline`, `unknown`; sin SSID, IP ni historial de redes. | Agente propio, admin/auditor. | Con el evento. |
| `metadata.batteryPercent` | Entero 0–100; estado de batería. | Agente propio, admin/auditor. | Con el evento. |
| `metadata.appEventCode` | `agent_started`, `sync_succeeded`, `sync_failed`, `consent_revoked`. | Agente propio, admin/auditor. | Con el evento. |
| Credencial de dispositivo | Aleatoria; hash en servidor, Keystore en Android; nunca en reportes. | Agente y verificador del servidor. | Hasta revocación; borrar material local al confirmarla. |
| Usuario, rol y sesión | Cuentas del grupo; autorización y atribución; contraseña como hash seguro. | Titular y servicio de autenticación. | Sesiones: hasta expiración/revocación y limpieza. |
| Auditoría | Actor, tipo, acción, recurso/filtros mínimos, resultado, correlación y hora. | Admin/auditor; escritura solo del servicio. | 90 días. |

Los registros de dispositivo/consentimiento revocados se conservan mientras existan eventos
o auditoría que los referencien; tras el mayor plazo (90 días desde revocación/última
referencia) la limpieza elimina o anonimiza el registro de control, dejando la credencial
invalidada. El historial recibido no se borra por el mero acto de revocar; esta regla debe
aparecer en el consentimiento.

### Requisitos funcionales

| ID | Regla obligatoria | Criterios |
| --- | --- | --- |
| RF-01 | Consentimiento versionado previo a captura/envío y revocación visible en A. | CA-01, CA-08 |
| RF-02 | Admin emite código de inscripción de un uso por 10 minutos; consumo atómico. | CA-02, CA-06 |
| RF-03 | Cada agente envía solo bajo su identidad y consentimiento exactos; no consulta historiales. | CA-03, CA-05 |
| RF-04 | Generación manual simulada y eventos propios; fuente simulada indicada en UI y datos. | CA-01, CA-04, CA-10 |
| RF-05 | Room guarda cola durable con IDs estables y estado; WorkManager reintenta errores recuperables. | CA-07, CA-08, CA-14 |
| RF-06 | Solo monitorear con indicador visible habilitado; pausar si deja de estar disponible. | CA-10 |
| RF-07 | API autentica, valida tipos/campos/límites y procesa lotes transaccionales. | CA-03, CA-04, CA-05 |
| RF-08 | Admin/auditor consultan dispositivos, filtros, búsqueda por ID/código permitido y detalle. | CA-06, CA-12, CA-14 |
| RF-09 | Exportan CSV con los filtros solicitados y un límite explícito; sin truncado silencioso. | CA-06, CA-12, CA-14 |
| RF-10 | Solo admin revoca otros dispositivos; el agente puede revocar su propia credencial. | CA-06, CA-08, CA-09 |
| RF-11 | Login, refresh rotatorio, logout y roles comprobados por el servidor. | CA-03, CA-06, CA-13 |
| RF-12 | Auditoría de consultas, exportación, códigos y revocaciones con resultado; sin edición/borrado manual. | CA-12 |
| RF-13 | Limpieza programada local/servidor, independiente de consultas, con evidencia de resultados. | CA-11 |
| RF-14 | Configuración, migraciones, respaldo/restauración y construcción/instalación reproducibles. | CA-15 |

### Requisitos no funcionales

| ID | Meta verificable |
| --- | --- |
| RNF-01 | HTTPS y validación de certificados en piloto; el cliente rechaza certificado inválido. Backend de desarrollo restringido a loopback. |
| RNF-02 | Secretos fuera de código/logs/APKs; tokens locales con Keystore y excluidos del backup. |
| RNF-03 | Datos inválidos dan errores de contrato sin stack traces, contraseñas ni SQL; UI distingue vacío, cargando, fallo y éxito. |
| RNF-04 | Con 10000 eventos y 5 consultas concurrentes, p95 de página < 2 s en los recursos de P7.3. |
| RNF-05 | minSdk 26, compile/target 35 iniciales; verificar versión mínima y reciente compatible antes de entregar. |
| RNF-06 | Etiquetas accesibles, estados legibles y texto ampliado sin ocultar la revocación. |
| RNF-07 | Backups recuperables sin compartir contraseñas ni ampliar privilegios; restauración ensayada en BD aislada. |

### Reglas y límites adoptados

- Cola local: 7 días desde encolado y máximo 10000 pendientes; al llegar al máximo se pausa la captura y se informa, sin sobrescribir pendientes.
- Servidor: eventos durante 30 días desde `receivedAt`, limpieza al menos diaria; cola local limpia al arrancar y en trabajos (sin prometer ejecución con la app forzada a detenerse).
- Auditoría: 90 días con limpieza diaria; exportar genera copia externa que el usuario guarda/elimina según el plazo comunicado.
- Lote: 1–100 eventos y máximo 256 KiB UTF-8. Rechazar campos desconocidos, tipos incorrectos, timestamps sin zona o previos al consentimiento, y eventos más de 5 minutos futuros.
- Idempotencia `(deviceId,eventId)`: mismo contenido normalizado devuelve duplicado; contenido distinto devuelve `409` y el lote no se inserta parcialmente.
- Historial: `from` inclusivo, `to` exclusivo (`from < to`); paginación estable por `(timestamp,eventId,deviceId)` con cursor opaco y límites 1–100; filtros mínimos registrados.
- CSV: hasta 10000 filas; si se supera, pedir restringir filtros (nunca éxito con archivo incompleto) y neutralizar fórmulas.
- Tokens de usuario: access 15 minutos; refresh 7 días, rotatorio, como hash y ligado a sesión revocable; logout invalida la sesión.
- Credencial de dispositivo solo para lotes propios y revocación propia; invalidarla impide nuevos lotes; nueva inscripción crea identidad/consentimiento nuevos.
- `lastSeenAt`: última comunicación autenticada aceptada; a los 30 minutos sin contacto mostrar «sin contacto reciente», sin afirmar desconexión.
- WorkManager no garantiza entrega inmediata ni cadencia exacta; no presentar la sincronización periódica como canal en tiempo real.

## Estados del Agente (estados.md)

> Origen: `docs/estados.md` — sin fecha explícita; especificación P1.2 de la línea base 1 (octubre de 2026).

| Estado | Pantalla/indicador y acciones | Captura/envío | Salidas |
| --- | --- | --- | --- |
| `sin_consentimiento` | Instalación inicial o rechazo: finalidad, datos, retención, versión de política, casilla sin preselección, aceptar/rechazar. | Ninguno; no programar workers. | Aceptar → `inscribiendo`; rechazar mantiene estado. |
| `inscribiendo` | Progreso, código, errores y cancelar; no mostrar «activo» antes de respuesta válida. | Ninguno. | `201` + visibilidad disponible → `activo`; fallo/cancelación → sin activación; token sin visibilidad → pausa. |
| `activo` | Identificador, datos permitidos, último envío, pendientes, evento simulado y revocar; notificación visible. | Solo lista permitida; cola y envíos autenticados. | Revocar → detención inmediata; sin indicador → pausa; rechazo definitivo → `revocado`; capacidad agotada → pausa. |
| `pausado_por_visibilidad` | «Monitoreo pausado: habilita el indicador visible para continuar», ajustes y revocar. | Sin captura ni envío; cola vigente conservada. | Restaurar visibilidad con consentimiento → `activo`; revocar → pendiente/revocado. |
| `pausado_por_capacidad` | «Captura pausada: cola llena» (10000), contador, estado de red y revocar. | Sin captura nueva; enviar pendientes solo con consentimiento/visibilidad. | Liberar capacidad → `activo`; revocar → pendiente/revocado. |
| `revocacion_pendiente` | «Monitoreo detenido; revocación pendiente de confirmar»; sin botones de captura/sync. | Cancelar workers de eventos y borrar cola antes de red; solo worker separado de revocación. | Confirmación o credencial inválida → `revocado`; sin red mantiene el estado tras reinicio. |
| `revocado` | «Monitoreo detenido»; credencial eliminada; opción de nueva inscripción. | Ninguno; cola eliminada. | Nueva inscripción exige política aceptada y código nuevos. |

La falta de red es una condición transversal: no retira el consentimiento ni borra
pendientes. La revocación prevalece sobre cualquier respuesta tardía de envío o inscripción.

### Secuencia obligatoria de revocación local

1. Persistir el estado detenido/revocación pendiente antes de iniciar solicitudes.
2. Impedir generación y nuevos envíos; cancelar WorkManager de eventos, borrar pendientes y retirar el indicador de monitoreo activo.
3. Mantener la credencial únicamente en el almacén de la tarea de revocación, nunca accesible para un worker de eventos.
4. Solicitar revocación propia. En timeout/sin red, reintentar solo esa operación con backoff.
5. Al confirmar o recibir credencial ya inválida, eliminarla y persistir `revocado`.

Una petición de eventos ya en vuelo puede haberse confirmado antes de la revocación del
servidor: la UI no promete borrar ese historial; la API serializa ambas operaciones y
rechaza cualquier lote que se confirme después de la revocación.

Red y errores (resumen): sin red/timeout/`5xx` conservar lote con IDs originales y reintentar
con backoff; `400` marca fallo definitivo del lote sin reintentos infinitos; `409` distingue
consentimiento/revocación de idempotencia (sin regenerar IDs silenciosamente); `401` detiene
envíos sin renovar el token de agente con una sesión de usuario; fallo de inscripción permite
nuevo intento con motivo seguro; respuesta tardía tras revocar no reactiva nada. En el centro
(B) toda pantalla distingue carga/datos/vacío/error; solo admin revoca (sin doble envío) y
ninguna acción se marca confirmada sin respuesta real de la API.

## Historias y criterios (historias.md)

> Origen: `docs/historias.md` — sin fecha explícita; especificación P1.3 de la línea base 1 (octubre de 2026). Define la verificación futura; no marca pruebas como aprobadas.

| HU | Necesidad y resultado | Criterios | Caso negativo obligatorio |
| --- | --- | --- | --- |
| HU-01 | La persona de A conoce los datos y acepta o rechaza antes de iniciar. | CA-01, CA-10 | Rechazar/no marcar la casilla y denegar indicador: cero captura/trabajos. |
| HU-02 | El admin genera un código temporal para un único agente autorizado. | CA-02, CA-06 | Código vencido/reutilizado, solicitudes concurrentes y auditor intentándolo. |
| HU-03 | El agente envía solo eventos propios bajo su credencial y consentimiento. | CA-03, CA-04, CA-05 | Sin token, token de usuario, dispositivo ajeno, contexto alterado, campos extra/tipos incorrectos. |
| HU-04 | La persona de A conserva pendientes ante un corte de red sin duplicarlos. | CA-07, CA-14 | Timeout tras commit, reinicio del proceso, mismo ID con distinto contenido y cola llena. |
| HU-05 | La persona de A detiene el monitoreo de inmediato y revoca incluso sin red. | CA-08, CA-09 | Red bloqueada, reinicio durante revocación y respuesta de envío tardía. |
| HU-06 | El admin revoca remotamente y el servidor deja de aceptar la credencial. | CA-06, CA-09 | Auditor por API directa, envío concurrente con revocación y dispositivo offline. |
| HU-07 | Admin/auditor inician/cierran sesión y acceden según su rol. | CA-03, CA-06, CA-13 | JWT alterado/vencido/algoritmo incorrecto, refresh reutilizado y token tras logout. |
| HU-08 | El auditor encuentra un evento y consulta su detalle sin contenido prohibido. | CA-04, CA-12, CA-14 | Rango inválido, páginas límite, búsqueda inexistente, recurso no autorizado y vacío/red caída. |
| HU-09 | El auditor exporta el conjunto filtrado y queda constancia. | CA-06, CA-12, CA-14 | Límite excedido, descarga interrumpida, valores CSV peligrosos y token inválido. |
| HU-10 | El responsable identifica quién consultó, exportó o revocó y el resultado. | CA-12 | Intento de editar auditoría y fallo de escritura del registro antes de devolver éxito. |
| HU-11 | Los datos dejan de conservarse cuando vence el plazo comunicado. | CA-11 | Servidor sin consultas, datos exactamente en el límite y reloj de dispositivo alterado. |
| HU-12 | El responsable instala una versión reproducible y recupera datos ante un fallo. | CA-13, CA-15 | Certificado inválido, BD inaccesible, restauración aislada y reinicio de servicios/apps. |

Cobertura por criterio: CA-01 HU-01 · CA-02 HU-02 · CA-03 HU-03, HU-07 · CA-04 HU-03, HU-08 ·
CA-05 HU-03 · CA-06 HU-02, HU-06, HU-07, HU-09 · CA-07 HU-04 · CA-08 HU-05 · CA-09 HU-05, HU-06 ·
CA-10 HU-01 · CA-11 HU-11 · CA-12 HU-08, HU-09, HU-10 · CA-13 HU-07, HU-12 ·
CA-14 HU-03, HU-04, HU-08, HU-09 · CA-15 HU-12. Cada historia se verifica al implementar
sus tareas y debe enlazar un informe en `docs/evidencias/`.

## Build académico I01 (PLAN_I01.md)

> Origen: `docs/PLAN_I01.md` — 22 de octubre de 2026 (America/Lima). I01 ejecutada y
> comprobada por CLI (build/lint); Sync de IDE y apertura en Android quedaron pendientes.

Configuración obligatoria del build académico en `academic/` (módulos `:app` y `:domain`):

| Componente | Valor que debe usarse |
| --- | --- |
| Lenguaje | Kotlin |
| AGP | 9.1.1 |
| Gradle Wrapper | 9.3.1 (con SHA-256 oficial de la distribución) |
| JDK | 17 (el JDK 21 existente no lo sustituye) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| `app` | Kotlin integrado en AGP; sin añadir `org.jetbrains.kotlin.android` |
| `domain` | `kotlin.jvm` 2.2.10, objetivo JDK 17; sin dependencias de Android |

- La compatibilidad de Kotlin JVM 2.2.10 con Gradle 9.3.1 es pendiente de comprobar; ante
  fallo no se cambian versiones automáticamente: presentar error, alternativa y diff y pedir aprobación.
- Alcance I01: base vacía con Activity Kotlin mínima y manifiesto sin permisos; sin modelos
  de eventos, AuditService, SQLite, aceptación, simulación ni CSV.
- Comprobaciones: `./gradlew --version`, `help`, `:domain:build :app:assembleDebug` (y
  `:app:lintDebug`) con JDK 17 y SDK 36; registrar códigos de salida y versiones efectivas.
  Sin casos de test, registrar 0 pruebas/NO-SOURCE sin introducir lógica de I02. APK debug
  con hash local, sin subir binarios a Git.
- **Regla de parada:** tras mostrar diff, comprobaciones y evidencia, detenerse antes de I02.
  Un Build satisfactorio no autoriza I02: se exige aprobación explícita del usuario.

## Especificación visual 0.3.1 (REDISENO_ANDROID.md)

> Origen: `docs/REDISENO_ANDROID.md` — 28 de octubre de 2026 (America/Lima), 0.3.1-rc1
> (versionCode 4, Android 8.0/API26 o superior, target 35).

Referencia visual (según los cinco mockups del usuario):

- Fondo beige `#DAD7C0`, panel claro `#F0EDDB`, texto `#10110C`.
- Tipografía monoespaciada, encabezados en mayúsculas, divisores finos y esquinas rectas.
- Botones negros y botones secundarios con borde; sin gradientes ni tarjetas redondeadas.
- Espaciado lateral de 26 dp, contenido desplazable y ancho máximo de 620 dp.
- Controles de al menos 48 dp, texto escalable, espacio para teclado y barras del sistema;
  mayor contraste en el texto secundario que el gris del mockup. Avisos y progreso fuera del
  contenido desplazable. Fuente de diseño en `apps/design/src/main/`, sin módulos ni bibliotecas nuevas.

Reglas de datos mostrados:

- No inventar nombres/modelos remotos ni contadores globales; «Inscripción activa» no significa conexión en vivo.
- El panel inicial consulta hasta 50 eventos recientes y, por separado, hasta 50 eventos
  `device_status` del dispositivo; la lectura visible se elige solo entre registros reales de
  esa consulta con su fecha, nunca entre simulados. Si no está en esa página, informar que no hay datos recibidos.
- El estado inicial no reutiliza ni modifica filtros/cursores del historial ni muestra lecturas
  de otro dispositivo durante un cambio. La antigüedad visible se actualiza cada minuto; no se
  infiere desconexión por edad.
- Las fechas de calendario incluyen ambos días en UTC; internamente el extremo final se envía
  como el inicio del día siguiente, exclusivo en la API. La exportación usa los filtros
  aplicados y avisa si hay cambios sin aplicar.
- Agente: «Activo» requiere servicio de captura en ejecución, indicador disponible y ausencia
  de retirada; «Listo para activar» si está inscrito con indicador y sin captura. No inventar
  hora de sincronización (solo contadores confirmados). Historial local: últimos 100 registros
  con pendiente/enviado/rechazado y origen real/simulado. Estado del dispositivo desde Room,
  sin consultar telefonía desde la interfaz. Las consultas DAO leen tablas existentes: no
  cambian el esquema ni borran datos.
- Centro: navegación inferior con cuatro destinos; opciones administrativas ocultas al
  auditor (la autorización efectiva permanece en la API).

## Instalación Windows con Tailscale (INICIO_WINDOWS_0.4.md)

> Origen: `docs/INICIO_WINDOWS_0.4.md` — guía base 0.4 actualizada al contenido 0.7;
> revisión de Defender del 29 de octubre de 2026: sin amenazas; mantener Defender activo y,
> si vuelve a bloquear, dejarlo bloqueado sin añadir exclusiones.

Requisitos: una PC Windows encendida y dos teléfonos Android 8 o superior con Internet; la PC
ejecuta el servidor, A el Agente y B el Centro. Tailscale conecta redes distintas sin dominio
ni abrir puertos del router.

Pasos:

1. Descargar Agente 0.7.5-preview, Centro 0.7.4-preview y, si procede, el servidor 0.7.0-preview (enlaces en la página de descargas).
2. PowerShell: `winget install --id Docker.DockerDesktop -e`, `winget install --id Microsoft.OpenJDK.21 -e`, `winget install --id Tailscale.Tailscale -e`. Reiniciar si se pide; abrir Docker Desktop (contenedores Linux/WSL2) y una PowerShell nueva tras instalar Java.
3. Extraer todo el ZIP en `C:\AuditLab` (`Iniciar-AuditLab.ps1`, `api`, `apks`, `source`); no ejecutar dentro del ZIP. En actualizaciones: detener la API con Ctrl+C, conservar `C:\AuditLab\.auditlab-windows` y los volúmenes Docker, respaldar `api` y extraer la nueva; no borrar la base ni la configuración para resolver errores de inicio.
4. `cd C:\AuditLab` y `powershell -NoProfile -ExecutionPolicy Bypass -File .\Iniciar-AuditLab.ps1`. En instalación nueva, crear la contraseña de `admin` (12–128 caracteres) y guardarla. Comprobar `http://127.0.0.1:8081/ready` → `{"status":"ready"}` (la dirección sin `/ready` no tiene página web).
5. Tailscale: iniciar sesión en Windows y en los dos teléfonos con VPN activada; verificar `& "$env:ProgramFiles\Tailscale\tailscale.exe" status` y `... serve status`; si el puerto 8443 está libre, `tailscale.exe serve --bg --https=8443 http://127.0.0.1:8081`. Habilitar HTTPS si el comando lo pide y usar la dirección real devuelta (ej. `https://mi-pc.tail123456.ts.net:8443`; no usar el ejemplo). Cada teléfono debe abrir esa URL + `/ready` sin errores de certificado antes de inscribir.
6. Instalar APKs conservando datos en actualizaciones. En el Centro: URL HTTPS sin `/ready` ni `/v1`, usuario `admin` y la contraseña creada en Windows (no la de Tailscale); Más → Crear código de inscripción. En A: consentimiento, misma URL, código, Inscribir; permitir notificaciones y habilitar indicador; verificar A en Equipos. No usar `127.0.0.1` en los teléfonos; si el Agente estaba inscrito con otra URL, retirar/confirmar la inscripción anterior y cerrar la sesión del Centro para editar la URL.

Errores que MANUAL_PRODUCTO.md no cubre:

- **Credenciales rechazadas:** comprobar `/ready` en ambos teléfonos; usar `admin` y la contraseña de esa instalación del servidor; una sesión de otra instalación/URL exige cerrar sesión y volver a iniciar.
- **«Actualiza el servidor»:** se usa el backend anterior; instalar el ZIP 0.7 conservando la configuración y ejecutar el lanzador nuevo.
- **No llegan notificaciones:** exigir sesión activa, acceso Android concedido, notificaciones de WhatsApp habilitadas y texto disponible; un chat abierto puede no generar notificación y no se inventan mensajes sin ella.
- **Se detuvo en segundo plano:** revisar el ahorro de batería del fabricante; forzar la detención o reiniciar el teléfono exige volver a autorizar las sesiones.

## Alertas WhatsApp sin Firebase (ALERTAS_WHATSAPP_0.7.4.md)

> Origen: `docs/ALERTAS_WHATSAPP_0.7.4.md` — 29 de octubre de 2026 (America/Lima), Centro 0.7.4 (P6.2/P6.6/P6.4/P8.2/P8.3).

Activación: instalar Centro 0.7.4 conservando datos (compatible con Agente 0.7.1 y API 0.7;
sin cambios de servidor, URL, base de datos ni inscripción); en el Agente, indicador visible,
acceso a notificaciones y sesión de vistas previas de WhatsApp autorizada; en el Centro,
Equipos → Ver dispositivo → Activar alertas y permitir notificaciones Android (13+). Aparece
la notificación persistente «AuditLab · Alertas activas» con consulta cada 5 segundos mientras
Android permita ejecutar el servicio y haya red. Expandir el aviso permite leerlo; tocarlo
abre Eventos → WhatsApp filtrado por equipo. Detener desde la notificación o el detalle del
equipo; una sola fuente por vez.

Límites del servicio dataSync:

- Se implementa como **servicio visible dataSync**, no con push de Firebase: sin cuenta Firebase y sin enviar textos a Google.
- Android 15 aplica un presupuesto de **6 horas de dataSync en segundo plano por cada 24 horas**; al alcanzarlo, el servicio se detiene. No se promete vigilancia permanente.
- **No se reinicia tras forzar la detención**, reiniciar el teléfono ni morir el proceso. Fabricante, ahorro de batería, Doze y Android pueden retrasar/interrumpir las consultas.
- Sin sesión autorizada del Agente, conexión, credenciales válidas o permiso, el servicio se detiene; reabrir el Centro y activar de nuevo (sin reconexión oculta).
- Solo notificaciones recibidas con texto y timestamp posterior a activar alertas; no captura mensajes enviados desde el chat ni historial anterior.
- Deduplicación en RAM; no crea eventos adicionales ni guarda textos en Room/SQL/CSV; sin contenido a proveedores push; sin cambios de contrato API.
- Avisos ocultos en pantalla bloqueada con caducidad máxima de 5 minutos; al detener se cancelan. Sesión detenida, revocación o borrado del texto en el Agente se detectan en la siguiente consulta exitosa y retiran los avisos (sin retirada instantánea sin conexión).
- Cada consulta se audita; más consultas aumentan batería/red y volumen de auditoría. El cierre de sesión invalida el núcleo compartido y la limpieza local detiene el servicio; sin credenciales paralelas.
- Permisos del Centro: `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`; servicio no exportado y sin arranque automático.

Validación registrada: compilación, lint y auditoría de APK, pruebas de selección temporal,
deduplicación, timestamps inválidos/caducados/futuros, limpieza del núcleo compartido y
regresión de autenticación, más un recorrido nativo API26 con entrada ficticia vía API. Sin
prueba en teléfonos físicos ni WhatsApp real; pendiente P7.6.

## Alias y archivo de dispositivos (DISPOSITIVOS_0.7.3.md)

> Origen: `docs/DISPOSITIVOS_0.7.3.md` — 29 de octubre de 2026 (America/Lima), Centro 0.7.3 (P6.2/P6.4). Compatible con Agente 0.7.1 y servidor 0.7; actualizar solo el Centro, conservando datos, sin cambiar la URL ni volver a inscribir.

- Equipos muestra nombre, fecha de inscripción y último contacto. **Contacto reciente** significa comunicación registrada hace como máximo 2 minutos; no garantiza conexión continua. Revocado, sin contacto registrado y sin contacto reciente son estados distintos de la inscripción activa.
- Cambiar nombre: Equipos → Ver dispositivo → Cambiar nombre; se guardan de 1 a 60 caracteres.
- **Archivar dispositivo** pide confirmación y oculta el equipo de Inicio y de la lista habitual; Equipos → Mostrar → Archivados permite abrirlo y restaurarlo.
- Se conservan los eventos, sus identificadores y la auditoría. **Archivar no revoca:** el Agente puede seguir enviando; para bloquear envíos hay que usar Revocar.
- Nombre y archivo se guardan **solo en este Centro**, separados por URL del servidor e identificador de inscripción; no se sincronizan con otros Centros y se pierden si se borran los datos de la app. No son permisos del servidor.
- Cada inscripción tiene su propio nombre/archivo; no se reasignan datos de equipos anteriores. Las tarjetas del historial muestran siempre el nombre del origen.
- Los filtros de inscripción y la paginación siguen activos: el archivo filtra la página cargada; si no aparece un equipo, revisar el filtro y las páginas siguientes. «Todos los dispositivos» en Eventos sigue incluyendo los archivados; archivar no cambia la consulta histórica.

Comprobaciones registradas: compilación, lint y auditoría de APK, y dos pruebas Robolectric
(persistencia/restauración/aislamiento por servidor y equipo con validación de nombre, y
límites del estado de contacto). Sin ejecución en teléfonos físicos.

## Interfaz simple y activación única (INTERFAZ_SIMPLE_0.8.3.md)

> Origen: `docs/INTERFAZ_SIMPLE_0.8.3.md` — 4 de noviembre de 2026.

Pantalla principal del Agente con estado, batería, última transmisión y funciones; un único
**Activar** abre la selección y **Activar lo seleccionado** confirma. Con monitoreo en
 ejecución el control es **Detener todo**: para cambiar funciones se detiene y se vuelve a
activar. Cada fuente sensible empieza sin marcar; desmarcar SMS/llamadas elimina números y
texto de esa selección. La confirmación conduce por los permisos necesarios (notificaciones,
SMS/llamadas, acceso a notificaciones WhatsApp y MediaProjection); denegar uno no concede el
resto. **Detener todo** cierra la visibilidad, cancela activaciones pendientes y bloquea la
recuperación de batería, sin retirar el consentimiento. La recuperación opcional al encender
solo guarda batería/conexión. En el Centro: Inicio con equipo y actividad reciente; Eventos con
pestañas, filtros plegados, exportación y detalle; las explicaciones de privacidad se despliegan
al solicitarlas.

## Revisión de Defender 0.7 (REVISION_DEFENDER_0.7.md)

> Origen: `docs/REVISION_DEFENDER_0.7.md` — 29 de octubre de 2026.

El ZIP 0.7 original pasó análisis en Windows Server 2022 con Defender activo, firmas
actualizadas, marca de zona Internet y sin exclusiones sobre el ZIP (motor `1.1.26100.4`,
firmas `1.463.16.0`): sin amenazas; la alerta reportada no se reprodujo. Política: mantener
Defender activo y, si vuelve a bloquear, dejarlo bloqueado sin añadir exclusiones. Un análisis
previo nunca acredita un ZIP distinto; cada publicación exige análisis de sus bytes exactos.
