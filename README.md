# AuditLab

AuditLab es un sistema de monitoreo autorizado para dispositivos Android propios o administrados. Este MVP usa eventos simulados y metadatos explícitamente permitidos; no lee SMS, llamadas, chats ni notificaciones.

Para continuar el desarrollo, sigue el [plan maestro por fases](docs/PLAN_MAESTRO.md) y el [registro de avance](docs/AVANCE.md). Las instrucciones para el agente están en [AGENTS.md](AGENTS.md). El registro distingue el código existente de las capacidades comprobadas.

## Primera entrega académica

**Implementación I01–I12 completada y entrega técnica verificada en Android 9 (API28).** La app local permite aceptación explícita, cuatro eventos SIMULADOS, historial SQLite, rotación, exportación CSV, borrado y revocación con recuperación de fallos. Versión **0.1.0-i12/12**.

Resultado final: **93 JVM, 15 SQL host, 23 de herramientas y 55 Android aprobadas**, sin fallos/errores/omitidas; lint debug/release 0. Nueve comprobaciones reales adicionales verifican selector/rotación/CSV, muerte de proceso, borrado y revocación con DELETE fallido/reintento. Se corrigieron errores de incrementos anteriores y se repitieron sus regresiones.

- [Entrega e instalación](docs/ENTREGA_ACADEMICA.md), [manual de uso](docs/USO_ACADEMICO.md).
- [Informe final I12 y hashes](docs/evidencias/academico/I12-2026-10-23.md), [seguridad/SSDF](docs/SEGURIDAD_ACADEMICA.md).
- [Plan académico](docs/PLAN_ACADEMICO.md), [avance y límites](docs/AVANCE_ACADEMICO.md).

**Android16 sigue sin validación completa:** un tercer intento también quedó bloqueado por un ANR del sistema del emulador; requiere una instancia estable con KVM o un teléfono compatible. Sync de Android Studio, teléfono físico y PDF/rúbrica siguen pendientes de evidencia propia. El APK entregado es debug firmado; release se compila/revisa sin firma de distribución. La fase 2 sigue fuera del alcance.

El plan maestro de dos apps/API que sigue pertenece al trabajo previo; sus módulos se conservan como referencia y no forman parte de la implementación autorizada de esta entrega local.

## Producto objetivo

- **Agente Android:** consentimiento revocable, notificación visible y eventos simulados/locales.
- **Centro de control Android:** consulta con roles, exportación y revocación.
- **API HTTPS:** inscripción, recepción y consulta de eventos, revocación y auditoría.

## MVP disponible en este repositorio

Incluye una API local en Python/SQLite y una interfaz de navegador para recorrer los flujos del agente A y del centro de control B. Su contrato es [prototype-v0.1.yaml](contracts/prototype-v0.1.yaml); la guía para ejecutarlo está en [backend/api/README.md](backend/api/README.md). Las pantallas son una simulación de desarrollo, no apps Android instalables. [OpenAPI 0.2](contracts/openapi.yaml) corresponde a las interfaces definitivas de Ktor, todavía en implementación.

La base definitiva tiene módulos Android en `apps/agent` y `apps/control`, y un servicio Ktor en `backend/ktor` con salud, disponibilidad PostgreSQL, migraciones y bootstrap seguro de cuentas y [login/refresh/logout HTTP](docs/autenticacion.md). Las pantallas nativas son informativas; la inscripción, el login Android, los eventos y la sincronización todavía deben implementarse. Los comandos y versiones están en la [guía de desarrollo](docs/desarrollo.md); los resultados reales se registran en [AVANCE.md](docs/AVANCE.md).

Los requisitos, estados e historias del producto están definidos en [requisitos](docs/requisitos.md), [estados](docs/estados.md) e [historias de usuario](docs/historias.md).

## Límites de seguridad del MVP

- No se recopilan datos antes de un consentimiento vigente.
- La captura se limita a eventos propios de AuditLab y metadatos permitidos.
- La revocación detiene nuevas transmisiones.
- Las operaciones de consulta y administración requieren autenticación y generan auditoría.
- No se incluyen permisos de SMS, llamadas, contactos, micrófono ni accesibilidad.

## Próximo hito del sistema ampliado (fuera de la entrega actual)

Implementar inscripción con código de un uso y consentimiento (P3.2), según el [contrato 0.2](docs/contrato.md), sobre el [modelo y migraciones PostgreSQL](docs/base-datos.md). Después, recepción/consulta/revocación y conexión de ambas apps. El estado de publicación y las comprobaciones se registran en [AVANCE.md](docs/AVANCE.md).

## Alcance y limitaciones del prototipo

El recorrido del sistema ampliado previo pertenece al prototipo Python/web: escucha en loopback, usa SQLite y autenticación simplificada. En ese prototipo faltan HTTPS, refresh tokens HTTP y controles completos contra abuso. Su flujo Android de dos apps/API sigue pendiente; es distinto de la app académica local ya funcional. No expongas el prototipo a una red. Ktor tiene infraestructura de BD, creación de cuentas por CLI y autenticación HTTP con refresh; todavía no acepta eventos. El plan maestro define lo necesario antes de un piloto real.
