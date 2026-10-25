# AuditLab

AuditLab es un sistema de monitoreo autorizado para dispositivos Android propios o administrados. Este MVP usa eventos simulados y metadatos explícitamente permitidos; no lee SMS, llamadas, chats ni notificaciones.

Para continuar el desarrollo, sigue el [plan maestro por fases](docs/PLAN_MAESTRO.md) y el [registro de avance](docs/AVANCE.md). Las instrucciones para el agente están en [AGENTS.md](AGENTS.md). El registro distingue el código existente de las capacidades comprobadas.

## Proyecto completo: alcance activo

El usuario ratificó el 24 de octubre que el objetivo es completar **agente Android + API Ktor/PostgreSQL + centro de control Android**. La guía vigente es [PLAN_MAESTRO.md](docs/PLAN_MAESTRO.md), fases 0–8. Estado del avance remoto hasta `a17dffd`: P0.1–P0.3 y P3.1–P3.6 cerradas según sus evidencias; P4.1–P4.3 implementadas con pruebas JVM, pendientes de ejecución real en emulador/dispositivo. El siguiente trabajo es reconstruir el fixture fuera de `/tmp` y comprobar ese recorrido con un emulador API30 de 2 GiB. El primer MVP integrado se acredita al cerrar la fase 4 con un evento enviado desde el agente y consultado desde el centro.

Las pruebas de la app académica descritas a continuación acreditan esa app local. La validación del proyecto completo debe demostrar el recorrido agente → API → centro, además de la revocación.

## Primera entrega académica

**Implementación I01–I12 completada y app verificada en Android 8 (API26) y Android 9 (API28).** La app local permite aceptación explícita, cuatro eventos SIMULADOS, historial SQLite, rotación, exportación CSV, borrado y revocación con recuperación de fallos. Versión **0.1.0-i12/12**.

Resultado final: **93 JVM, 15 SQL host, 23 de herramientas y 55 Android aprobadas**, sin fallos/errores/omitidas; lint debug/release 0. Nueve comprobaciones reales adicionales verifican selector/rotación/CSV, muerte de proceso, borrado y revocación con DELETE fallido/reintento. Se corrigieron errores de incrementos anteriores y se repitieron sus regresiones.

- [Entrega e instalación](docs/ENTREGA_ACADEMICA.md), [manual de uso](docs/USO_ACADEMICO.md).
- [Informe final I12 y hashes](docs/evidencias/academico/I12-2026-10-23.md), [seguridad/SSDF](docs/SEGURIDAD_ACADEMICA.md).
- [Prueba adicional en dos emuladores](docs/evidencias/academico/DOS-EMULADORES-2026-10-23.md): **55/55 API26 y 55/55 API28**, sin fallos/errores/omitidos, con la misma APK I12.
- [Plan académico](docs/PLAN_ACADEMICO.md), [avance y límites](docs/AVANCE_ACADEMICO.md).

**Android16 sigue sin validación completa:** los tres intentos históricos registraron ANR del sistema; otro intento con un AVD nuevo se interrumpió durante la instalación tras inestabilidad en el arranque, sin llegar a ejecutar Instrumentation. Requiere una instancia estable con KVM o un teléfono compatible. Sync de Android Studio, teléfono físico y PDF/rúbrica siguen pendientes de evidencia propia. El APK académico entregado es debug firmado; release se compila/revisa sin firma de distribución.

Los módulos `apps/agent`, `apps/control` y `backend/ktor` forman la ruta activa del proyecto completo. La app `academic/` se conserva como entrega local y fuente de reglas y regresiones útiles.

## Producto objetivo

- **Agente Android:** consentimiento revocable, notificación visible y eventos simulados/locales.
- **Centro de control Android:** consulta con roles, exportación y revocación.
- **API HTTPS:** inscripción, recepción y consulta de eventos, revocación y auditoría.

## MVP disponible en este repositorio

Incluye una API local en Python/SQLite y una interfaz de navegador para recorrer los flujos del agente A y del centro de control B. Su contrato es [prototype-v0.1.yaml](contracts/prototype-v0.1.yaml); la guía para ejecutarlo está en [backend/api/README.md](backend/api/README.md). Las pantallas son una simulación de desarrollo, no apps Android instalables. [OpenAPI 0.2](contracts/openapi.yaml) corresponde a las interfaces definitivas de Ktor, todavía en implementación.

La base definitiva tiene módulos Android en `apps/agent` y `apps/control`, un módulo Kotlin compartido en `apps/common` y un servicio Ktor en `backend/ktor` con salud, disponibilidad PostgreSQL, migraciones, bootstrap seguro de cuentas, [autenticación HTTP](docs/autenticacion.md), inscripción, ingesta, consultas, CSV, revocación y retención ([despliegue](docs/despliegue.md)). Las dos apps ya incluyen consentimiento, inscripción, evento simulado, login, consultas y revocación, pero **no se han ejecutado en un emulador ni dispositivo compatible**: el recorrido integrado no está validado. Los comandos y versiones están en la [guía de desarrollo](docs/desarrollo.md); los resultados reales se registran en [AVANCE.md](docs/AVANCE.md).

Comprobaciones ejecutadas sobre el artefacto y el servicio reales, con sus resultados en la [evidencia de verificación](docs/evidencias/verificacion-2026-10-24.md):

- 8 pruebas unitarias y 53 de integración PostgreSQL de la API, 0 fallos, desde una base vacía.
- Concurrencia: 16 entregas del mismo `eventId` producen una sola fila; un código de inscripción se consume una sola vez aunque se intente 16 veces a la vez.
- 23 respuestas reales validadas contra [OpenAPI 0.2](contracts/openapi.yaml), 0 fallos.
- TLS nativo de Ktor comprobado con CA propia: el puerto cifrado rechaza HTTP en claro y el cliente JVM funciona con un truststore propio.
- 7 pruebas de UI Compose ejecutadas en la JVM con Robolectric y 23 del módulo común.
- Auditoría de los dos APK: sin permisos peligrosos, sin tráfico en claro fuera de loopback, firma válida.

Estas comprobaciones **no** sustituyen la ejecución en un dispositivo: no hay `/dev/kvm`, imagen de sistema ni teléfono conectado en este entorno.

Los requisitos, estados e historias del producto están definidos en [requisitos](docs/requisitos.md), [estados](docs/estados.md) e [historias de usuario](docs/historias.md).

## Límites de seguridad del MVP

- No se recopilan datos antes de un consentimiento vigente.
- La captura se limita a eventos propios de AuditLab y metadatos permitidos.
- La revocación detiene nuevas transmisiones.
- Las operaciones de consulta y administración requieren autenticación y generan auditoría.
- No se incluyen permisos de SMS, llamadas, contactos, micrófono ni accesibilidad.

## Próximo hito del sistema ampliado (fuera de la entrega actual)

Comprobar el recorrido integrado de la fase 4 (P4.1–P4.3) en un emulador o dispositivo compatible: agente con consentimiento e inscripción, evento visible en el centro y revocación en ambos sentidos. La implementación existe; la ejecución real sigue pendiente por falta de `/dev/kvm` e imagen de sistema. Después, persistencia y reintentos del agente (P5). El estado de publicación y las comprobaciones se registran en [AVANCE.md](docs/AVANCE.md).

## Alcance y limitaciones del prototipo

El recorrido del sistema ampliado previo pertenece al prototipo Python/web: escucha en loopback, usa SQLite y autenticación simplificada. En ese prototipo faltan HTTPS, refresh tokens HTTP y controles completos contra abuso. Su flujo Android de dos apps/API sigue pendiente de ejecución real; es distinto de la app académica local ya funcional. No expongas el prototipo a una red. Ktor ya implementa autenticación, inscripción, eventos, consultas y revocación sobre PostgreSQL, pero no se ha desplegado con TLS ni se ha validado el recorrido Android en un dispositivo. El plan maestro define lo necesario antes de un piloto real.
