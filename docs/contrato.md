# Contrato de API 0.2 — Ktor/PostgreSQL

Relación: P2.2. [OpenAPI definitivo](../contracts/openapi.yaml) es la fuente de verdad para las interfaces nuevas. [Contrato 0.1](../contracts/prototype-v0.1.yaml) conserva el comportamiento de referencia Python. Publicar un esquema no implementa las rutas: consultar el registro de avance para conocer el servidor disponible.

## Autenticación y rutas

Base `/v1`, HTTPS en piloto y loopback en desarrollo. Todas las respuestas llevan correlación y `Cache-Control: no-store`. Credenciales fuera de URLs y logs. Login/refresh se limitan frente a abuso.

| Operación | Identidad requerida | Éxito |
| --- | --- | --- |
| POST `/auth/login` | Credenciales de usuario | 200, access + refresh + sesión + usuario/rol. |
| POST `/auth/refresh` | Refresh de un uso | 200, tokens rotados sin extender vencimiento absoluto. |
| POST `/auth/logout` | Usuario | 204, sesión invalidada. |
| POST `/devices/enrollment-code` | Admin | 201, código de un uso por 10 min. |
| POST `/devices/enroll` | Código + consentimiento explícito | 201, credencial y consentimiento canonicalizado. |
| GET `/devices`, `/devices/{deviceId}` | Admin/auditor | 200, página o detalle. |
| POST `/devices/{deviceId}/revoke` | Admin | 204, revocación confirmada; repetir sobre dispositivo existente es idempotente. |
| POST `/devices/{deviceId}/consent-revoke` | Agente propio | 204; credencial inválida posterior también permite finalizar la limpieza local. |
| POST `/events` | Agente propio | 202, lote ya persistido y contadores; no solo una cola no confirmada. |
| GET `/events` | Admin/auditor | 200, historial filtrado y paginado. |
| GET `/devices/{deviceId}/events/{eventId}` | Admin/auditor | 200, detalle por clave compuesta. |
| GET `/events/export.csv` | Admin/auditor | 200 CSV del conjunto filtrado. |
| GET `/audit-log` | Admin/auditor | 200, auditoría paginada. |

## Reglas del servidor que no sustituye JSON Schema

- Verificar credencial, rol, sesión/dispositivo activo y recursos en cada petición. El agente no consulta historiales ni usa tokens de usuario.
- Normalizar fechas a UTC/microsegundos antes de comparar y calcular contenido idempotente. El cliente reutiliza el contexto canonicalizado devuelto por inscripción.
- Validar política vigente, contexto exacto y `timestamp >= acceptedAt`, máximo 5 minutos futuros. `receivedAt` se genera en el servidor y nunca se acepta en entrada.
- Rechazar cuerpos de más de 256 KiB y lotes fuera de 1–100. Idempotencia por dispositivo/ID; contenido diferente devuelve `409 EVENT_ID_CONFLICT`, sin escrituras parciales. Confirmar commit antes de responder.
- Filtros temporales con inicio inclusivo y fin exclusivo; `from < to`. Búsqueda exacta por ID/código permitido, sin contenido libre.
- Cursores firmados, opacos, vinculados a actor/filtros, con vencimiento de 10 minutos y techo de recepción de la primera página. Orden de eventos `(timestamp,eventId,sourceDevice)` descendente; dispositivo `(enrolledAt,deviceId)`; auditoría `(occurredAt,auditId)`. Las eliminaciones por retención pueden reducir páginas; no se promete snapshot histórico inmutable.
- CSV usa los mismos filtros; si supera 10000 filas, responder `409 EXPORT_LIMIT_EXCEEDED` sin truncar silenciosamente. Auditar filtros/resultado y escapar celdas con posible interpretación de fórmulas.
- Refresh rotatorio y revocación serializados mediante transacciones; reutilización revoca la sesión. Código de inscripción consumido de manera atómica. Serializar revocación/ingesta sobre dispositivo.
- En revocación propia, el cliente detiene localmente antes de la petición. Un agente offline solo conoce una revocación remota en el siguiente contacto; la API ya debe rechazar sus lotes.

Errores llevan `application/problem+json`, `status`, `code` estable y `correlationId`, sin SQL/stack trace. `401` invalida credencial/sesión; `403` indica rol/alcance; `409` requiere distinguir `DEVICE_REVOKED`, `CONSENT_MISMATCH`, `EVENT_ID_CONFLICT` y `EXPORT_LIMIT_EXCEEDED`. `429` incluye `Retry-After`; `503` permite reintento acotado. `204` no lleva cuerpo.

## Comprobación

```bash
.venv/bin/python scripts/check_contract.py
.venv/bin/python -m unittest discover -s contracts/tests -v
```

Los [casos ficticios de esquema](../contracts/examples/schema-cases.json) incluyen entradas válidas e inválidas. Las pruebas también verifican límites del lote, claves YAML duplicadas, seguridad de rutas, respuesta 204 y consistencia de ejemplos de error. No llaman al servidor ni validan autorización/cronología/transacciones; esas pruebas se ejecutarán con la implementación de fase 3.

## Implementación disponible

P3.1 implementa las tres rutas de autenticación en Ktor; configuración y límites en [autenticación](autenticacion.md). Las otras operaciones de OpenAPI siguen pendientes. Las pruebas de fixtures comprueban esquemas; las pruebas HTTP/PostgreSQL verifican las rutas existentes y sus transacciones.
