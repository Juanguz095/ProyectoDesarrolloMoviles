# WhatsApp dentro de Eventos — Centro 0.7.2

29 de octubre de 2026, America/Lima. P6.2/P6.6/P6.4.

## Qué cambia

Centro 0.7.2-preview (código 11) muestra las vistas previas recibidas también en
**Eventos → Todo**, dentro de Resultados. Antes solo se veían al elegir WhatsApp.
Compatible con Agente 0.7.1 y servidor 0.7/V9: no exige volver a inscribir,
cambiar la URL ni reinstalar el servidor.

1. Instalar el Centro actualizado sobre la instalación anterior.
2. En el Agente mantener el indicador visible, el permiso Android de notificaciones
   y una sesión de vistas previas expresamente autorizada por su propietario.
3. Abrir Eventos → Todo o WhatsApp, sin filtros de fecha/identificador.
4. Elegir el teléfono si hay varios. Con uno activo se selecciona automáticamente.
5. Recibir una notificación nueva con WhatsApp en segundo plano. Las tarjetas
   muestran título de la notificación, texto y hora, ordenadas de más nueva a antigua.
   Ver mensaje completo expande el texto; Actualizar WhatsApp consulta de nuevo.

Con filtros de fecha o ID se muestra solo el historial de actividad, sin recuperar
texto anterior. Elegir otro teléfono cambia el filtro del historial y la sesión
consultada. Las categorías SMS/llamadas no solicitan vistas previas WhatsApp.

## Límites que se conservan

- Solo texto que Android entregue en notificaciones entrantes posteriores al
  consentimiento. No se obtiene el texto enviado dentro del chat, ni todo el historial.
- Las respuestas propias observadas conservan únicamente tipo/fecha.
- Últimas 20 vistas previas, hasta 5 minutos en memoria del servidor; no se guardan
  en Room/PostgreSQL ni se incluyen en CSV, ni cuentan como filas del historial.
- El Centro consulta cada 3 segundos mientras está en primer plano. Al salir de
  la vista/pasar al fondo/cerrar sesión se descarta el contenido local. FLAG_SECURE
  protege la pantalla durante la consulta. Detener la sesión elimina las vistas
  previas del servidor; la desaparición en el Centro depende de la siguiente consulta.
- No hay cambios en permisos, consentimiento, captura, API ni base de datos.

## Comprobaciones

Resultados de compilación, lint y firma en
[registro de esta revisión](evidencias/WHATSAPP-EVENTOS-0.7.2-2026-10-29.json).
No se atribuye a esta revisión una prueba funcional en teléfonos ni recepción real
de WhatsApp. Sigue pendiente P7.6 para la combinación de dispositivos del usuario.
