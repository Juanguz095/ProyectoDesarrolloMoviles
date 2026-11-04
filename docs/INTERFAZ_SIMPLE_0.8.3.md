# Interfaz simple y activación única — 0.8.3

4 de noviembre de 2026, America/Lima. Agente código18 y Centro código16.
Cambios de cliente sobre el servidor 0.8.1/V11 existente: no requiere migración.
Estado y evidencias actuales: [AVANCE](AVANCE.md).

## Agente

La pantalla principal muestra estado, batería, última transmisión y funciones.
Un único **Activar** abre la selección y una confirmación **Activar lo seleccionado**.
Con monitoreo en ejecución, el control principal es **Detener todo**. Para cambiar
las funciones, detener y volver a activar: no hay botones de dos modos que se
anulen entre sí. El texto manual conserva su acción independiente por cada envío.

Batería/conexión son el alcance base. Cada fuente sensible empieza sin marcar:
SMS/llamadas, números, texto SMS, notificaciones WhatsApp y pantalla. Al desmarcar
SMS/llamadas se eliminan también números/texto de esa selección. Las decisiones
pendientes viven en RAM; no se restauran como una autorización tras perder proceso.

La confirmación conduce por los permisos necesarios: notificaciones, SMS/llamadas,
acceso Android a notificaciones WhatsApp y MediaProjection para pantalla. Denegar
uno no concede el resto. La pantalla informa funciones activas, detenidas o sin
permiso; nunca presenta que todas estén activas por iniciar solo la batería.
El estado activo es local, no prueba recepción en el servidor.

**Detener todo** cierra la visibilidad inmediatamente, cancela una activación aún
pendiente de permisos, detiene servicios y bloquea recuperación de batería. Conserva
la inscripción y eventos previamente capturados; no equivale a retirar consentimiento.
La retirada sigue accesible en Detalles y ajustes y requiere confirmación.

La recuperación opcional al encender guarda solo el alcance batería/conexión. No
reactiva SMS, llamadas, WhatsApp ni pantalla. Puede configurarse al activar una
sesión con comunicaciones sin sustituirla por el servicio básico. Se conservan
las comprobaciones de inscripción, consentimiento y visibilidad del arranque.

La política, los errores por fuente, la conexión, las actualizaciones y las
herramientas de prueba pasan a **Detalles y ajustes**. Los avisos sobre qué datos
se comparten y su conservación permanecen visibles durante la autorización.

## Centro

Inicio conserva el equipo, lectura con antigüedad y actividad reciente. Eventos
mantiene pestañas, filtros plegados, exportación y detalle. Las vistas previas
WhatsApp están en su pestaña o en «Ver WhatsApp en vivo»; Todo prioriza el historial. Las explicaciones de
privacidad y aspectos técnicos se despliegan al solicitarlas. El consentimiento y
las actualizaciones de un equipo se consultan desde sus secciones plegables.
Las lecturas antiguas y errores de red siguen visibles; no se sustituyen por un
estado conectado inventado. El detalle de Compartidos muestra el texto completo.

## Validación pendiente

Las pruebas Compose/Robolectric verifican navegación, selección explícita y
cancelación, sin sustituir permisos/servicios reales. El recorrido de activación
unificada y las recepciones reales de SMS/WhatsApp necesitan validación nativa.
No se considera resuelto el reporte previo de SMS sin esa evidencia. No borrar
apps, bases o credenciales para instalar/probar esta revisión; conservar firma y datos.
