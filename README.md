# AuditLab — Descargas

Monitoreo visible y autorizado para dispositivos Android propios o administrados.

## Versión actual: 0.5.0-preview

| Componente | Descargar |
| --- | --- |
| Agente | [APK Agente](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.5.0-preview.apk) |
| Centro de control | [APK Centro](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.5.0-preview.apk) |
| Servidor Windows, apps y fuentes | [Servidor 0.5](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.5.0-preview.zip) |

**Actualiza las tres piezas a 0.5. Android 8.0 o superior.** Conserva los datos
locales de las apps, `.auditlab-windows`, sus claves y los datos Docker. Extrae el
servidor nuevo en la misma ubicación, conservando un respaldo de la API anterior.
El lanzador aplica V7; no es necesario cambiar la URL de Tailscale ni la cuenta.

[Instalación sin USB](PASOS-SIN-USB.md) ·
[Qué detectar y cómo activar WhatsApp](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/ACTUALIZACION_0.5.md) ·
[SHA256](SHA256SUMS-0.5.0-preview)

## Cambios en 0.5

- Tarjetas completas: se corrige el recorte circular de títulos y textos.
- Filtros rápidos de Eventos: Todos, WhatsApp, SMS, Llamadas y Batería.
- Eventos de notificación WhatsApp recibida y respuesta propia observada, con
  tipo/fecha/dirección y sin texto, nombres ni números en la cola, historial o CSV.
- Nueva autorización local v2. El texto de vistas previas sigue siendo temporal:
  hasta 20 elementos/5 minutos en memoria, sin guardarse en eventos ni SQL/Room.
- Se conserva la actualización automática del Centro y de batería/eventos del Agente.

## Activar actividad WhatsApp

En el Agente abre Configurar vistas previas de WhatsApp, concede acceso Android,
vuelve, marca la autorización de vistas previas y registro de actividad e inicia
la sesión. En el Centro abre **Eventos → WhatsApp** y actualiza.

La respuesta se detecta solo cuando Android la expone en una notificación. No
confirma entrega ni detecta todos los envíos escritos dentro del chat abierto.
No se importa historial; el propietario puede detener la sesión desde la app o
la notificación visible. SMS y llamadas requieren sus permisos propios.

## Estado de la entrega

Compilación de ambas apps/API, lint sin errores, contratos y auditoría estática de
APK satisfactorios. V7 aplicada al servidor de desarrollo conservando datos;
revisión visual de tarjetas autenticadas en un emulador con datos QA.
No se ejecutaron suites automáticas ni recepción desde WhatsApp instalado en
esta revisión. Windows y teléfonos físicos siguen pendientes. APK con firma debug;
paquete de desarrollo, sin aprobación de piloto o firma de distribución.

[Estado y evidencias](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/AVANCE.md) ·
[Registro de descarga](ENTREGA-0.5.0-preview.json) ·
[Código](https://github.com/Juanguz095/ProyectoDesarrolloMoviles)

Versiones anteriores conservadas en esta rama.
