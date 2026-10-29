# AuditLab 0.7 — texto de SMS y sesiones de WhatsApp

## Cambios

- SMS muestra **De/Para**, texto autorizado y hora. Un número como 515 identifica
  al remitente/destinatario, no al contenido del mensaje. El contador dice eventos,
  para no llamar recibidos a los enviados.
- Casilla local adicional, inicialmente desmarcada, para texto de SMS nuevos
  enviados y recibidos. READ_SMS e indicador visible obligatorios. No importa
  historial anterior, RCS, agenda ni audio. No empareja respuestas por suposición.
- Máximo 2 000 caracteres por SMS; si se supera se indica el recorte. La tarjeta
  muestra un resumen y Ver detalle el texto compartido. Si falta, se indica
  que no está compartido o disponible. Los registros antiguos no se completan.
- El texto autorizado se guarda en Room y PostgreSQL y puede exportarse en CSV
  por administradores/auditores. Retención de eventos: local hasta 7 días/10 000
  registros y servidor 30 días por defecto, configurable. Puede contener datos
  sensibles; la autorización lo explica. No se imprimen metadatos en toString.
- Lotes limitados también por tamaño para evitar rechazar muchos SMS largos.
  V9 valida campos, longitud, categoría SMS y origen real sin borrar datos previos.

## WhatsApp

Se corrigen dos rutas de interrupción en el código: se renueva la sesión también
cuando se observan respuestas salientes y se separa el envío de la cola persistente
del envío de vistas previas. Un fallo de esa cola se gestiona con WorkManager.
Las operaciones de vistas previas/heartbeat reintentan errores transitorios hasta
tres veces dentro de la misma sesión. No reinician captura ni crean sesiones nuevas.
Errores definitivos detienen el servicio y muestran una causa y acción específica:
red, TLS, HTTP 400/401/403/404/409/429/5xx. No se muestran cuerpos ni credenciales.

Esto no demuestra que fueran la causa del teléfono del usuario. /ready accesible
solo demuestra disponibilidad; las rutas de captura requieren autenticación propia.
No se declara resuelta la recepción real hasta comprobarla en un teléfono.

WhatsApp continúa limitado a notificaciones nuevas autorizadas. Los mensajes
escritos dentro de un chat abierto normalmente no son observables. El texto
entrante sigue en memoria: 20 vistas previas/5 minutos, fuera de Room, SQL y CSV.
Las respuestas propias observadas guardan tipo/fecha, sin compartir su texto.
No hay historial completo ni confirmación de entrega/lectura.

## Descargar y actualizar

- [APK Agente 0.7](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.7.0-preview.apk).
- [APK Centro 0.7](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.7.0-preview.apk).
- [Servidor Windows 0.7](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.7.0-preview.zip).

1. Detén la API con Ctrl+C. Conserva `.auditlab-windows`, claves y datos/volúmenes
   Docker. Respalda la carpeta `api` anterior y extrae todo el ZIP nuevo en la misma
   ubicación de instalación.
2. En CMD, desde esa carpeta, ejecuta:

   ```cmd
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\Iniciar-AuditLab.ps1"
   ```

3. Se aplica V9 conservando datos/configuración. Mantén la ventana abierta y
   verifica la misma URL HTTPS terminada en /ready en ambos teléfonos.
4. Instala ambos APK como actualización sin borrar datos. No cambies URL ni cuenta.
5. En el Agente, detén captura real, lee y marca **Autorizo compartir el texto de
   SMS nuevos enviados y recibidos** y vuelve a iniciar con permiso Android.
   El número tiene su casilla independiente. Solo se capturan eventos posteriores.
6. Envía y recibe un SMS nuevo entre teléfonos propios. Consulta Eventos → SMS.
7. WhatsApp: vuelve a autorizar/iniciar su sesión visible, recibe una notificación
   nueva y consulta Eventos → WhatsApp; selecciona un teléfono si hay varios.
   Si se detiene, conserva el aviso específico para diagnosticar la causa.

Retirar consentimiento detiene las capturas/envíos y limpia la cola local según
la política existente. No borres ni reinscribas dispositivos para intentar arreglar
un error de conexión sin identificarlo primero.

## Validación

Entrega de desarrollo: ambas apps/API compiladas, lint, contrato y auditoría estática.
Se registran por separado migración de desarrollo y descargas verificadas. No se
han ejecutado suites automáticas en esta revisión ni se ha comprobado recepción
real de WhatsApp, SMS de operador o Windows. APK con firma debug. Los resultados
históricos no validan esta ampliación.
