# Guía de trabajo para agentes — AuditLab

## Documentos obligatorios al comenzar

1. Lee [el plan maestro](docs/PLAN_MAESTRO.md).
2. Lee [el registro de avance](docs/AVANCE.md), especialmente la tarea siguiente y los bloqueos.
3. Revisa `git status --short --branch`, el código afectado y las instrucciones de su componente. Conserva los cambios existentes, incluidos archivos sin seguimiento.
4. Consulta [el contrato](contracts/openapi.yaml) antes de cambiar una interacción entre componentes.

El plan describe el producto objetivo. El registro contiene el estado real; no trates las funciones descritas como funciones verificadas.

## Cómo ejecutar el plan

- Cuando el usuario solicite continuar el desarrollo, toma la primera tarea pendiente cuyos requisitos estén cumplidos. El punto inicial es `P0.1` mientras el registro no demuestre otra situación.
- Implementa tareas completas con sus comprobaciones y evidencias. Después continúa con la siguiente tarea disponible dentro del alcance autorizado.
- No solicites confirmación para decisiones rutinarias ya cubiertas por el plan. Registra las decisiones técnicas y sus motivos.
- Ante un bloqueo, identifica el comando o requisito concreto, registra la causa y avanza en trabajo independiente. Pide únicamente el dato, acceso o decisión que realmente falte.
- Actualiza `docs/AVANCE.md` al cerrar una tarea y al finalizar cada sesión. Nunca marques una fase terminada sin todas sus evidencias.
- En una petición de documentación, modifica la documentación solicitada; las tareas futuras del plan no se ejecutan automáticamente por haberlo leído.

## Requisitos que deben conservarse

- Producto final: agente Android + centro de control Android + API Ktor/PostgreSQL con HTTPS. La web/Python actual es un prototipo local de referencia.
- Una herramienta ausente requiere investigar su instalación y compatibilidad. No justifica reemplazar silenciosamente las apps Android por una web.
- Alcance base: eventos simulados, eventos propios de AuditLab y metadatos permitidos; sin contenido de SMS ni llamadas. Las ampliaciones expresamente autorizadas posteriores son `docs/CAPTURA_REAL.md`, `docs/COMPARTIR_PANTALLA.md` y `docs/WHATSAPP_NOTIFICACIONES.md`: requieren permisos/consentimiento adicionales, indicador visible y detención local. La última admite solo texto de notificaciones nuevas de WhatsApp/Business, en sesiones efímeras, sin acceso a bases de chats ni historial. No extender esta excepción a otras aplicaciones o captura oculta.
- Consentimiento explícito, monitoreo visible, revocación accesible, retención y borrado desde el diseño.
- Autorización en el servidor, aislamiento por dispositivo, idempotencia y auditoría de acceso son requisitos funcionales.
- Usa bibliotecas mantenidas para autenticación y criptografía; no lleves el JWT artesanal del prototipo al backend definitivo.

## Comprobaciones y entrega

- Ejecuta las comprobaciones pertinentes a cada tarea. Conserva el código de salida y los resultados reales; diferencia aprobado, fallido, omitido y no ejecutado.
- Usa datos ficticios y ubicaciones temporales para comprobar migraciones, retención, concurrencia y recuperación. No borres datos existentes para lograr una comprobación satisfactoria.
- Si el código Android no se compila o no se prueba en un dispositivo/emulador compatible, registra esa limitación. No declares el APK validado.
- No registres contraseñas, tokens, códigos de inscripción ni valores de secretos en Git, evidencias o respuestas. Verifica presencia de variables sin imprimir sus valores.
- Mantén el prototipo restringido a loopback hasta disponer del servicio definitivo con HTTPS.
- Usa el checkout existente. No crees un worktree salvo petición expresa. No sobrescribas cambios ajenos ni restablezcas el repositorio para facilitar la tarea.
- Publicar en GitHub, desplegar o publicar el entorno exige autorización vigente para esa acción. Guardar instrucciones o preparar archivos no demuestra publicación.

La respuesta final de una sesión debe indicar tareas realizadas, comprobaciones, limitaciones y tarea siguiente, usando los identificadores del plan.
