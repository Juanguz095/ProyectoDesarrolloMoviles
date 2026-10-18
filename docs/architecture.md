# Arquitectura inicial

## Arquitectura objetivo

```text
Agente Android A ── HTTPS + token de dispositivo ──┐
                                                   ├── API ── PostgreSQL
Centro de control B ── HTTPS + rol de usuario ─────┘
```

En el producto objetivo, el agente Android captura eventos simulados y metadatos permitidos solo con consentimiento vigente. Room conserva una cola local acotada; WorkManager envía lotes cuando hay conexión. El centro de control consulta la API según su rol. La API valida cada evento, autentica al dispositivo o usuario y registra consultas y acciones administrativas.

## Implementación MVP actual

La primera versión ejecutable usa Python estándar, SQLite y una interfaz web local que simula las dos apps. Se enlaza solo a loopback, ofrece login por rol, inscripción de un uso, eventos manuales de demostración, consultas, CSV, auditoría y revocación local/remota. No contiene código Android nativo ni es apta para desplegarse.

La base definitiva ya está separada: módulos Android informativos y Ktor con conexión/disponibilidad, migraciones PostgreSQL y CLI de bootstrap/permisos y [autenticación HTTP](autenticacion.md) con JWT y sesiones revocables. El [contrato definitivo](contrato.md) y el [modelo de datos](base-datos.md) están definidos; las rutas de inscripción/eventos/consultas y revocación de dispositivos siguen pendientes de fase 3. El recorrido funcional del párrafo anterior corresponde exclusivamente al prototipo Python/web.

## Flujo vertical inicial

1. Un administrador crea una inscripción de un solo uso.
2. La persona usuaria acepta el monitoreo en el agente y completa la inscripción.
3. El agente genera un evento de prueba propio de la aplicación y lo guarda localmente.
4. El agente envía el evento autenticado por HTTPS.
5. La API valida el esquema y la vigencia del consentimiento asociado.
6. Un usuario autorizado consulta el evento; la consulta queda en el registro de auditoría.
7. Al revocar el dispositivo, la API invalida su credencial y el agente deja de enviar.

## Decisiones y límites

- API contract-first en OpenAPI 3.1.
- Kotlin + Compose, Room y WorkManager para las aplicaciones Android objetivo.
- Ktor + PostgreSQL para el backend objetivo; el prototipo local usa SQLite.
- Los tokens se almacenan con Android Keystore y no se registran en logs.
- La inscripción usa un código de un solo uso y entrega una credencial revocable del dispositivo.
- El MVP no solicita permisos para leer SMS, llamadas, chats, notificaciones o contenido de otras apps.
- El modelo de evento contiene categorías permitidas por lista blanca; el servidor rechaza campos adicionales.

## Roles mínimos

| Operación | Admin | Auditor |
| --- | --- | --- |
| Listar dispositivos y consultar eventos | Sí | Sí |
| Exportar reportes | Sí | Sí |
| Revocar dispositivo | Sí | No |
| Consultar registro de auditoría | Sí | Sí |

Cada operación de consulta, exportación o administración debe registrar actor, acción, recurso y hora. No guardar datos sensibles en el registro de auditoría.
