# AuditLab — descargas actuales

| Componente | Descargar |
| --- | --- |
| Agente 0.7.1 | [APK Agente](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.7.1-preview.apk) |
| Centro 0.7.4 | [APK Centro](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.7.4-preview.apk) |
| Servidor Windows 0.7 | [ZIP original revisado](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.7.0-preview.zip) |

## Alertas WhatsApp sin Firebase

Equipos → Ver dispositivo → Activar alertas para este teléfono. Permitir
notificaciones y mantener la sesión autorizada en el Agente. Servicio visible
que consulta cada 5 segundos, con texto recibido y botón Detener. Una fuente
por vez. Tocar un aviso abre Eventos del equipo. Sin captura de envíos del chat.
Android puede interrumpirlo por batería/Doze o límites dataSync; sin reinicio
oculto ni garantía de avisos permanentes. No usa Firebase ni cambia el servidor.

## Identificar y archivar equipos

Equipos → Ver dispositivo → Cambiar nombre o Archivar dispositivo. Los nombres y
el archivo permanecen en este Centro, separados por servidor e inscripción.
Equipos → Mostrar → Archivados permite restaurarlos. Se muestran inscripción y
último contacto; contacto reciente significa como máximo 2 minutos.
Archivar conserva los eventos y permite que sigan llegando; Revocar bloquea envíos.
Los cambios locales no se sincronizan con otros Centros.

## WhatsApp en Eventos

Actualizar el Centro sobre la instalación anterior. Compatible con Agente 0.7.1
y servidor 0.7, sin volver a inscribir ni cambiar la URL.

En Eventos → Todo o WhatsApp aparecen las vistas previas de notificaciones
entrantes autorizadas, con título, texto expandible y hora. Si hay varios teléfonos,
seleccionar uno. Usar sin filtros de fecha o ID para ver texto en vivo.

Requiere permiso de notificaciones, indicador visible y sesión autorizada localmente
en el Agente. No obtiene el texto enviado dentro del chat ni todo el historial.
Texto temporal: 20 vistas previas/5 minutos, fuera de Room/PostgreSQL/CSV.
Historial persistente de actividad solo contiene tipo/fecha para WhatsApp.

[Guía de la actualización](ALERTAS-WHATSAPP-0.7.4.md) ·
[Instalación Windows](PASOS-SIN-USB.md) ·
[Registro Centro 0.7.4](ENTREGA-0.7.4-preview.json) ·
[SHA256 Centro](SHA256SUMS-0.7.4-preview) ·
[SHA256 Agente](SHA256SUMS-0.7.1-preview).

Compilación, lint, revisión estática y firma de actualización correctos. 20 pruebas unitarias/Robolectric aprobadas; sin recepción real en teléfonos para esta revisión.

## Servidor Windows

No hay ZIP nuevo. El ZIP 0.7 contiene APK anteriores: usar los enlaces separados
actuales. El ZIP original pasó un análisis Defender en Windows Server 2022 sin
hallazgos; no hay confirmación del proveedor de falso positivo. Si Defender vuelve
a bloquearlo, mantener el bloqueo sin restaurarlo ni añadir exclusiones.
[Informe](REVISION-DEFENDER-0.7.md). El análisis histórico no cubre este APK nuevo.

Versiones anteriores conservadas como historial.
