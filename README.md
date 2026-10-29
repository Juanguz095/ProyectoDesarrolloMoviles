# AuditLab — Descargas 0.7

## Revisión de Defender completada — 29/10/2026

El ZIP original fue analizado en Windows Server 2022 con Defender activo, análisis
de ZIP habilitado, marca de zona Internet y sin exclusiones que cubran el archivo.
Motor **1.1.26100.4**, firmas **1.463.16.0**, código de salida **0**, sin amenazas
detectadas. Se mantiene el mismo archivo y SHA256; no se renombró ni reempaquetó.

La alerta reportada no se reprodujo. No hay confirmación del proveedor de falso
positivo ni garantía para otras versiones/configuraciones del antivirus.
**Mantén Defender activo. Si vuelve a bloquearlo, déjalo bloqueado; no restaures
ni añadas exclusiones.**

[Informe Windows](DEFENDER-WINDOWS-40825372845.json) ·
[Resolución y alcance](DEFENDER-RESOLUCION-0.7-2026-10-29.json) ·
[Ejecución](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/actions/runs/40825372845)

| Componente | Descargar |
| --- | --- |
| Agente | [APK Agente](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.7.0-preview.apk) |
| Centro | [APK Centro](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.7.0-preview.apk) |
| Servidor Windows y apps | [ZIP revisado](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.7.0-preview.zip) |

Android 8.0 o superior. SMS nuevo con texto opcional autorizado, De/Para y hora;
WhatsApp limitado a notificaciones nuevas en sesión local autorizada. El texto
SMS usa retención/exportación; el texto WhatsApp permanece temporal. No se ofrece
historial completo de chats ni detección de todos los envíos dentro de ellos.

[Instalación sin USB](PASOS-SIN-USB.md) · [Activación y cambios](ACTUALIZACION-0.7.md) ·
[SHA256](SHA256SUMS-0.7.0-preview)

Conserva `.auditlab-windows`, claves, datos Docker y datos de las apps al actualizar.
No necesitas cambiar la URL ni la cuenta. Las APK mantienen firma debug. La revisión
antimalware no demuestra el recorrido funcional en teléfonos; ese estado está en
[AVANCE](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/AVANCE.md).

Versiones anteriores conservadas como historial, sin revisión antivirus implícita.
