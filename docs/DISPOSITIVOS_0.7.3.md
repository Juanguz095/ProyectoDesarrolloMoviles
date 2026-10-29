# Identificar y archivar dispositivos — Centro 0.7.3

29 de octubre de 2026, America/Lima. P6.2/P6.4.

Compatible con Agente 0.7.1 y servidor 0.7. Actualizar únicamente el Centro,
conservando sus datos. No necesita cambiar la URL ni volver a inscribir.

- Equipos muestra nombre, fecha de inscripción y último contacto.
- Contacto reciente significa comunicación registrada hace como máximo 2 minutos;
  no garantiza conexión continua. Revocado, sin contacto registrado y sin contacto
  reciente son estados distintos de la inscripción activa.
- Equipos → Ver dispositivo → Cambiar nombre permite guardar de 1 a 60 caracteres.
- Archivar dispositivo pide confirmación y oculta el equipo de Inicio y de la
  lista habitual. Equipos → Mostrar → Archivados permite abrirlo y restaurarlo.
- Se conservan los eventos, sus identificadores y la auditoría. Archivar no revoca:
  el Agente puede seguir enviando. Para bloquear envíos, utilizar Revocar.
- Nombre y archivo se guardan **en este Centro**, separados por URL del servidor
  e identificador de inscripción. No se sincronizan con otros Centros y se pierden
  si se borran los datos de la app. No son permisos del servidor.
- Cada inscripción tiene su propio nombre/archivo; no se reasignan datos de equipos
  anteriores. Las tarjetas del historial muestran siempre el nombre del origen.
- Los filtros de inscripción y la paginación siguen activos: el archivo filtra la
  página cargada. Si no aparece un equipo, revisar el filtro y las páginas siguientes.
- Todos los dispositivos en Eventos continúa incluyendo los archivados. No se
  cambia automáticamente la consulta histórica al archivar un equipo.

Compilación, lint y auditoría APK aprobados. Dos pruebas Robolectric aprobadas:
persistencia/restauración/aislamiento por servidor y equipo/validación del nombre,
y límites del estado de contacto. Sin ejecución de esta revisión en teléfonos.
[Registro](evidencias/DISPOSITIVOS-0.7.3-2026-10-29.json).
