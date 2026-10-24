# AuditLab — Registro de avance

Fecha de actualización: 24 de octubre de 2026, UTC; comprobaciones de la app realizadas el 23 de octubre UTC.

Plan vigente: [PLAN_MAESTRO.md](PLAN_MAESTRO.md). Instrucciones de entrada: [AGENTS.md](../AGENTS.md).

## Alcance actual de la solicitud

**Objetivo activo ratificado el 24 de octubre: completar el proyecto original de dos apps y API**, siguiendo [PLAN_MAESTRO.md](PLAN_MAESTRO.md), fases 0–8. La implementación I01–I12 corresponde a una app local; el proyecto completo continúa en fase 3. Siguiente tarea disponible: **P3.2 — inscripción de dispositivos**; después recepción/consulta de eventos y el recorrido real agente → API → centro de la fase 4. La revisión actual del código confirma que Ktor registra rutas de autenticación/salud y que las dos apps contienen pantallas base; no existe todavía el recorrido integrado.

**Publicación autorizada en GitHub:** el 24 de octubre se subieron los 14 commits académicos pendientes a `main` de [Juanguz095/ProyectoDesarrolloMoviles](https://github.com/Juanguz095/ProyectoDesarrolloMoviles). Incluyen I01–I12, correcciones, documentación e informe de los dos emuladores. Se confirmó mediante `git ls-remote` que la revisión publicada coincide con `652b6988931ad3d191249c749f2b5c53ea08445d`.

**Comprobación adicional solicitada con dos emuladores:** la misma APK I12 aprobó **55/55 casos en Android8/API26 y 55/55 en Android9/API28**. Se conservaron los datos normales existentes de API28. [Informe adicional](evidencias/academico/DOS-EMULADORES-2026-10-23.md). Otro intento API36 se interrumpió durante instalación tras inestabilidad del sistema al arrancar; no ejecutó Instrumentation ni se contó como aprobado.

**Implementación I01–I12 y entrega técnica de la fase 1 académica completadas sobre API28.** Se revisaron errores anteriores y se corrigieron exportación, datos SQLite dañados, selector restringido y herramientas de prueba. Resultado final: 93 JVM, 15 SQL host, 23 herramientas y 55 Android aprobadas; nueve comprobaciones reales de selector/rotación/CSV/reinicio/revocación fallida y reintento; lint debug/release 0. Versión 0.1.0-i12/12.

[Entrega y matriz](ENTREGA_ACADEMICA.md), [informe I12/hashes](evidencias/academico/I12-2026-10-23.md), [avance académico](AVANCE_ACADEMICO.md), [seguridad/SSDF](SEGURIDAD_ACADEMICA.md). Android16 sigue sin validación completa tras tres intentos: el último registró 46 casos aprobados, 4 timeouts de UI y 5 sin resultado completo por ANR del sistema del emulador. Sync IDE, teléfono físico y PDF/rúbrica requieren evidencia propia. No se declara conformidad académica sin contrastarlos; fase 2 fuera del alcance.

El estado siguiente corresponde al proyecto completo y vuelve a ser la ruta activa por la instrucción actual del usuario. Las bitácoras académicas conservan sus resultados y su alcance local.

## Estado del proyecto completo

- Etapa: **fase 3 en curso; P3.1 verificada**. Autenticación HTTP lista; inscripción, eventos, consultas y revocación de dispositivos pendientes.
- Tarea siguiente: **P3.2 — inscripción con código de un uso y consentimiento**.
- Tarea principal en curso: ninguna al cerrar esta sesión.
- Tareas verificadas: **P0.1, P0.2, P1.1, P1.2, P1.3, P2.1, P2.2, P2.3 y P3.1**.
- Pendiente externo de P0.3: workflow preparado; código publicado en `main`; falta comprobar el resultado alojado de GitHub Actions. No bloquea el desarrollo local de P3.2.
- Evidencias: [P0.1](evidencias/P0.1-2026-10-17.md), [P0.2/P2.1](evidencias/P0.2-2026-10-17.md), [P0.3](evidencias/P0.3-2026-10-17.md), [fase 1](evidencias/P1-2026-10-17.md), [P2.2](evidencias/P2.2-2026-10-17.md), [P2.3](evidencias/P2.3-2026-10-17.md) y [P3.1](evidencias/P3.1-2026-10-17.md).
- Herramientas: JDK completo, Gradle, Android SDK y PostgreSQL comprobados. Guía reproducible en [desarrollo](desarrollo.md).

El prototipo Python/SQLite conserva su contrato 0.1 y la evidencia previa de 16 pruebas HTTP y 2 JavaScript. Los dos APKs nativos base siguen sin consentimiento funcional, login ni sincronización; no cambiaron en esta fase. OpenAPI 0.2 pasa 49 tests. Ktor implementa login/refresh/logout, JWT por biblioteca, sesiones revocables, auditoría transaccional y límites compartidos en PostgreSQL. Ocho unitarios y 31 tests PostgreSQL aprobados; autenticación real tras reinicio y error 503 al perder BD comprobados. `/ready` comprueba infraestructura de BD; `/health` informa proceso. Faltan inscripción, recepción, consultas y revocación de dispositivos definitivos. Sin validación Android, HTTPS de piloto ni GitHub Actions. Código publicado en GitHub en `main`; commit inicial y coincidencia remota verificados en la bitácora de publicación. Sin despliegue de la aplicación. Las instrucciones y nombres de variables del entorno están guardados en borrador, sin valores, publicación o restauración probada.

## Seguimiento por fase

Todos los IDs remiten a tareas definidas en el plan maestro. Al comenzar una tarea, añadir su detalle a la tabla de ejecución; al verificarla, enlazar evidencia y actualizar esta tabla.

| Fase | Tareas pendientes | Tareas verificadas | Cierre |
| --- | --- | --- | --- |
| 0. Base | P0.3 (ejecución alojada) | P0.1, P0.2 | Pendiente de CI remota |
| 1. Requisitos | Ninguna | P1.1, P1.2, P1.3 | Verificada como especificación |
| 2. Contratos y estructura | Ninguna | P2.1, P2.2, P2.3 | Verificada |
| 3. API | P3.2, P3.3, P3.4, P3.5, P3.6 | P3.1 | En curso |
| 4. Primer recorrido Android | P4.1, P4.2, P4.3 | Ninguna | Pendiente |
| 5. Agente | P5.1, P5.2, P5.3, P5.4 | Ninguna | Pendiente |
| 6. Centro de control | P6.1, P6.2, P6.3, P6.4 | Ninguna | Pendiente |
| 7. Validación | P7.1, P7.2, P7.3, P7.4 | Ninguna | Pendiente |
| 8. Entrega | P8.1, P8.2, P8.3, P8.4 | Ninguna | Pendiente |

## Tabla de ejecución

| Tarea | Estado | Entregable / evidencia | Resultado, bloqueo y siguiente acción |
| --- | --- | --- | --- |
| P0.1 | verificada | [Evidencia](evidencias/P0.1-2026-10-17.md) | Fallos reproducidos/corregidos y límites del prototipo registrados. |
| P0.2 | verificada | [Evidencia](evidencias/P0.2-2026-10-17.md), [desarrollo](desarrollo.md). | Toolchain completo, PostgreSQL y compilación nativa comprobados. |
| P0.3 | bloqueada | [Evidencia](evidencias/P0.3-2026-10-17.md). | CI configurada y comandos locales probados; código publicado en `main`, ejecución alojada pendiente de comprobar. |
| P1.1 | verificada | [Evidencia fase 1](evidencias/P1-2026-10-17.md), [requisitos](requisitos.md). | Datos, alcance, acceso, retención y límites definidos. |
| P1.2 | verificada | [Estados](estados.md), [evidencia](evidencias/P1-2026-10-17.md). | Flujos positivos/negativos y revocación offline definidos. |
| P1.3 | verificada | [Historias](historias.md), [evidencia](evidencias/P1-2026-10-17.md). | 12 historias relacionadas con los 15 criterios, sin declarar aceptación funcional. |
| P2.1 | verificada | [Evidencia](evidencias/P0.2-2026-10-17.md), [ADR](decisiones/0001-estructura-y-herramientas.md). | Dos bases Android y Ktor compiladas; prototipo conservado. |
| P2.2 | verificada | [Evidencia](evidencias/P2.2-2026-10-17.md), [contrato](contrato.md). | OpenAPI 0.2 y 49 pruebas de contrato aprobadas. Prototipo 0.1 conservado. |
| P2.3 | verificada | [Evidencia](evidencias/P2.3-2026-10-17.md), [BD](base-datos.md). | Flyway repetible, bootstrap Argon2id, permisos y disponibilidad; 5 unitarios + 15 PostgreSQL satisfactorios. |
| P3.1 | verificada | [Evidencia](evidencias/P3.1-2026-10-17.md), [autenticación](autenticacion.md). | Login/refresh/logout, roles, límites, auditoría y concurrencia; 8 unitarios + 31 integración aprobados. |
| P3.2 | pendiente | Contrato y tablas de códigos/dispositivos/consentimientos preparados. | Implementar consumo atómico de código, consentimiento versionado y credencial revocable; probar doble inscripción concurrente y roles. |

## Bitácora de sesiones

### 24 de octubre de 2026 UTC — aclaración y restitución del alcance completo

- El usuario ratificó que solicita el proyecto original de dos apps conectadas a una API y pidió aclarar sus planes. Se restablece `PLAN_MAESTRO.md` como guía activa; la app local I01–I12 conserva su documentación y evidencias.
- Revisión de solo lectura del checkout limpio: `Application.kt` conecta autenticación, `/health` y `/ready`; las actividades de agente/centro son pantallas base. Inscripción, eventos y recorrido entre dispositivos siguen pendientes según la tabla del plan.
- Se aclaró el alcance en README, este registro y las entradas académicas. Siguiente tarea disponible P3.2; no se marca como implementada o verificada por haber revisado el plan. La petición actual de explicar las fases no acredita nuevas funciones ni pruebas de integración.

### 24 de octubre de 2026 UTC — publicación académica en GitHub

- Solicitud explícita «Sube los cambios a github». Se actualizó la referencia remota y se comprobó que `main` podía avanzar desde `118a738` sin sobrescribir historia.
- `git push origin main` terminó con código 0: `118a738..652b698 main -> main`. Los 14 commits pendientes incluyen implementación I01–I12 y la comprobación de 55/55 casos en cada uno de los emuladores API26/API28.
- Verificación independiente con `git ls-remote origin refs/heads/main`: `652b6988931ad3d191249c749f2b5c53ea08445d`, idéntico al HEAD publicado. Este registro se incorpora después de confirmar esa publicación.

### 23 de octubre de 2026 UTC — comprobación en dos emuladores

- Solicitud «Pruébalo con dos emuladores»: 55/55 Android8/API26 y repetición 55/55 Android9/API28; cero fallos, errores u omitidos. Misma APK y árbol fuente que I12, sin cambios de dependencias ni aserciones. Volcado normal API28 idéntico antes/después; API26 AVD nuevo. [Evidencia](evidencias/academico/DOS-EMULADORES-2026-10-23.md).
- Intentos preparatorios y cuarto intento API36 conservados con sus límites: watchdog del sistema antes de instalar la app; interrupción deliberada durante instalación, sin pruebas de la app ejecutadas. Pendientes API36 estable, teléfono físico, IDE y rúbrica. Fase 2 no iniciada.

### 23 de octubre de 2026 UTC — I12 académico

- Final 0.1.0-i12/12 con 55 Android API28, 93 JVM, 15 SQL y 23 herramientas aprobadas; lint debug/release 0 y firmas/políticas verificadas. Nueve checks reales incluidos rotación del selector, proceso detenido y fallo de DELETE/reintento persistente. Original de fixture restaurado; CSVs anteriores conservados.
- Herramienta de entrega corregida para XML agrupado e instalación que conserva datos; intentos previos preservados. [Informe](evidencias/academico/I12-2026-10-23.md), [entrega](ENTREGA_ACADEMICA.md). API36 no aprobada: 46 casos pasaron, 4 timeouts de UI y 5 incompletos ante ANR del sistema, con captura y logs preservados. Límites externos IDE/físico/rúbrica. Commit local, sin push/despliegue o fase 2.

### 23 de octubre de 2026 UTC — I11 académico

- Revisión SSDF y políticas de manifiestos/backup/transferencia/Dex en debug y release, sin dependencias nuevas. Tres fallos reproducidos (52 casos/3 fallos) y corregidos; luego 54/54 Android API28, 93 JVM, 15 SQL y 14 políticas aprobadas. Lint debug/release 0.
- [Evidencia I11](evidencias/academico/I11-2026-10-23.md), [seguridad](SEGURIDAD_ACADEMICA.md). Commit local y continuación I12 autorizada; sin push/publicación.

### 22 de octubre de 2026 — Ejecución I10 académica

- Controles de borrado/revocación con confirmación, errores/reintentos y bloqueo inmediato de exportaciones pendientes; app 0.1.0-i10. Sin nuevas dependencias de producción o cambios de herramientas.
- 93 JVM, 15 SQL host y completa final 49/49 Android API28 aprobadas. Siete comprobaciones del recorrido real y proceso detenido/relanzado aprobadas, con CSV externo comparado por IDs. Ajuste de fondo/contraste revisado en capturas y repetido en tests; respaldo manual corregido para incluir WAL.
- API36: intento conectado fallido (44 casos, 4 fallos de UI); repetición con otro renderizador/un vCPU también bloqueada por ANR de Quickstep/System UI (4 terminados, 3 fallos). Capturas y XML preservados; no se declara aprobada. Repetir en una instancia estable con KVM o dispositivo compatible. [Evidencia I10](evidencias/academico/I10-2026-10-22.md), [manual](USO_ACADEMICO.md). Commit local de cierre y documentación; sin push/despliegue ni fase 2. I11 es el siguiente incremento, pendiente de autorización.

### 22 de octubre de 2026 — Ejecución I09 académica

- Exportación explícita por SAF, UTF-8, snapshot completo y revalidación de contexto. 93 JVM y 6 pruebas Android de exportación aprobadas; 33 base aprobadas en la completa previa. Fallos de fixtures de proveedor/permisos corregidos y registrados sin atribuir éxito a sus intentos fallidos. [Evidencia](evidencias/academico/I09-2026-10-22.md).
- I10 repetirá la suite integrada. Imagen/AVD API36 preparados, pruebas aún pendientes. Commit local separado; sin push o publicación.

### 22 de octubre de 2026 — Ejecución I08 académica

- Sesión/cola compartidas y WindowInsets implementados; 80 JVM, 15 SQL host y 33 Android aprobadas. Primer intento 27 Android detectó creación concurrente de tablas; corregida con DDL idempotente, sin omitir pruebas ni cambiar versiones. [Evidencia](evidencias/academico/I08-2026-10-22.md).
- Verificados botones, consentimiento, pulsaciones rápidas, recreación, reapertura y recuperación de error. Muerte de proceso pendiente. Commit local separado, continúa I09 autorizado.

### 22 de octubre de 2026 — Ejecución I07 académica

- Pantalla Views/ScrollView conectada a servicios y SQLite, aceptación explícita, cuatro eventos y lista de 100; errores en español. Dos builds salida 0; Overdraw corregido y lint final 0. [Evidencia](evidencias/academico/I07-2026-10-22.md).
- ADB resuelto mediante permiso específico y creación de .android; emulador oficial API28 instalado y en arranque software, sin afirmar todavía pruebas Android. Commit local separado; sigue I08 autorizado.

### 22 de octubre de 2026 — Ejecución I06 académica

- Aceptación persistente en SQLite, migración 1→2 conservando eventos y servicio de decisión explícita con dependencias inyectadas. Sin dependencias nuevas ni cambios de herramientas; UI base conservada.
- Suite renovada: 75 JVM y 15 SQLite host aprobadas. APK principal/pruebas, firmas y lint (0 incidencias) verificados. 27 casos instrumentados compilados y sin ejecutar por falta de Android operativo; persistencia Android no acreditada. [Evidencia I06](evidencias/academico/I06-2026-10-22.md), [guía](PERSISTENCIA_ACADEMICA.md).
- Commit local separado; borrador de arranque actualizado, sin publicación. Se detiene antes de I07. Sin push, despliegue, cambios del sistema ampliado o fase 2.

### 22 de octubre de 2026 — Ejecución I05 académica

- Repositorio SQLite Android implementado con restricciones, índice, cupo/inserción transaccional y errores explícitos. APK principal y de instrumentación compilados; lint 0 incidencias. Sin nuevas dependencias externas.
- Siete pruebas del DDL en SQLite host aprobadas; 12 instrumentadas compiladas pero sin ejecutar por bloqueo ADB y falta de dispositivo/emulador. Cierre Android pendiente, sin inventar verificación. [Evidencia](evidencias/academico/I05-2026-10-22.md).
- Commit local separado; continúa I06 por autorización actual. Sin UI de I07, push o fase 2.

### 22 de octubre de 2026 — Ejecución I04 académica

- Autorizada continuación I04–I06. CSV puro implementado, sin dependencias nuevas; 10 casos nuevos y 58/58 JVM aprobados. [Evidencia I04](evidencias/academico/I04-2026-10-22.md).
- Commit local separado; I05 es el siguiente incremento autorizado. No se implementó exportación Android, I07 ni fase 2.

### 22 de octubre de 2026 — Pruebas académicas solicitadas

- Reejecutados tests, build y lint de `academic/` sobre `c67e4c9`, forzando tareas: salida 0; 48 pruebas JVM aprobadas, 0 fallos/errores/omitidas; lint 0 incidencias. Firma y manifiesto del APK comprobados.
- Prueba Android sin ejecutar: sin USB, KVM, emulador o imagen instalada; ADB falla intentando crear una carpeta personal de solo lectura. No se declara un recorrido funcional Android ni SQLite verificados.
- [Evidencia de esta ejecución](evidencias/academico/VERIFICACION-I01-I03-2026-10-22.md). Sin cambios de código/dependencias, nueva funcionalidad, push o publicación.

### 22 de octubre de 2026 — Ejecución I02/I03 académica

- Solicitud de continuar hasta I03 atendida en orden; dependencia de pruebas kotlin.test2.2.10/JUnit4 expresamente aprobada por el usuario, sin cambiar versiones de herramientas.
- Modelos/contratos y AuditService implementados solo en `academic/domain`: aceptación, límites 1000/100, errores explícitos, borrado y revocación antes de limpieza. Coordinación compartida comprobada con dobles en memoria; no acredita SQLite, durabilidad ni cola Android.
- 15 pruebas de I02 y 33 nuevas de I03: **48 aprobadas, 0 fallos/errores/omitidas**. Build Android, firma debug y lint (0 incidencias) verificados. Evidencias [I02](evidencias/academico/I02-2026-10-22.md) e [I03](evidencias/academico/I03-2026-10-22.md).
- Commits locales separados por incremento; sin push, despliegue o fase 2. Instrucciones de arranque de nube actualizadas en borrador, sin publicación. I01 mantiene pendientes IDE/Android; I04 requiere una nueva autorización.

### 22 de octubre de 2026 — Ejecución I01 académica

- Base `academic/app` y `academic/domain` creada sin modificar fuentes/configuración de apps/backend anteriores.
- Versiones exigidas preservadas; Build/lint y probe de compilación JVM satisfactorios, APK debug firmado y manifiesto sin permisos. Evidencia y límites en [I01](evidencias/academico/I01-2026-10-22.md).
- Sin pruebas funcionales todavía: domain vacío/NO-SOURCE. Sync IDE y ejecución en Android pendientes por falta de sesión IDE y fallo ADB en ruta personal de solo lectura; no se acredita una aceptación completa.
- Preparación de nube repetible guardada en borrador; commit local para revisión, sin push ni despliegue. Se detiene antes de I02 por la regla explícita del contexto del usuario.

### 22 de octubre de 2026 — Plan académico y propuesta I01

- Leídos ambos textos aportados; el contexto posterior determina requisitos y proceso de aprobación.
- Entregados plan académico, inventario/propuesta limitada a I01 y registro independiente de doce incrementos pendientes.
- Corregidas diferencias del primer beta: cuatro botones de eventos, Views/ScrollView, límites 1000/100, CSV numérico/CRLF y revocación persistente aunque falle el borrado.
- No se modificó código, configuración Gradle ni contratos del sistema anterior. Sin instalación de herramientas, Build, Sync, pruebas de aplicación, commit o push. No se ejecutó P3.2 ni se autorizó fase 2.

### 18 de octubre de 2026 — Publicación inicial en GitHub

- El usuario autorizó subir el proyecto a `Juanguz095/ProyectoDesarrolloMoviles`.
- El origen HTTPS coincide con el repositorio indicado; lectura remota correcta y sin referencias existentes al comenzar. No hay historial remoto que reemplazar.
- Preparados 77 archivos de código, configuración, pruebas y documentación para el primer commit en `main`. Revisión de rutas, tamaños y patrones habituales de secretos sin hallazgos; excluidos `.tooling`, `.venv`, builds, datos SQLite, variables locales y llaves de firma.
- Push normal a `origin/main` completado, salida 0, sin forzar. Commit inicial `a88103a319d65091dfe32e660e97114ee7728022`; consulta posterior con `git ls-remote` confirma el mismo SHA en GitHub. Esta actualización documental se publica en un segundo commit y su SHA final se verifica al terminar.
- Comprobación documental satisfactoria (34 tareas, 15 criterios, 22 documentos). `diff --check` reconoce los finales CRLF del wrapper Windows mediante `core.whitespace=cr-at-eol`; el wrapper no se modifica.
- No se repiten suites funcionales en esta sesión de publicación; resultados previos y límites en las evidencias P0–P3.1. CI alojada pendiente de verificación. Sin despliegue ni publicación del entorno.
- El avance funcional se conserva: P3.1 verificada, siguiente P3.2.

### 17 de octubre de 2026 — Autenticación HTTP definitiva

- P3.1: tres rutas auth, JWT por biblioteca con emisor/audiencia/tipo, refresh rotatorio, logout, sesión absoluta y comprobación de rol/estado en cada acción protegida.
- V2 añade cuotas PostgreSQL compartidas por cuenta/cliente/global con HMAC; V1 conservada. Auditoría y acciones confirmadas en la misma transacción.
- Ocho unitarios y 31 integración aprobados sin omitidos; tres ejecuciones completas sobre fixtures diferentes. El servidor de la última usa únicamente credenciales runtime, sin variables administrativas. Refresh concurrente y logout no dejan descendientes válidos; fallo de auditoría revierte el login.
- Distribución real: login/refresh, reinicio propio, refresh persistente, logout y rechazo posterior; caída de BD produce `/ready` y login 503, liveness 200. Contratos válidos y 49 fixtures aprobados.
- `start_skill` y cuatro requisitos de variables BD/firma guardados en borrador confirmado, sin valores; instalador/red/repositorios preservados. Para activar: revisar y guardar en ajustes del entorno, después publicar. No se realizó esa publicación.
- Documentación final válida: 34 tareas, 15 criterios trazados y 22 documentos. Sin cambios Android, push, despliegue o CI alojada. Evidencia y límites en P3.1; siguiente P3.2.

### 17 de octubre de 2026 — Contratos y persistencia definitiva

- P2.2: contrato 0.2 de 13 paths/14 operaciones, esquemas estrictos, errores, ejemplos y 49 tests aprobados; prototipo 0.1 conservado.
- P2.3: migración V1 de ocho tablas, FK/checks/índices, Flyway, pool y CLI bootstrap/grants. Cinco unitarios y quince tests PostgreSQL aprobados, sin omitidos.
- Comprobados comandos de distribución instalada, repetición de migraciones/bootstrap, rechazo sin contraseña y `/ready` 200→503 al detener BD; `/health` permanece vivo.
- CI ampliada para contrato, JVM e integración; ejecución alojada sigue pendiente. No se cambió código Android ni se atribuyó progreso funcional a los APKs base.
- `start_skill` actualizado y guardado como borrador confirmado con instrucciones de BD/CLI, prueba aislada y disponibilidad. El instalador y los demás campos se conservaron. Para activar esa configuración hay que revisarla y guardarla en los ajustes del entorno y después publicar; esta sesión no la publicó.
- Comprobación documental final: salida 0; 34 tareas, 15 criterios trazados y 19 documentos con enlaces válidos.
- Evidencias de fallos/correcciones y límites en P2.3; siguiente tarea P3.1.

### 17 de octubre de 2026 — Primera ejecución del plan

- P0.1: pruebas reales del prototipo, correcciones de validación/consentimiento/idempotencia y revocación local anterior a red.
- P0.2: instalación verificada de herramientas en rutas escribibles; construcción Android/Ktor y prueba PostgreSQL con contraseña incorrecta/correcta.
- P1.1–P1.3: requisitos, estados e historias con trazabilidad de los 15 CA.
- P2.1: módulos, ADR, APKs debug y distribución Ktor base; lint regenerado sin incidencias y arranque HTTP comprobado.
- P0.3: estructura/CI y guías preparadas; GitHub Actions pendiente de publicación autorizada y resultado alojado.
- Configuración: `install_script` y `start_skill` guardados en borrador; no se publicó el entorno. Sin commit, push o despliegue.
- Repetibilidad: ejecutado el contenido exacto del instalador guardado, salida 0. Comprobación final documental: 34 tareas, 15 CA trazados y 14 documentos con enlaces válidos; contrato de 9 rutas válido y XML actual de lint sin incidencias.
- Siguiente sesión: P2.2 y P2.3. Mantener como pendientes los flujos Android, API definitiva y aceptación en dispositivos.

### 17 de octubre de 2026 — Documento de ejecución

- Inspeccionados: archivos presentes, Git, documentos y partes de código/contrato pertinentes al estado inicial.
- Entregados: plan maestro, guía de agente, registro de avance y enlace de entrada desde README.
- Comprobación documental ejecutada con Python, salida 0: enlaces locales válidos, 34 tareas únicas presentes en el seguimiento y 15 criterios de aceptación definidos. Esta comprobación no valida la aplicación.
- Alcance: documentación; no implementación nueva, compilación, prueba funcional, commit, push ni despliegue.
- Siguiente sesión de desarrollo: comenzar P0.1; continuar tareas disponibles tras registrar resultados.

## Actualización obligatoria al terminar una sesión

Registrar tarea realizada, estado real, archivos y evidencias, comprobaciones ejecutadas, defectos/bloqueos y siguiente tarea. Mantener separadas las decisiones propuestas de las verificadas. No reemplazar resultados fallidos por un estado genérico de «listo».
