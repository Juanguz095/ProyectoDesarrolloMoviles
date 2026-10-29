# AuditLab — apps 0.7.1

## Corrección de WhatsApp

- Señal de actividad cada 8 segundos independiente de la cola de eventos.
- Recuperación de notificaciones publicadas durante la conexión inicial, siempre
  posteriores al consentimiento local. Sin importar historial anterior.
- Compatibilidad con notificaciones de mensaje sin estilo completo.
- Agente muestra avisos observados, eventos reconocidos, textos enviados y señal
  confirmada. Centro permite elegir teléfono y actualizar WhatsApp directamente.

| Componente | Descargar |
| --- | --- |
| Agente 0.7.1 | [APK Agente](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.7.1-preview.apk) |
| Centro 0.7.1 | [APK Centro](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.7.1-preview.apk) |

**Instala los dos como actualización conservando sus datos.** Compatibles con
servidor 0.7. El ZIP Windows 0.7 conserva las apps anteriores; usa estos APK para
esta corrección. No se publica un ZIP Windows nuevo con este cambio.

[Activación y límites](CORRECCION-WHATSAPP-0.7.1.md) ·
[Pasos Windows](PASOS-SIN-USB.md) · [SHA256 de las apps](SHA256SUMS-0.7.1-preview) ·
[Registro](ENTREGA-0.7.1-preview.json)

## Comprobar WhatsApp

En el Agente reinicia la sesión opcional autorizada con indicador visible. Recibe
un mensaje nuevo con WhatsApp en segundo plano. En el Centro abre Eventos →
WhatsApp, selecciona ese teléfono y pulsa Actualizar WhatsApp. Texto disponible
solo si Android lo publica en la notificación; no se capturan todos los envíos
escritos dentro del chat. Texto entrante efímero: 20 vistas previas/5 minutos, sin
Room/SQL/CSV. Las respuestas observadas solo comparten tipo/fecha.

Compilación, lint, auditoría estática y firma de actualización correctos. No se
han ejecutado suites automáticas ni confirmado la recepción real en teléfonos
para esta revisión. No se declara que sea la causa confirmada del teléfono del
usuario. [Estado](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/AVANCE.md).

## Servidor Windows 0.7: revisión separada

El ZIP original 0.7 se analizó con Defender en Windows Server 2022, con análisis
ZIP/zona Internet y sin exclusiones de cobertura: no se detectaron amenazas.
No se confirmó un falso positivo del proveedor. Si Defender vuelve a bloquearlo,
conserva el bloqueo sin restaurar ni añadir exclusiones. Ese informe corresponde
al ZIP 0.7 y no es una validación antivirus de estos APK nuevos.

[Informe Windows](REVISION-DEFENDER-0.7.md) · [SHA256 anteriores](SHA256SUMS-0.7.0-preview).
Versiones anteriores conservadas como historial.
