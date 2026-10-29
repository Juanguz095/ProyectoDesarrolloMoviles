# AuditLab 0.4.1 — interfaz y actualización de batería/eventos

## Instalar

Actualiza conservando los datos:

- [Agente 0.4.1](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.4.1-preview.apk) en el teléfono supervisado.
- [Centro 0.4.1](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.4.1-preview.apk) en el teléfono desde el que consultas.

Puedes conservar el servidor **0.4.0**, su dirección HTTPS y la base de datos. No
hay migraciones ni cambios de contrato. El servidor 0.3 necesita actualizarse a
0.4.0 según la [guía Windows](INICIO_WINDOWS_0.4.md). No desinstales las apps ni
borres su almacenamiento para actualizar. Ambas APK mantienen la firma debug.

## Activar batería y eventos en el Agente

1. Abre el Agente. Debe estar inscrito y mostrar el indicador visible.
2. En **Batería y eventos del teléfono**, marca la autorización y pulsa
   **Autorizar e iniciar captura real**.
3. Responde a los permisos Android que necesites. La batería y la conexión no
   requieren conceder SMS ni llamadas.
4. Comprueba que indique **CAPTURA EN EJECUCIÓN**. El porcentaje y la hora de la
   última lectura aparecen en **Estado que comparte este teléfono**.
5. Para solicitar una lectura adicional, pulsa **Enviar estado ahora**. La
   confirmación de solicitud no equivale a confirmar recepción en el servidor;
   revisa los pendientes y los avisos de conexión.

El Agente observa cambios de batería y reconcilia cada 10 segundos mientras la
captura visible esté activa. Cuando el estado no cambia, genera una lectura al
menos cada minuto de ejecución del servicio. Android, batería y conectividad
pueden retrasar el envío: no es una garantía de tiempo real.

## Consultar en el Centro

- En **Inicio**, selecciona un dispositivo si tienes varios. Verás batería,
  conexión, fecha de lectura y actividad reciente. **Ver equipo** abre sus acciones.
- Deja **Automático** activado para consultar cada 10 segundos mientras la app
  está en primer plano, o pulsa **Actualizar ahora**.
- **Última consulta** indica cuándo respondió el servidor. **Lectura del teléfono**
  indica cuándo el Agente registró ese estado: son fechas distintas.
- Una lectura de más de dos minutos muestra un aviso de antigüedad. Un fallo de
  red conserva los últimos datos y muestra el error; no cambia la hora de consulta
  exitosa ni inventa un nuevo porcentaje.

Actualizar el Centro consulta la información ya recibida; el propietario activa
la captura desde el Agente. Si el Agente está pausado, sin red o fue detenido por
Android, actualizar el Centro no puede obtener una lectura nueva.

## Leer eventos

**Eventos** muestra tarjetas con títulos como SMS recibido o Llamada perdida,
fecha/hora local, dispositivo, marca real/simulada y detalle. Las lecturas de estado
muestran también porcentaje y conexión. Se agrupan por día del teléfono del Centro.

Pulsa **Filtrar eventos** para elegir dispositivo, categoría y rango. Los rangos
de consulta mantienen UTC y lo indican expresamente. El CSV usa los últimos filtros
aplicados. Los borradores de filtros permanecen al actualizar la lista.

La actualización automática actúa en la primera página. En páginas posteriores se
pausa para que puedas leer sin saltos; **Actualizar ahora** conserva los filtros y
regresa a la primera página con los resultados más recientes.

## Comprobaciones de esta revisión

Ambas APK compilan y lint termina sin errores, con avisos. La auditoría estática
de artefactos comprueba firma, permisos, configuración de red y clases. Se revisaron
visualmente las pantallas iniciales en emuladores, sin inscribir dispositivos.

No se ejecutaron pruebas automáticas ni el flujo funcional completo de 0.4.1.
Los resultados de 0.4.0 corresponden a esa revisión anterior. Pendiente validar
las lecturas y eventos entre dos dispositivos, recuperación de red, segundo plano,
permisos, paginación y regresión de las funciones opcionales.
