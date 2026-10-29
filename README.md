# AuditLab — Descargas

Monitoreo visible y autorizado para dispositivos Android propios o administrados.

## Versión actual: 0.6.0-preview

| Componente | Descargar |
| --- | --- |
| Agente | [APK Agente](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.6.0-preview.apk) |
| Centro de control | [APK Centro](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.6.0-preview.apk) |
| Servidor Windows, apps y fuentes | [Servidor 0.6](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.6.0-preview.zip) |

**Actualiza las tres piezas. Android 8.0 o superior.** Conserva datos de las apps,
`.auditlab-windows`, claves y datos Docker. Extrae el servidor nuevo en la misma
ubicación, conservando respaldo de la API anterior. El lanzador aplica V8;
no es necesario cambiar la URL de Tailscale ni crear otra cuenta.

[Instalación sin USB](PASOS-SIN-USB.md) · [Cambios y activación](ACTUALIZACION-0.6.md) ·
[SHA256](SHA256SUMS-0.6.0-preview)

## Cambios

- Eventos abre en mensajes y llamadas reales. Batería y conexión permanecen en Inicio.
- SMS/llamadas muestran tipo, hora local y número únicamente si fue autorizado.
- Alias locales de teléfonos; UUID y códigos bajo Información técnica.
- Texto temporal de WhatsApp dentro de Eventos → WhatsApp, con sesión local autorizada.
- Actualizar ahora y consulta automática existentes.

Para números, detén captura real en el Agente, marca la casilla adicional y vuelve
 a autorizar con permisos Android. Solo afecta eventos nuevos; no lee agenda,
contenido de SMS ni audio. El número usa retención y CSV de eventos.

Para WhatsApp, configura acceso Android a notificaciones y autoriza/inicia la
sesión visible en el Agente. En el Centro abre Eventos → WhatsApp y selecciona
un dispositivo si hay varios. Solo texto nuevo que Android exponga, máximo
20 vistas previas/5 minutos en memoria, fuera de SQL, Room, historial y CSV.
No recupera historial ni detecta todos los envíos desde un chat abierto.

## Validación

Compilación de ambas apps/API, lint sin errores, cuatro OpenAPI y auditoría estática
correctos. V8 aplicada a base de desarrollo preservada. No se ejecutaron suites
automáticas ni revisión nativa en esta versión. Recepción real de WhatsApp,
operadores telefónicos, Windows y teléfonos físicos siguen pendientes. APK debug;
paquete de desarrollo sin aprobación de piloto o firma de distribución.

[Estado y evidencias](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/AVANCE.md) ·
[Registro](ENTREGA-0.6.0-preview.json) · [Código](https://github.com/Juanguz095/ProyectoDesarrolloMoviles)

Las versiones anteriores se conservan en esta rama.
