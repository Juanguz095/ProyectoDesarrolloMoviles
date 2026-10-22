# AuditLab — Avance de la fase 1 académica

Fecha de actualización: 22 de octubre de 2026, America/Lima.

Plan: [PLAN_ACADEMICO.md](PLAN_ACADEMICO.md). Propuesta inmediata: [PLAN_I01.md](PLAN_I01.md). Fuente de requisitos: [contexto del usuario](referencias/contexto-auditlab-2026-10-22.txt).

## Estado actual

- Última solicitud: «continua con las otras fasese hasta la 3»; se interpreta como autorización de I02 e I03 de esta ruta. Se ejecutan en orden; no se autoriza I04 o fase 2.
- Incrementos ejecutados en esta continuación: I02 e I03, verificados en JVM. I01 conserva sus pendientes IDE/Android.
- Artefacto: APK debug de `academic/app`, firma verificada, lint con 0 incidencias; la Activity sigue siendo la base de I01, sin botones conectados al dominio. [Evidencia I03](evidencias/academico/I03-2026-10-22.md).
- Incrementos con alcance JVM verificado: **2/12 (I02 e I03)**; I01 conserva las comprobaciones CLI y los pendientes IDE/Android. Suite acumulada: **48 pruebas satisfactorias**.
- Cierres aprobados tras revisión: **0/12**; la autorización de ejecución no equivale a aprobación de sus resultados.
- Próximo paso: revisar I02/I03 y completar Sync/apertura Android de I01. El siguiente incremento de código es I04 (CSV), pendiente de autorización; no se ha iniciado.
- Pendiente documental: PDF/prompt específico I01; el contexto textual ya fija versiones, límites y obligación de aprobación.
- Fase 2: fuera de autorización. El [avance previo](AVANCE.md) conserva el trabajo histórico del sistema ampliado y no acredita los incrementos de esta app.

## Seguimiento

| Incremento | Entregable | Estado técnico | Aprobación | Entrada principal |
| --- | --- | --- | --- | --- |
| I01 | Base app/domain y herramientas exigidas. | Implementado; CLI/lint verificados, IDE/Android pendientes. | Ejecución autorizada; cierre pendiente. | [Evidencia](evidencias/academico/I01-2026-10-22.md). |
| I02 | Modelo y contratos Kotlin puros. | Verificado JVM: 15 pruebas. | Ejecución autorizada; revisión pendiente. | Continuación explícita hasta I03; I01 conserva pendientes IDE/Android. [Evidencia](evidencias/academico/I02-2026-10-22.md). |
| I03 | AuditService, DI, límites 1000/100 y reglas. | Verificado JVM: 33 nuevas; 48 acumuladas. | Ejecución autorizada; revisión pendiente. | I02 verificado; continuación autorizada. [Evidencia](evidencias/academico/I03-2026-10-22.md). |
| I04 | CSV con CRLF, timestamps numéricos y protección de fórmulas. | Pendiente. | Pendiente. | I03 verificado y aprobación explícita para I04. |
| I05 | SQLite y pruebas instrumentadas. | Pendiente. | Pendiente. | I04 verificado y aprobación explícita para I05. |
| I06 | Aceptación persistida. | Pendiente. | Pendiente. | I05 verificado y aprobación explícita para I06. |
| I07 | Cuatro botones de eventos, ScrollView y marca SIMULADO. | Pendiente. | Pendiente. | I06 verificado y aprobación explícita para I07. |
| I08 | Cola compartida, busy, ciclo de vida, cierre BD y WindowInsets. | Pendiente. | Pendiente. | I07 verificado y aprobación explícita para I08. |
| I09 | Exportación manual elegida y controlada. | Pendiente. | Pendiente. | I08 verificado y aprobación explícita para I09. |
| I10 | Borrado y revocación persistente aun con fallo de borrado. | Pendiente. | Pendiente. | I09 verificado y aprobación explícita para I10. |
| I11 | Revisión de seguridad/SSDF. | Pendiente. | Pendiente. | I10 verificado y aprobación explícita para I11. |
| I12 | Evidencias y cierre de fase 1. | Pendiente. | Pendiente. | I11 verificado y aprobación explícita para I12. |

## Ejecución I03 del 22 de octubre de 2026

- AuditService con dependencias inyectadas y coordinación compartida: registra únicamente con aceptación vigente, aplica límites, devuelve fallos explícitos y permite borrar sin cambiar la aceptación.
- Revocación guardada antes del borrado; un fallo de limpieza mantiene la retirada y permite reintento. Un fallo al guardar la retirada se informa y bloquea la instancia actual, sin prometer persistencia inexistente.
- 33 pruebas nuevas y 15 de I02 aprobadas: **48/48**. Dos ejecuciones renovadas satisfactorias; build Android y lint con 0 incidencias. Concurrencia con dos servicios y repositorios en memoria; no son pruebas SQLite/Android.
- [Evidencia I03](evidencias/academico/I03-2026-10-22.md), [explicación del dominio](DOMINIO_ACADEMICO.md). Versiones y pantalla conservadas. Commit local separado; detención antes de I04. Borrador de arranque de nube actualizado, pendiente de publicación para activarse.

## Ejecución I02 del 22 de octubre de 2026

- Nueva solicitud autoriza continuar I02/I03 en orden. Aprobación expresa de kotlin.test2.2.10/JUnit4 recibida antes de añadirla.
- Modelos/contratos implementados; 15 tests JVM aprobados tras corregir inferencia de tipos de una aserción. Sin imports Android ni adaptador persistente.
- [Evidencia I02](evidencias/academico/I02-2026-10-22.md). Commit local; siguiente I03 ya autorizado. Se mantienen los pendientes IDE/Android de I01.

## Ejecución I01 del 22 de octubre de 2026

- Usuario autorizó comenzar la propuesta I01. Se crearon app/domain y Activity mínima en `academic/`, conservando apps/backend anteriores.
- Herramientas: Temurin17.0.21+9, Gradle9.3.1, SDK36/build-tools36.0.0; AGP9.1.1 y Kotlin JVM2.2.10 resueltos sin sustituciones. Checksums y TLS mantenidos.
- Build renovado satisfactorio, 47 tareas ejecutadas; lint XML final con 0 incidencias tras corregir tres advertencias de recursos base. Probe JVM temporal compila con las versiones exigidas y bytecode17.
- Domain sin fuentes/tests: 0 pruebas, NO-SOURCE. No se implementaron modelos o funcionalidades de I02–I12.
- APK debug generado, firma verificada y manifiesto sin permisos/servicios/receptores. Hash y tamaño en evidencia.
- Pendientes: Sync real en Android Studio, instalación/apertura en un dispositivo y contraste del prompt PDF. ADB falla por carpeta personal de solo lectura; emulador no instalado y KVM ausente. No se inventa una validación Android.
- Configuración de nube guardada en borrador confirmado: instalador y arranque académicos; ruta activa del instalador repetida con salida0. Sin publicación del entorno ni GitHub.
- Commit local de cierre conserva la base y su documentación; diff revisable en `.tooling/academic-setup/i01-review.diff`. Detención antes de I02 para revisión y aprobación explícita.

## Preparación documental del 22 de octubre de 2026

- Leídos el beta y el contexto posterior; copias originales conservadas en `docs/referencias/`.
- Se corrigió la propuesta según el contexto: cuatro eventos con dirección, Views/ScrollView, 100 visibles/1000 almacenados, timestamps numéricos, versiones obligatorias y aprobación entre incrementos.
- Revocación propuesta en dos etapas: persistir retirada antes de borrar; un fallo de DELETE conserva la retirada y permite reintento. No se trata de una funcionalidad implementada.
- Inspeccionado repositorio en revisión `118a738`, configuración y ubicaciones locales de herramientas. Preparados JDK 21/Gradle 8.11.1/SDK 35 del trabajo anterior; JDK 17 y SDK 36 no localizados en las rutas examinadas, Gradle 9.3.1 sin caché local encontrada. Inventario detallado en PLAN_I01.
- Documentación y propuesta solamente. Sin Build, Sync de IDE, pruebas de aplicación, instalación en Android, commit, push ni publicación de entorno en esta sesión.
- Comprobación documental: enlaces válidos, 12 incrementos únicos, 12 criterios AC y seguimiento completo. El checker general conserva 34 tareas/15 criterios del sistema previo y valida 25 documentos. No son pruebas de la app.
- Ningún incremento se marca aprobado por haber redactado su plan.

## Regla al continuar

Consultar el prompt específico del incremento y trabajar solo en el autorizado. Mostrar archivos y diff, comprobar lo aplicable, corregir, actualizar evidencia y detenerse para aprobación antes del siguiente. Si se solicita cambiar versiones, preparar primero la justificación y el cambio concreto para revisión.
