# AuditLab — Avance de la fase 1 académica

Fecha de actualización: 23 de octubre de 2026, UTC.

Plan: [PLAN_ACADEMICO.md](PLAN_ACADEMICO.md). Propuesta inicial: [PLAN_I01.md](PLAN_I01.md). Fuente de requisitos: [contexto del usuario](referencias/contexto-auditlab-2026-10-22.txt).

## Estado actual

- Última solicitud: «Continúa hasta acabarlo» y revisión/pruebas de fases anteriores. Autoriza I11/I12 en orden y correcciones necesarias de la app académica; fase 2 sigue fuera del alcance.
- Estado actual: **implementación I01–I12 y entrega técnica completadas sobre API28**. Se corrigieron y repitieron errores de fases anteriores; no quedan defectos reproducidos de la app abiertos en esa versión comprobada.
- Artefactos: debug firmado y APK de tests, versión **0.1.0-i12/12**; release sin firmar revisado, lint debug/release 0. [Entrega](ENTREGA_ACADEMICA.md), [manual](USO_ACADEMICO.md), [informe I12/hashes](evidencias/academico/I12-2026-10-23.md).
- Verificación final: **93 JVM, 15 SQL host, 23 herramientas y 55 Android API28 aprobadas**, sin fallos/errores/omitidas. **Nueve comprobaciones reales aprobadas** con selector/rotación, CSV exacto, cancelación, muerte de proceso, revocación con fallo de DELETE y reintento. Fixture original vacía/revocada restaurada y comparada; CSVs anteriores conservados.
- Revisión del usuario/profesor y conformidad de la rúbrica pendientes; la ejecución autorizada no equivale a esa revisión.
- Próximo paso: revisión de la entrega y evidencia externa de IDE/teléfono/rúbrica. API36 sigue sin validación completa tras tres intentos con ANR del sistema del emulador. No se inicia fase 2.
- Pendiente documental: PDF/prompts/rúbrica del curso; el contexto textual ya fija versiones, límites y obligación de aprobación.
- Fase 2: fuera de autorización. El [avance previo](AVANCE.md) conserva el trabajo histórico del sistema ampliado y no acredita los incrementos de esta app.
- Entorno Android: ADB resuelto e imágenes/AVDs API28/API36 instalados. API28 estable ejecutado aisladamente. API36 sufrió ANR de componentes del sistema en tres intentos. SwANGLE/4 GiB/pantalla reducida recuperó el almacenamiento, pero la prueba de privacidad volvió a bloquearse ante un ANR del sistema. Los procesos de prueba se detuvieron y sus AVDs se conservaron. La próxima comprobación de API36 requiere una instancia estable, preferiblemente con KVM, o un dispositivo compatible.

## Seguimiento

| Incremento | Entregable | Estado técnico | Aprobación | Entrada principal |
| --- | --- | --- | --- | --- |
| I01 | Base app/domain y herramientas exigidas. | CLI/lint y apertura real Android comprobados; Sync IDE pendiente. | Ejecución autorizada; revisión pendiente. | [I01](evidencias/academico/I01-2026-10-22.md), [validación integrada I10](evidencias/academico/I10-2026-10-22.md). |
| I02 | Modelo y contratos Kotlin puros. | Verificado JVM: 15 pruebas. | Ejecución autorizada; revisión pendiente. | Continuación explícita hasta I03; I01 conserva pendientes IDE/Android. [Evidencia](evidencias/academico/I02-2026-10-22.md). |
| I03 | AuditService, DI, límites 1000/100 y reglas. | Verificado JVM: 33 nuevas; 48 acumuladas. | Ejecución autorizada; revisión pendiente. | I02 verificado; continuación autorizada. [Evidencia](evidencias/academico/I03-2026-10-22.md). |
| I04 | CSV con CRLF, timestamps numéricos y protección de fórmulas. | Verificado JVM: 10 pruebas nuevas; 58 acumuladas. | Ejecución autorizada; revisión pendiente. | [Evidencia](evidencias/academico/I04-2026-10-22.md). |
| I05 | SQLite y pruebas instrumentadas. | 12 casos Android aprobados en versión integrada I08, tras corregir creación concurrente. | Ejecución autorizada; revisión pendiente. | [I05](evidencias/academico/I05-2026-10-22.md), [validación I08](evidencias/academico/I08-2026-10-22.md). |
| I06 | Aceptación persistida. | 17 JVM y 15 Android aprobados; reapertura/migración/fallos verificados en I08. | Ejecución autorizada; revisión pendiente. | [I06](evidencias/academico/I06-2026-10-22.md), [validación I08](evidencias/academico/I08-2026-10-22.md). |
| I07 | Cuatro botones de eventos, ScrollView y marca SIMULADO. | Implementado; casos UI aprobados en I08. | Ejecución autorizada; revisión pendiente. | [I07](evidencias/academico/I07-2026-10-22.md), [validación I08](evidencias/academico/I08-2026-10-22.md). |
| I08 | Cola compartida, busy, ciclo de vida, cierre BD y WindowInsets. | 5 JVM nuevas y 6 UI Android nuevas aprobadas. | Ejecución autorizada; revisión pendiente. | [Evidencia](evidencias/academico/I08-2026-10-22.md). |
| I09 | Exportación manual elegida y controlada. | 13 JVM, 7 casos Android y selector real/CSV verificados en I10. | Ejecución autorizada; revisión pendiente. | [I09](evidencias/academico/I09-2026-10-22.md), [validación I10](evidencias/academico/I10-2026-10-22.md). |
| I10 | Borrado y revocación persistente aun con fallo de borrado. | 9 PrivacyUiTests y recorrido real aprobados; completa 49/49 API28. | Ejecución autorizada; revisión pendiente. | [Evidencia](evidencias/academico/I10-2026-10-22.md). |
| I11 | Seguridad/SSDF y correcciones SEC-01–SEC-03. | Verificado: build, 54 Android, políticas y regresiones. | Ejecución autorizada; revisión pendiente. | [Evidencia](evidencias/academico/I11-2026-10-23.md), [revisión](SEGURIDAD_ACADEMICA.md). |
| I12 | Verificación y entrega técnica. | 55 Android API28, 93 JVM, 15 SQL, 23 herramientas y nueve checks reales aprobados; límites explícitos. | Ejecución autorizada; revisión/rúbrica pendientes. | [Informe](evidencias/academico/I12-2026-10-23.md), [entrega](ENTREGA_ACADEMICA.md). |

## Ejecución I12 del 23 de octubre de 2026 UTC

- App 0.1.0-i12/12; fuente congelada, 55 Android y recorrido real de nueve comprobaciones aprobados. Build/lint/firmas/políticas y 93 JVM/15 SQL/23 herramientas aprobados. [Informe y hashes](evidencias/academico/I12-2026-10-23.md).
- Colector agrupado y flujo de instalación corregidos: verify.py instala con actualización y ejecuta Instrumentation directamente, conservando datos. Intentos previos y recuperación de la fixture se documentan; no se borran evidencias fallidas.
- [Manual de entrega/matriz](ENTREGA_ACADEMICA.md), [uso](USO_ACADEMICO.md), [seguridad/SSDF](SEGURIDAD_ACADEMICA.md). Tercer intento API36 no aprobado: 46 pasaron, 4 fallos de UI y 5 sin resultado completo ante ANR del sistema. IDE/físico/rúbrica externos. Commit local de cierre, sin push/publicación ni fase 2.

## Ejecución I11 del 23 de octubre de 2026 UTC

- Tres fallos reproducidos sobre I10 y corregidos: documento existente eliminado tras apertura rechazada y dos decodificaciones SQLite permisivas. Selector restringido también manejado sin crash.
- Repetición completa 54/54 Android API28, 93 JVM/15 SQL/14 políticas y lint debug/release 0. Revisión efectiva de recursos de backup incluso en release optimizado. [Evidencia I11](evidencias/academico/I11-2026-10-23.md), [seguridad/SSDF](SEGURIDAD_ACADEMICA.md).
- Commit local; I12 autorizado es el siguiente. Los pendientes de Android16/IDE/dispositivo/rúbrica se mantienen separados.

## Ejecución I10 del 22 de octubre de 2026

- Confirmaciones, reintentos, bloqueo por retirada no guardada y cancelación de selectores obsoletos implementados. URI file rechazada sin sobrescribir datos internos.
- 93 JVM/15 SQL host aprobadas; suite final de 49 Android API28 aprobada después del ajuste visual. Selector real generó CSV con cuatro IDs exactos; detener/abrir el proceso conservó eventos/aceptación y posteriormente la revocación sin datos. Copia CSV externa conservada según la política.
- Pruebas de fallos separadas y capturas revisadas. El conductor de respaldo se corrigió para incluir WAL, sin borrar datos ni atribuir un respaldo incompleto a pérdida del almacenamiento.
- API36 adicional no aprobada: 44 casos/4 fallos en el intento conectado y 4 terminados/3 fallos en la repetición de privacidad, con ANR de System UI/Quickstep/Process system delante de los diálogos. Capturas, logs y XML conservados; casos incompletos no contados como aprobados. [Evidencia](evidencias/academico/I10-2026-10-22.md), [manual](USO_ACADEMICO.md). Detención antes de I11; sin push/publicación.

## Ejecución I09 del 22 de octubre de 2026

- CSV completo/UTF-8 con selector y tickets ligados a aceptación; no abre destino con aceptación obsoleta. Cancelación, fallo y éxito distintos; limpieza parcial a cargo del proveedor.
- 93 JVM y 6 pruebas Android de exportación aprobadas; la completa previa pasó los 33 base y detectó fallos de fixtures documentados/corregidos. [Evidencia](evidencias/academico/I09-2026-10-22.md). Sigue I10; API36 en preparación.

## Ejecución I08 del 22 de octubre de 2026

- Cola/sesión de proceso e insets implementados. Primeras 27 Android: 1 fallo de creación concurrente; corregido sin omitir el caso. Repetición completa: 33/33 Android aprobadas, más 80 JVM y 15 SQL host.
- Los nuevos casos prueban consentimiento, cuatro botones, doble clic, recreación, reapertura y errores. [Evidencia](evidencias/academico/I08-2026-10-22.md). Continúa I09 autorizado; muerte de proceso pendiente.

## Ejecución I07 del 22 de octubre de 2026

- Pantalla de consentimiento/simulación conectada al dominio y SQLite, con cuatro botones y lista limitada a 100. Build y lint 0 tras corregir Overdraw. [Evidencia](evidencias/academico/I07-2026-10-22.md).
- ADB arranca tras crear su directorio interno con permiso específico; emulador oficial API28 en arranque por software. Ejecución Android aún pendiente al commit. I08 autorizado a continuación.

## Ejecución I06 del 22 de octubre de 2026

- AcceptanceService y SQLiteAcceptanceStore implementados, con migración 1→2 sin perder eventos y retirada guardada antes del borrado. No hay aceptación automática ni UI nueva. [Guía](PERSISTENCIA_ACADEMICA.md).
- 75/75 JVM y 15/15 SQL host aprobadas; ambos APKs y lint correctos. Quince pruebas Android nuevas, 27 totales compiladas; ninguna ejecutada por bloqueo del entorno. No se declara persistencia Android verificada.
- [Evidencia](evidencias/academico/I06-2026-10-22.md). Commit local separado; borrador de arranque de nube actualizado, pendiente de publicación. Detención antes de I07.

## Ejecución I05 del 22 de octubre de 2026

- SQLiteOpenHelper/repositorio y 12 casos instrumentados implementados; APKs principal/pruebas y lint satisfactorios. Siete pruebas host del SQL aprobadas, sin atribuirles validación del adaptador Android.
- Dispositivo/emulador no disponible y ADB falla por ruta protegida. Pruebas instrumentadas sin ejecutar; cierre pendiente. [Evidencia](evidencias/academico/I05-2026-10-22.md). Commit local separado; continuación I06 autorizada.

## Ejecución I04 del 22 de octubre de 2026

- CSV puro implementado y probado sin nuevas dependencias. Diez casos nuevos; 58/58 JVM aprobados. [Evidencia](evidencias/academico/I04-2026-10-22.md).
- No implementa guardado Android. Commit local separado; siguiente I05 ya autorizado y después I06.

## Verificación solicitada del 22 de octubre de 2026

- Nueva ejecución forzada de tests/build/lint sobre `c67e4c9`: salida 0, 48 pruebas sin fallos/errores/omitidas, lint 0 incidencias. Inspección del APK empaquetado y firma correctas.
- Sin USB/KVM/emulador/imágenes Android; ADB vuelve a fallar por directorio personal de solo lectura. Instalación/apertura y Sync siguen sin ejecutar; no se contabilizan como aprobados.
- [Informe y comandos](evidencias/academico/VERIFICACION-I01-I03-2026-10-22.md). Sin cambios de código, dependencias o configuración de nube; sin comenzar I04.

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
