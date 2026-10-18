# Pantallas y estados del producto Android

Relación: P1.2. Esta especificación describe el producto a construir; no atribuye estas capacidades al simulador web.

## Agente A

| Estado | Entrada | Pantalla/indicador y acciones | Captura/envío | Salida |
| --- | --- | --- | --- | --- |
| `sin_consentimiento` | Instalación inicial o consentimiento rechazado. | Finalidad, datos, retención, versión de política, casilla sin preselección, aceptar/rechazar. | Ninguno; no programar workers. | Aceptar → `inscribiendo`; rechazar mantiene estado. |
| `inscribiendo` | Aceptación explícita y solicitud con código. | Progreso, código, errores y cancelar; no mostrar «activo» antes de respuesta válida. | Ninguno. | `201` y visibilidad disponible → `activo`; fallo/cancelación → sin activación; token recibido sin visibilidad → pausa. |
| `activo` | Identidad/consentimiento vigentes e indicador permitido. | Identificador, datos permitidos, último envío, pendientes, botón de evento simulado y revocar; notificación visible. | Solo lista permitida, cola y envíos autenticados. | Revocar → detención inmediata; pérdida de indicador → pausa; rechazo definitivo → `revocado`; capacidad agotada → pausa. |
| `pausado_por_visibilidad` | Notificaciones denegadas, deshabilitadas o indicador no disponible. | «Monitoreo pausado: habilita el indicador visible para continuar», acceso a ajustes y revocar. | No capturar ni enviar eventos; conservar cola vigente. | Restaurar visibilidad con consentimiento vigente → `activo`; revocar → pendiente/revocado. |
| `pausado_por_capacidad` | Cola alcanza 10000 pendientes. | «Captura pausada: cola llena», contador, estado de red y revocar. | No capturar nuevos; enviar pendientes solo si se mantiene consentimiento/visibilidad. | Liberar capacidad → `activo`; revocar → pendiente/revocado. |
| `revocacion_pendiente` | Revocación local y sin confirmación del servidor. | «Monitoreo detenido; revocación pendiente de confirmar», sin botones de captura/sync. | Cancelar trabajadores de eventos y borrar cola antes de red; solo worker separado de revocación. | Confirmación o credencial ya inválida → `revocado`; falta de red mantiene estado tras reinicio. |
| `revocado` | Revocación confirmada, respuesta remota de revocación o credencial definitivamente inválida. | «Monitoreo detenido», credencial eliminada, opción de nueva inscripción. | Ninguno; cola eliminada. | Nueva inscripción exige política aceptada y código nuevos. |

La pausa por capacidad es un subestado añadido para concretar el límite de cola del plan. La falta de red es una condición transversal, no retira por sí misma el consentimiento ni borra pendientes. Revocación prevalece sobre cualquier respuesta de envío o inscripción que llegue tarde.

## Secuencia obligatoria de revocación local

1. Persistir el estado detenido/revocación pendiente antes de iniciar solicitudes.
2. Impedir generación y nuevos envíos; cancelar WorkManager de eventos, borrar pendientes y retirar el indicador de monitoreo activo.
3. Mantener la credencial únicamente en el almacén de la tarea de revocación, nunca accesible para un worker de eventos.
4. Solicitar revocación propia. En timeout/sin red, reintentar solo esa operación con backoff.
5. Al confirmar o recibir credencial ya inválida, eliminarla y persistir `revocado`.

Una petición de eventos ya en vuelo podría haberse confirmado antes de la revocación del servidor. La UI no promete borrar ese historial. La API serializa ambas operaciones y rechaza cualquier lote que intente confirmarse después de la revocación.

## Red y errores

| Situación | Comportamiento y mensaje |
| --- | --- |
| Sin conexión/timeout/`5xx` de envío | Conservar lote con IDs originales; backoff acotado por WorkManager. «No se pudo enviar; se reintentará». |
| Lote inválido `400` | Marcar fallo definitivo del lote y registrar motivo técnico saneado; no reintentar indefinidamente datos inválidos. Mostrar acción de soporte/borrado conforme a política. |
| Conflicto `409` | Diferenciar código de error de consentimiento/revocación e idempotencia. Detener credencial revocada; conflicto de contenido no se resuelve generando IDs nuevos silenciosamente. |
| Credencial inválida `401` | Detener envíos y pasar a estado no activo. No renovar un token de agente usando una sesión de usuario. |
| Fallo de inscripción | Mostrar el motivo seguro y permitir nuevo intento/código; no generar eventos mientras tanto. |
| Respuesta tardía tras revocar | No reactivar monitoreo ni restaurar credenciales/cola. |

## Centro de control B

| Pantalla | Estados y acciones | Restricción |
| --- | --- | --- |
| Login | Credenciales, progreso, error uniforme y reintento. | No cachear contraseña en logs ni preferencias. |
| Dispositivos | Cargando, vacío, listado, fallo; estado y última comunicación. | Admin: generar código/revocar. Auditor: consulta. |
| Historial | Dispositivo/categoría/rango/búsqueda, páginas, vacío/error; abrir detalle. | Filtros aplicados visibles, CSV respeta el mismo conjunto autorizado. |
| Detalle | Evento, indicador simulado, dispositivo, UTC/local identificados, consentimiento y metadatos permitidos. | No mostrar credenciales ni identificadores físicos. |
| Exportación | Selección de destino/compartición, progreso, éxito o error/límite. | Auditar solicitud/resultado; fallo de red no se presenta como archivo completo. |
| Auditoría | Filtros mínimos, páginas, actor/acción/recurso/resultado/hora. | Sin edición o borrado manual por admin/auditor. |
| Revocación | Confirmación, progreso, error y resultado confirmado por API. | Solo admin; deshabilitar doble envío. No cambiar estado a confirmado si la petición falla. |
| Sesión vencida | Refresh válido continúa; si falla, limpiar sesión y volver a login. | Solicitudes concurrentes comparten una rotación de refresh; no bucle de reintentos. |

Toda pantalla distingue carga, datos, vacío y error y conserva una ruta accesible para salir. Las acciones que modifican estado esperan confirmación real, excepto la detención local inmediata del agente.
