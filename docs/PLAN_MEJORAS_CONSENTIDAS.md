# Plan de mejoras consentidas — 0.9

11 de noviembre de 2026 (America/Lima). Plan autorizado por el responsable del proyecto.
Ampliaciones nuevas sobre la base 0.8.8-preview. Revisión M0: [REVISION_SEGURIDAD_M0.md](REVISION_SEGURIDAD_M0.md).
Documentos relacionados: [PLAN_MAESTRO](PLAN_MAESTRO.md), [AVANCE](AVANCE.md), [INTERFAZ_SIMPLE_0.8.3](INTERFAZ_SIMPLE_0.8.3.md),
[COMPARTIR_TEXTO_MANUAL_0.8.1](COMPARTIR_TEXTO_MANUAL_0.8.1.md), [COMPARTIR_PANTALLA](COMPARTIR_PANTALLA.md),
[CAPTURA_REAL](CAPTURA_REAL.md), [WHATSAPP_NOTIFICACIONES](WHATSAPP_NOTIFICACIONES.md).

## 1. Objetivo y alcance

Mejorar AuditLab manteniendo consentimiento explícito, indicador visible, revocación,
límites de uso, retención y auditoría. El plan se suma a las validaciones nativas
pendientes (recepción real de SMS, visor de pantalla, F1–F6); no las reemplaza ni las cierra.

Nuevas funciones: consentimiento independiente por función, video de cámara (sin audio),
ubicación aproximada continua y galería seleccionada por el usuario. Todo arranca por
acción local confirmada; nunca hay activación remota ni oculta.

## 2. Decisiones registradas

| ID | Decisión | Motivo |
| --- | --- | --- |
| D1 | El plan se añade a las validaciones pendientes; no las reemplaza. | Las validaciones nativas requieren hardware físico y siguen abiertas. |
| D2 | Orden de implementación: consentimiento por función → galería → ubicación → video. | De menor a mayor riesgo; la galería sigue el patrón probado de texto manual. |
| D3 | Video sin pista de audio; cámaras frontal y trasera. | Evita cualquier conflicto con el límite base de "sin audio de llamadas" y reduce la sensibilidad. |
| D4 | Grabación máxima de 5 minutos por clip; sin límite de cantidad diaria. | Límite de duración acordado; la cola local existente protege el dispositivo. |
| D5 | No se bloquea la grabación durante llamadas activas. | Al no existir audio (D3) no se captura audio de llamadas; se documenta expresamente. |
| D6 | Ubicación en tiempo real (continua), no puntual. La "ubicación puntual" queda como acción especial "pedir ubicación ahora". | Requisito acordado: lecturas continuas con la última muestra siempre fechada. |
| D7 | Ubicación activa también en segundo plano, hasta que el usuario corte el servicio. | Foreground service con notificación permanente y detención local accesible. |
| D8 | Precisión aproximada (no precisa). | Menos invasiva; suficiente para auditoría. |
| D9 | Galería: JPEG/PNG/WebP/HEIC, 15 MB por archivo y 20 elementos por selección. Vista previa obligatoria antes de enviar. | Límites propuestos por el equipo de diseño ante la ausencia de valores acordados; ajustables. |
| D10 | Las fotos y grabaciones se envían al servidor y se visualizan desde el Centro. | Requisito acordado. |
| D11 | Almacenamiento del servidor: archivos en filesystem con metadatos y referencia en PostgreSQL. | Lo más cómodo con Ktor + Flyway; PostgreSQL no almacena blobs grandes. |
| D12 | Grabaciones, ubicaciones y álbumes: borrado manual por rol autorizado y auditado; sin expiración automática. Los eventos de metadatos siguen la retención de 30 días existente. | Requisito acordado; la auditoría de borrado cubre la trazabilidad. |
| D13 | Los dispositivos inscritos con consentimiento 0.8.x deben re-aceptar el consentimiento 0.9. | El modelo de módulos cambia; la re-aceptación explícita evita autorizaciones implícitas. |
| D14 | La interfaz conserva el estilo actual (paleta Paper/Panel/Ink/Muted, etiquetas rectangulares, navegación inferior) y añade mejoras de visibilidad de la sección 6. | Instrucción acordada. |

## 3. Límites que no se negocian

- Sin activación remota ni oculta de cámara, ubicación o galería.
- Sin exploración automática de la galería; solo elementos elegidos por el usuario.
- Sin acceso a contactos, agenda ni historial de chats.
- Sin audio de llamadas ni audio ambiental (D3).
- Sin extracción mediante accesibilidad.
- Sin bloqueo de desinstalación ni de revocación.
- Sin captura sin indicador visible y notificación permanente.
- Revocación accesible en todo momento, local y remota.
- Todo módulo nuevo exige consentimiento propio con finalidad, datos recopilados y conservación visibles.

## 4. Datos fuera del sistema

Agenda y contactos; historial previo de SMS; RCS; audio de llamadas y audio ambiental;
historial de chats de WhatsApp; archivos de galería no seleccionados; ubicación precisa;
biometría; sensores distintos de los declarados; captura de pantalla oculta; teclas o
accesibilidad; datos de otras aplicaciones.

## 5. Fases ejecutables

### M0 — Revisión de privacidad y seguridad (0.9.0-doc)

- Comparar código, contratos y documentación. Lista de funciones pendientes de
  validar en teléfonos físicos. Datos fuera del sistema (sección 4).
- Comprobación: documento de revisión con hallazgos por área.
- Evidencia: [REVISION_SEGURIDAD_M0.md](REVISION_SEGURIDAD_M0.md). Estado: completada.

### M1 — Consentimiento independiente (0.9.0)

- Modelo de módulos: base (batería/conexión), comunicaciones (número, texto SMS),
  whatsapp, pantalla, texto manual, galería, ubicación y video. Cada módulo con
  política versionada (patrón `ManualSharePolicy`), finalidad, datos, duración y
  conservación visibles. Estados: aceptado, pausado, revocado, pendiente de re-aceptación.
- Pausar o revocar cada módulo por separado; revocación general retira todos los módulos.
- Migración V12 `consent_modules`. Re-aceptación obligatoria de dispositivos 0.8.x (D13).
- Contrato: `openapi.yaml` extiende consentimiento con módulos.
- Agente: sección "Consentimiento y funciones" en Detalles y ajustes.
- Comprobaciones: revocación general arrastra módulos; pausa detiene su captura;
  re-aceptación de un dispositivo 0.8.x; pruebas JVM de `AgentCore` y API.
- Criterio: ningún módulo nuevo captura sin su consentimiento aceptado.

### M2 — Galería seleccionada (0.9.1)

- Photo Picker oficial o selector de documentos de Android; selección explícita de
  álbum o fotos; vista previa de los elementos antes de enviar; cancelación con
  borrado de temporales. Límites D9.
- Envío al servidor como lote con identificador idempotente; Centro los muestra en
  la pestaña Álbumes. Descarga por rol y auditada.
- Migración V13 `media_items`. Contrato `gallery-share.openapi.yaml` (patrón manual-share).
- Comprobaciones: denegar permiso, cancelar selección, archivo >15 MB rechazado,
  reintento idempotente, revocación bloquea envío, temporales borrados.
- Criterio: sin selección explícita no hay envío; el Centro solo lista lotes aceptados.

### M3 — Ubicación aproximada continua (0.9.2)

- Permiso de ubicación aproximada y foreground service tipo `location`.
- Modo en vivo cada 60 s por defecto, configurable entre 30 s y 15 min; activo en
  segundo plano hasta que el usuario corte el servicio (D7). Acción especial
  "pedir ubicación ahora" (antigua ubicación puntual).
- Notificación permanente mientras está activa; pausar, detener y borrar pendientes.
- Eventos `location_update` con latitud, longitud, precisión aproximada y fecha.
  El Centro muestra "última lectura conocida" con su fecha; nunca la presenta como
  ubicación actual si es antigua. Máximo 1440 muestras por dispositivo y día.
- Migración V14 `location_samples`. Contrato `location.openapi.yaml`.
- Comprobaciones: permiso denegado, segundo plano, corte del servicio, borrado de
  pendientes, límite diario, expiración de lectura visible en el Centro.
- Criterio: sin notificación permanente no hay servicio de ubicación.

### M4 — Video de cámara sin audio (0.9.3)

- Grabación iniciada y confirmada localmente; indicador visible, notificación
  permanente y botón de detención accesible. Solo video (D3); cámaras frontal y
  trasera con selección local antes de grabar. Máximo 5 min por clip (D4),
  720p por defecto y 1080p máximo, 250 MB por clip.
- Sin activación remota ni grabación indefinida. No se bloquea durante llamadas (D5);
  se documenta que al no haber audio no se captura audio de llamadas.
- Subida al servidor con reintentos de la cola existente; Centro los reproduce según rol.
- Migración V15 `video_recordings`. Contrato `video.openapi.yaml`.
- Comprobaciones: permisos de cámara, cambio de cámara, detención a mitad, clip
  >5 min cortado, subida interrumpida y reanudada, revocación bloquea grabación.
- Criterio: toda grabación tiene inicio local, indicador visible y final local.

### M5 — API, roles y Centro consolidados (0.9.4)

- Endpoints de grabaciones, ubicaciones y álbumes con validación de dispositivo,
  consentimiento del módulo vigente, sesión activa, idempotencia, límites de tamaño
  y aislamiento por dispositivo. Almacenamiento D11, borrado manual auditado D12.
- Auditoría de visualizaciones, exportaciones, borrados y revocaciones. La auditoría
  guarda referencias y tamaños, nunca contenido sensible.
- Centro: pestañas separadas de grabaciones, ubicaciones, álbumes y eventos
  generales; consultas y descargas según rol; sin exportar contenido sensible por defecto.
- Comprobaciones: concurrencia, duplicados, rol auditor sin descarga, borrado
  auditado, aislamiento entre dispositivos.
- Criterio: el contrato OpenAPI y las migraciones cubren el 100 % de los flujos nuevos.

### M6 — Pruebas y entrega (0.9.5)

- Matriz: permisos, consentimiento, revocación, falta de red, límites, expiración,
  duplicados y concurrencia. Estados diferenciados: aprobado, fallido, omitido, pendiente.
- Validación en teléfonos físicos de cámara, ubicación, galería y video.
- Manuales de uso y consentimiento; retención, borrado y revocación documentados.
- Versiones, hashes y firmas registrados. Verificación del APK y del servidor antes
  de distribuir. Sin publicación sin autorización expresa.
- Criterio: evidencias por tarea; nada se declara validado sin ejecución nativa.

## 6. Interfaz: estilo conservado y mejoras visuales

Se conserva el estilo actual: paleta Paper/Panel/Ink/Muted de `AuditUi.kt`, etiquetas
rectangulares 0.8.8, navegación inferior de cuatro destinos (INICIO, EVENTOS, EQUIPOS,
MÁS), componentes `AuditPage`, `AuditHeader`, `AuditButton`, `ScopeChoice` y estados
con color de 0.8.7 (verde activo, ámbar pendiente, gris detenido, rojo rechazado).

Mejoras de visibilidad propuestas:

1. Inicio del Agente: franja de chips por función activa o pausada, con color por
   categoría y acceso directo al detalle de esa función.
2. Activación del Agente: cada fila `ScopeChoice` nueva muestra finalidad, datos
   recopilados y conservación sin desplegables; el módulo sin permiso de Android se
   marca con ámbar y enlace al permiso.
3. Detalles y ajustes del Agente: sección "Consentimiento y funciones" con un estado
   por módulo (activo, pausado, revocado) y su botón de pausa o revocación; la
   retirada general queda al pie con confirmación, como hoy.
4. Centro, Eventos: pestañas Todo, Comunicación, WhatsApp, Compartidos, Ubicaciones,
   Grabaciones y Álbumes. Los filtros plegados actuales se conservan.
5. Centro, Inicio: tarjeta de equipo con chips de función activa y última lectura
   fechada; acceso directo a Ubicaciones en vivo, Grabaciones y Álbumes.
6. Ubicaciones del Centro: lista de muestras con fecha, hora y precisión; ninguna
   muestra sin fecha se presenta como actual. Acción "Ver en vivo" mientras el
   servicio está activo.
7. Grabaciones y Álbumes del Centro: visor con fecha, tamaño y autor; descarga con
   confirmación por rol y registro de auditoría.
8. Iconos por función en las etiquetas rectangulares existentes (cámara, ubicación,
   imágenes), manteniendo el contraste mínimo verificado de 4,5:1.

## 7. Relación con las validaciones pendientes

Las validaciones nativas abiertas (recepción real de SMS, visor de pantalla en el
Huawei, F1–F6 de administración, texto manual 0.8.1, Android 13/14/15 y fabricantes)
permanecen abiertas y se registran como tales. Este plan no las cierra ni reutiliza
sus evidencias. Se ejecuta en paralelo y sus fases no declaran nada validado sin
ejecución nativa.

## 8. Marco de ampliaciones autorizadas

Este documento constituye la autorización expresa de las ampliaciones de galería,
ubicación aproximada continua y video sin audio, con los límites de las secciones 2 y 3.
Las ampliaciones anteriores (captura real, pantalla compartida y notificaciones de
WhatsApp) conservan sus límites y no se amplían. Ninguna de estas funciones sustituye
los permisos de Android ni el consentimiento local.
