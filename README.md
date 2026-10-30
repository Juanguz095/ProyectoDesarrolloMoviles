# AuditLab — descargas actuales

| Componente | Descargar |
| --- | --- |
| Agente 0.7.5 | [APK Agente](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.7.5-preview.apk) |
| Centro 0.7.4 | [APK Centro](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.7.4-preview.apk) |
| Servidor Windows 0.7 | [ZIP original revisado](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.7.0-preview.zip) |

## Batería y conexión con recuperación visible

Actualizar Agente sobre el anterior conservando sus datos. Compatible con Centro
0.7.4 y servidor 0.7; no requiere volver a inscribir ni cambiar URL.

En el Agente inscrito abrir Batería y conexión, elegir si se desea reanudar al
encender y pulsar Activar batería y conexión. La elección está inicialmente
desactivada y se guarda solo al activar. Notificación visible y botón Detener.
Pausar, retirar consentimiento o activar captura real deshabilita la recuperación.

Solo device_status: batería/conexión. No se reactivan SMS/llamadas, WhatsApp ni
pantalla tras boot. No funciona apagado ni se promete ejecución permanente.
Forzar detención, permisos, fabricante o restricciones Android pueden impedirlo.

[Uso, fragmentos y pruebas](MONITOREO-BASICO-0.7.5.md) ·
[Pasos Windows](PASOS-SIN-USB.md) · [SHA256 Agente](SHA256SUMS-0.7.5-preview) ·
[Entrega](ENTREGA-0.7.5-preview.json).

Compilación/lint/auditoría de APK/firma correctos; 18 pruebas JVM/Robolectric y
2 invocaciones nativas con reinicio real del AVD API28 aprobadas. Servicio observado
antes de abrir actividad/instrumentación; el proceso se reengancha para probar Detener.
Sin prueba en teléfonos, permiso inicial Android13 o arranque Android14/15.

## Centro 0.7.4

Alertas WhatsApp entrantes autorizadas mediante servicio visible, consulta cada
5 segundos, texto disponible y botón Detener. Tocar abre Eventos del equipo.
No usa Firebase ni captura el texto enviado dentro del chat. Android puede detenerlo.
Vistas previas en memoria hasta 5 minutos, sin Room/PostgreSQL/CSV.

Nombres y archivo locales de equipos; archivarlos conserva eventos y no revoca.

## Servidor Windows

No hay ZIP nuevo. El ZIP 0.7 conserva APK anteriores: usar los enlaces separados.
El ZIP original pasó análisis Defender sin hallazgos en el entorno documentado;
no hay confirmación del proveedor de falso positivo. Si Defender vuelve a bloquearlo,
mantener el bloqueo sin restaurarlo ni añadir exclusiones. El informe histórico
no cubre este APK nuevo. [Informe](REVISION-DEFENDER-0.7.md).

Versiones anteriores conservadas como historial.
