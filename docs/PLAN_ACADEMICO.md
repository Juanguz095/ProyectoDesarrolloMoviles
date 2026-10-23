# AuditLab — Plan de la primera entrega académica

**Versión:** 1.0 · **Fecha:** 22 de octubre de 2026, America/Lima.
**Estado:** ruta académica implementada hasta I10 por solicitud explícita del usuario. Modelos/reglas, persistencia, pantalla, cola, exportación y privacidad están conectados. Verificación: 93 JVM, 15 SQL host y 49 Android API28 aprobadas, además de selector real y persistencia tras detener/abrir el proceso. API36 sin validación completa por ANR del emulador: resultados y límites en [AVANCE_ACADEMICO.md](AVANCE_ACADEMICO.md). I11/I12 requieren nueva autorización. El [contexto vinculante](referencias/contexto-auditlab-2026-10-22.txt) prevalece sobre el [beta](referencias/plan-beta-original-2026-10-22.txt); PDF/prompts/rúbrica aún pendientes de contraste.

**Seguimiento de esta entrega:** [AVANCE_ACADEMICO.md](AVANCE_ACADEMICO.md).
**Referencia histórica del sistema ampliado, fuera de la fase autorizada:** [plan maestro](PLAN_MAESTRO.md) y [avance previo](AVANCE.md).

## 1. Meta y objetivos medibles

### Meta

Entregar una app Android local, instalable y comprensible para su autor, que genere únicamente eventos ficticios después de una aceptación explícita; conserve un historial, permita borrarlo y revocar esa aceptación, y exporte un CSV elegido por el usuario. La demostración debe funcionar sin internet y contar con evidencia reproducible.

### Objetivos

1. Separar reglas y modelos Kotlin de la interfaz y del almacenamiento Android mediante módulos `domain` y `app`.
2. Garantizar **cero eventos nuevos sin aceptación vigente**, incluidos los casos de reinicio y error al leer el estado.
3. Conservar exactamente los eventos confirmados y su contexto de aceptación al cerrar y abrir la app.
4. Mantener una interfaz utilizable ante rotación, pulsaciones repetidas, operaciones lentas y fallos de almacenamiento.
5. Exportar filas consistentes con el historial, en CSV UTF-8 con escape y protección de fórmulas comprobados.
6. Demostrar borrado y revocación persistentes, límites de almacenamiento y ausencia de permisos o datos personales innecesarios.
7. Presentar código, APK identificada, instrucciones y evidencias que permitan a otra persona repetir el recorrido y al estudiante explicar sus decisiones.

## 2. Alcance de esta entrega

| Incluido | Resultado esperado |
| --- | --- |
| Aceptación explícita | Explica qué se simula, almacenamiento, conservación, exportación y revocación. Rechazar permite salir sin generar datos. |
| Cuatro botones de eventos | Llamada entrante, llamada saliente, mensaje entrante y mensaje saliente; todos ficticios y marcados **SIMULADO**. No se hacen llamadas ni se envían mensajes. |
| Historial | Hasta 100 registros, con tipo, dirección, fecha/hora y marca SIMULADO en ScrollView; estados vacío, cargando y error. |
| SQLite local | Hasta 1000 eventos, consultas parametrizadas y transacciones para inserciones/cupo y borrado. Aceptación persistida y coordinada con las operaciones. |
| Exportación | Selector Android para crear un CSV en una ubicación elegida; cancelación y errores explícitos. |
| Borrar historial | Confirmación, eliminación persistente y aceptación conservada. |
| Revocar aceptación | Persiste primero la revocación y después intenta borrar eventos. Si falla el borrado, sigue revocada y permite reintentarlo. |
| Evidencias | Pruebas de reglas, pruebas Android pertinentes, recorrido manual, versión y resultados reales. |

La entrega local no necesita backend, login, roles, sincronización, segundo teléfono, Docker o API de OpenAI. **La fase 2 no está autorizada.** El backend y las apps anteriores se conservan como trabajo histórico y no se conectarán ni modificarán al ejecutar esta ruta. Su avance no acredita estos doce incrementos.

La app académica no leerá contenido o registros reales de SMS, llamadas, chats, contactos o notificaciones. No solicitará telefonía, accesibilidad, micrófono, ubicación ni permisos amplios de almacenamiento. No incluirá clientes de red, transmisión a otros dispositivos, arranque automático, AccessibilityService, captura automática ni servicios en segundo plano; por ello no necesita notificación persistente o WorkManager para esta entrega.

## 3. Punto de partida y decisiones técnicas

El repositorio actual ya tiene dos bases Android con pantallas informativas y autenticación Ktor. Las evidencias previas están en el registro general; no se han repetido en esta sesión. No existe todavía el proyecto local `app`/`domain` descrito en el beta.

### Organización propuesta

Crear, al ejecutar I01, un **build Gradle independiente** dentro de `academic/`, con sus módulos `app` y `domain`. Abrir esa carpeta en Android Studio. Así los nombres del beta quedan claros y las herramientas de la entrega local pueden validarse sin migrar el backend ni las apps existentes.

```text
ProyectoDesarrolloMoviles/
├── academic/                     # build independiente creado en I01
│   ├── app/
│   │   ├── src/main/             # Views, SQLite, selector de archivos
│   │   └── src/androidTest/      # persistencia y recorrido Android
│   ├── domain/
│   │   ├── src/main/kotlin/      # modelos, AuditService, contratos, CsvReport
│   │   └── src/test/kotlin/      # reglas y CSV con datos ficticios
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── gradlew / gradlew.bat
├── apps/                         # trabajo previo conservado
├── backend/                      # trabajo previo conservado
└── docs/
```

Esta estructura se creó al ejecutar I01, conservando el build raíz anterior. Los nombres app/domain son obligatorios según el contexto. Si el prompt de I01 exige otra ubicación o archivos, ajustar la propuesta antes de implementar. Mantener identificador propio, por ejemplo `com.auditlab.academic`, para instalarlo junto a las apps anteriores.

### Herramientas y versiones

En Omarchy bastan Android Studio, SDK Android, un JDK compatible, Git y un dispositivo propio o emulador. Usar Gradle Wrapper; Kali Linux y una API key no son requisitos de esta app.

| Componente | Obligatorio según el contexto del usuario | Base previa del repositorio, solo referencia |
| --- | --- | --- |
| AGP | 9.1.1 | 8.9.2 |
| Gradle | 9.3.1 | 8.11.1 |
| JDK | 17 | 21, compilando bytecode JVM 17 |
| Kotlin JVM | 2.2.10 para domain | 2.1.20 |
| compileSdk / targetSdk | 36 / 36 | 35 / 35 |
| minSdk | 26 | 26 |

**Regla de I01:** probar la combinación exigida en `academic/` y contrastar documentación oficial de compatibilidad. No sustituir versiones por las antiguas ni migrar el build raíz. Si aparece un bloqueo confirmado, registrar error, causa, alternativas y cambios concretos; cualquier cambio de versión o dependencia requiere justificación y aprobación explícita antes de aplicarlo. La ausencia del PDF no autoriza versiones alternativas.

Usar JDK 17 para Gradle y el código de esta ruta. En app usar Kotlin integrado de AGP; no aplicar además `org.jetbrains.kotlin.android`. En domain usar `kotlin.jvm` 2.2.10. Verificar su combinación con Gradle 9.3.1. Un error de resolución, del SDK o de red no demuestra por sí solo incompatibilidad.

**UI:** Android Views con ScrollView, MainActivity y WindowInsets, como exige el contexto; no incorporar Compose a esta entrega. **Persistencia propuesta:** SQLiteOpenHelper; contrastar su implementación concreta con el prompt I05. No añadir Room u otras dependencias sin aprobación. `domain` no importa Android, Views, SQLite ni APIs de archivos Android.

## 4. Reglas funcionales que se implementarán

Las exigencias explícitas del contexto se mantienen. Los campos concretos, las interfaces y decisiones adicionales de este apartado son propuestas para revisar en el incremento que corresponda; no autorizan anticipar su implementación.

### Modelo mínimo

| Dato | Tipo / regla |
| --- | --- |
| `eventId` | UUID generado una vez; clave única. |
| `occurredAtEpochMs` | Instante UTC en milisegundos, obtenido mediante reloj inyectable. La UI muestra hora local identificada. |
| `kind` | Enum de llamada o mensaje simulado. |
| `direction` | Entrante o saliente; con kind representa exactamente los cuatro botones. |
| `isSimulated` | Siempre `true`; visible en UI y CSV. |
| `consentId` | UUID de la aceptación vigente al generar el evento. |
| `policyVersion` | Versión de la política aceptada. |

No almacenar números telefónicos, remitentes, destinatarios o cuerpos de mensajes. Los textos de demostración proceden de etiquetas fijas de la app. Este modelo local no se envía a la API ni modifica su contrato de eventos.

El estado de aceptación incluye ID, decisión, versión de política y fecha de decisión. Implementación I05/I06: tabla `events` y tabla singleton `acceptance` en SQLite. Se eligió SQLite en lugar de la propuesta inicial de SharedPreferences para confirmar toda la decisión de forma atómica y evitar estados en memoria tras un commit fallido; no añade dependencias ni se atribuye esa elección al PDF. Cada evento conserva su contexto, sin una FK que dependa de una fila de aceptación mutable. Detalles, migración y límites de validación en [persistencia académica](PERSISTENCIA_ACADEMICA.md).

### Aceptación y privacidad

- Inicio en estado **cargando/bloqueado** hasta leer la aceptación. Un fallo de lectura no se interpreta como aceptación.
- Estados de negocio: pendiente, aceptado y revocado. Una versión de política distinta exige nueva aceptación antes de crear o exportar datos.
- Generación solo por pulsación explícita en primer plano y con política vigente. Aceptar no genera un evento automáticamente.
- **Borrar** elimina eventos y conserva aceptación; **revocar** persiste la retirada de aceptación e intenta eliminar eventos, con reintento si falla. El usuario ve la diferencia antes de confirmar.
- Ante revocación, bloquear nuevas acciones protegidas y persistir primero aceptación retirada y borrado pendiente. Solo después intentar borrar SQLite. **Un fallo al borrar nunca revierte la revocación.**
- Mostrar por separado «Aceptación revocada» y «Borrado pendiente» cuando corresponda. Conservar esa condición al reiniciar; permitir reintento del borrado incluso sin aceptación. Como política propuesta, no permitir nueva aceptación hasta completar la limpieza pendiente.
- Si falla la propia escritura de la decisión de revocación, bloquear en la sesión actual y mostrar «No se pudo guardar la revocación». No afirmar persistencia tras reinicio; reintentar su guardado antes de confirmar. No existe garantía de escritura cuando el almacenamiento es inaccesible.
- Nueva aceptación crea un ID nuevo y no restaura el historial borrado. Después de revocar y cerrar la app, un commit satisfactorio debe seguir reflejándose al volver.

### Persistencia, límites y concurrencia

- Capacidad máxima obligatoria: **1000 eventos**. Historial consultable: **100 eventos**. Propuesta de orden: más recientes primero, con desempate estable por ID.
- No añadir retención temporal o borrado automático que el contexto no solicita. Los registros permanecen hasta borrado/revocación; aplicar cualquier regla distinta solo si el prompt la exige y se documenta.
- Al llegar al máximo, rechazar nuevas inserciones con mensaje claro; no sobrescribir eventos vigentes. La comprobación de cupo y la inserción pertenecen a la misma transacción.
- Usar una **cola serial compartida** para comprobar aceptación, insertar, borrar, revocar y exportar. Cupo/inserción son atómicos en SQLite. La revocación usa dos etapas ordenadas: persistir retirada y luego borrar; no envolver ambas en una operación que deshaga la retirada si falla el borrado.
- Persistencia y archivos fuera del hilo principal. Gestionar `busy`, ciclo de vida de MainActivity, WindowInsets y cierre de SQLite cuando no existan operaciones pendientes. Rotación no debe recrear una cola que se salte las restricciones de la anterior.
- Mientras una acción está en curso, impedir su ejecución repetida; cada pulsación aceptada genera un solo evento y su propio ID. No hay un contador en memoria que sustituya la consulta transaccional de cupo.

### CSV y archivos

- Cabecera fija propuesta: `eventId,timestampEpochMs,kind,direction,isSimulated,consentId,policyVersion`; cotejar nombres exactos con el prompt I04.
- UTF-8, **timestamps numéricos** y terminadores **CRLF** obligatorios. Unidad propuesta: milisegundos UTC, pendiente de cotejar con I04. Orden estable por timestamp e ID; no convertir timestamps del CSV a fechas ISO ni a texto local.
- Escapar comillas dobles duplicándolas y entrecomillar campos con coma, comillas o saltos de línea. Probar también caracteres acentuados.
- Neutralizar celdas que pudieran interpretarse como fórmulas (`=`, `+`, `-`, `@`, incluidos prefijos de control/espacios relevantes). Aunque los datos actuales estén limitados a enums/IDs, verificar esa protección del generador.
- Con aceptación vigente y lista vacía, exportar solo la cabecera e informar «0 eventos». Sin aceptación vigente, no iniciar exportación.
- Obtener una instantánea consistente. Propuesta: exportar todos los eventos almacenados, hasta 1000, aunque la pantalla muestre solo 100; indicar cantidad. Contrastar el conjunto exacto con el prompt I09 y evitar truncamiento silencioso. El CSV contiene exactamente los IDs de esa instantánea.
- Usar Storage Access Framework, `ACTION_CREATE_DOCUMENT` o `CreateDocument`; el usuario elige destino. Cancelar no es un error ni produce un mensaje de éxito.
- Si la aceptación cambia mientras el selector está abierto, invalidar la exportación pendiente y revalidar antes de escribir. Cancelar y cerrar correctamente trabajos/streams pendientes al revocar.
- Informar fallo o archivo parcial y ofrecer reintento. Intentar eliminar la salida parcial cuando el proveedor lo permita; no garantizar eliminación si el proveedor la rechaza.
- Una copia ya exportada queda bajo control del usuario y su proveedor de documentos; revocar o borrar en AuditLab no elimina esas copias externas.

## 5. Fases, objetivos y metas

Las horas son de trabajo efectivo estimado, incluyen implementación y comprobaciones y deben recalibrarse después de I01. No son fechas comprometidas ni una estimación de todo el sistema ampliado.

| Fase | Incrementos | Objetivo | Meta verificable | Estimación |
| --- | --- | --- | --- | --- |
| A. Base y reglas | I01–I03 | Tener proyecto reproducible y reglas independientes de Android. | App base compilable; reglas de aceptación, límites y datos probadas con fakes. | 7–10 h |
| B. Datos y aceptación | I04–I06 | Guardar y representar datos consistentemente. | CSV probado; SQLite y aceptación sobreviven al reinicio; política de revocación persistente definida. | 7–10 h |
| C. Recorrido de usuario | I07–I09 | Completar la interacción visible y exportación real. | Simular → consultar → reiniciar → exportar funciona en Android. | 9–14 h |
| D. Controles y revisión | I10–I11 | Resolver errores, privacidad y configuración. | Borrado/revocación y casos concurrentes probados; revisión de seguridad sin bloqueadores de entrega. | 5–8 h |
| E. Entrega | I12 | Preparar una demostración reproducible. | APK identificada, manual, resultados, limitaciones y defensa técnica. | 3–5 h |

**Base:** 31–47 h. **Reserva propuesta:** 6–10 h para aprendizaje, configuración y correcciones. **Total orientativo:** 37–57 h. Con 10–15 h disponibles por semana, reservar aproximadamente 3–6 semanas y ajustar con resultados reales. El plan beta de diez días puede servir como objetivo intensivo, pero no se presenta como garantía para una persona que está aprendiendo.

Orden de implementación propuesto: I01 → I02 → I03 → I04 → I05 → I06 → I07 → I08 → I09 → I10 → I11 → I12. Las reglas de borrado/revocación se especifican y prueban en dominio en I03; la persistencia de aceptación llega en I06 y los controles de UI se completan en I10. No anticipar funciones posteriores para completar otro incremento. Cada paso requiere autorización propia; I11 consolida la revisión de seguridad.

## 6. Los doce incrementos ejecutables

**Método obligatorio del contexto:** inspeccionar, identificar archivos autorizados, proponer, implementar únicamente lo aprobado, revisar diff, comprobar Sync/Build/pruebas aplicables, corregir y registrar. **Detenerse y solicitar aprobación explícita antes del siguiente incremento.** Verificación técnica y aprobación son estados distintos. El prompt del PDF de cada incremento determina sus archivos y criterios; no inventar aprobación del usuario o del profesor.

### I01 — Preparación y contrato de herramientas

- **Entrada:** repositorio inspeccionado, beta y decisión documentada sobre versiones/PDF.
- **Trabajo:** crear `academic/`, wrapper, módulos `app` y `domain`, paquete propio, Activity vacía y exclusiones Git. Registrar JDK, SDK y compatibilidad; no añadir modelos o reglas de I02. Compilar el módulo domain aunque aún no tenga fuentes.
- **Cierre:** `app` y `domain` compilan y Sync es satisfactorio. Comprobar apertura de la Activity en dispositivo/emulador cuando esté disponible. Si domain aún no tiene tests, registrar cero/NO-SOURCE y no presentarlo como pruebas aprobadas. Si falta dispositivo, registrar compilación satisfactoria y validación de ejecución pendiente; la aceptación de esa limitación queda pendiente de revisión, sin inventar la prueba.
- **Evidencia:** comandos, versiones, salidas, revisión, dispositivo/API y captura de la pantalla base.

### I02 — Modelos y contratos

- **Entrada:** I01 verificado y aprobación explícita para este incremento.
- **Trabajo:** definir evento, enum, contexto/estado de aceptación y contratos de repositorio, reloj e IDs. Documentar el mecanismo de transacción común y crear fakes para pruebas.
- **Cierre:** modelos compilables sin imports Android; casos válidos/incorrectos definidos y almacenamiento sustituible por fakes.
- **Evidencia:** esquema de clases/contratos y tests de invariantes del modelo.

### I03 — Reglas y AuditService

- **Entrada:** I02 verificado y aprobación explícita para este incremento.
- **Trabajo:** generar eventos, validar aceptación/versión/tipo, aplicar máximo de 1000 almacenados y consulta de hasta 100, con reloj e IDs inyectados; definir operaciones de borrado y revocación con resultados explícitos.
- **Cierre:** tests rechazan generación antes de aceptar/después de revocar, versiones antiguas, cupo excedido y datos inválidos; evento aceptado tiene ID, instante y contexto correctos. Verificar que los rechazos no escriben.
- **Evidencia:** matriz de casos y resultados reales del módulo domain.

### I04 — Generador CSV

- **Entrada:** I03 verificado y aprobación explícita para este incremento.
- **Trabajo:** implementar `CsvReport` como función pura sobre una instantánea de eventos; fijar cabecera, orden, timestamps numéricos, CRLF, escape y neutralización de fórmulas.
- **Cierre:** tests de lista vacía, una/muchas filas, comas, comillas, saltos, Unicode y fórmulas. Esos últimos datos son entradas sintéticas del generador, no permisos para guardar texto libre de usuarios.
- **Evidencia:** resultados y pequeños CSV ficticios; no confundir generar texto con guardar mediante el selector Android.

### I05 — Persistencia SQLite

- **Entrada:** I04 verificado y aprobación explícita para este incremento.
- **Trabajo:** esquema versionado, repositorio Android, restricciones, índice de orden y consultas parametrizadas. Definir migraciones futuras que preserven datos; no recrear tablas con pérdida como solución de actualización.
- **Cierre:** pruebas Android insertan/leen/borran y reabren una BD temporal; rechazan IDs duplicados y cambios parciales; cupo/transacciones se verifican contra SQLite real.
- **Evidencia:** esquema, tests instrumentados y comparación de IDs antes/después de reabrir.

### I06 — Aceptación persistente

- **Entrada:** I05 verificado y aprobación explícita para este incremento.
- **Trabajo:** guardar aceptación, ID, política y fecha; implementar lectura inicial bloqueada, rechazo de versión distinta y reglas persistentes de retirada. El borrado tras revocación se coordina en I10, sin anticipar su UI.
- **Cierre:** decisión confirmada sobrevive al reinicio y una lectura fallida nunca habilita generación. Probar que una decisión retirada no se transforma en aceptada; fallo de escritura no se presenta como éxito.
- **Evidencia:** tests de persistencia y transiciones; decisiones de conservación/borrado visibles en la política.

### I07 — Interfaz completa mínima

- **Entrada:** I06 verificado y aprobación explícita para este incremento.
- **Trabajo:** pantalla Views con ScrollView y cuatro botones: **Llamada entrante**, **Llamada saliente**, **Mensaje entrante** y **Mensaje saliente**. Conectarlos a AuditService; mostrar hasta 100 registros, SIMULADO, estado de aceptación, vacío y textos en español. Exportación y controles completos se conectarán en I09/I10.
- **Cierre:** navegación y estados vacío/cargando/error/activo/revocado funcionales; generación conectada a SQLite. No añadir funcionalidades posteriores. Los controles aún no implementados no muestran resultados ficticios de éxito.
- **Evidencia:** capturas de los cuatro botones, marca SIMULADO y límite de 100; recorrido con y sin aceptación según lo autorizado en I07.

### I08 — Ciclo de vida y operaciones

- **Entrada:** I07 verificado y aprobación explícita para este incremento.
- **Trabajo:** cola serial compartida, estado busy y operaciones fuera del hilo principal; proteger dobles pulsaciones, ciclo de vida/rotación, cierre de SQLite y WindowInsets. No introducir dependencias arquitectónicas adicionales sin aprobación.
- **Cierre:** rotar no genera ni duplica eventos; operaciones confirmadas reaparecen al abrir; una operación no confirmada no se presenta como guardada; carga/error mantienen la política de aceptación.
- **Evidencia:** casos manuales y automatizados aplicables. Probar muerte del proceso separadamente de rotación.

### I09 — Exportación Android

- **Entrada:** I08 verificado y generador I04 aprobado.
- **Trabajo:** selector de documentos, flujo UTF-8 y cierre de streams, instantánea y coordinación con aceptación/revocación.
- **Cierre:** guardar, cancelar y fallo del proveedor producen resultados distintos; archivo leído por otra aplicación coincide con la instantánea; revocar con el selector abierto impide una exportación posterior con aceptación obsoleta.
- **Evidencia:** archivo ficticio, comparación de cabecera/IDs/filas y prueba de cancelación/fallo. No usar permisos amplios de almacenamiento.

### I10 — Borrado, revocación y recuperación

- **Entrada:** I09 verificado; casos de uso de dominio ya definidos; implementación de controles según el prompt I10.
- **Trabajo:** confirmaciones claras, reintento, inyección de fallos y casos de generación/exportación frente a borrado/revocación. Registrar el orden de confirmación de operaciones simultáneas.
- **Cierre:** borrar conserva aceptación; revocar la elimina e impide nuevos eventos. Después de confirmar y reiniciar no reaparecen datos. Ante fallo, no mostrar borrado completado ni reautorizar funciones. Si solo falla DELETE después de revocar, aceptación permanece retirada y el borrado queda pendiente de reintento.
- **Evidencia:** resultados de UI/persistencia y reinicio; fallos separados al guardar aceptación y al borrar SQLite, sin confundirlos. Verificar explícitamente revocación persistente con borrado fallido y reintento idempotente.

### I11 — Seguridad y revisión técnica

- **Entrada:** I10 verificado y aprobación explícita para este incremento.
- **Trabajo:** revisar manifiesto combinado, permisos, componentes exportados, backups/transferencia, logs, SQL, CSV y dependencias. Comprobar que solo el launcher se expone cuando sea necesario y que el módulo académico no requiere INTERNET. Consolidar controles SSDF aplicables con evidencia, sin declarar una certificación.
- **Cierre:** hallazgos corregidos o documentados; ningún defecto crítico/alto de los criterios de aceptación queda abierto. Revisar diff y evitar secretos, bases o APKs en Git. Comprobar configuración efectiva de backups, no solo una bandera.
- **Evidencia:** lista de hallazgos, impacto, corrección y repetición de la comprobación afectada. Revisar las exigencias SSDF específicas si aparecen en el PDF.

### I12 — Verificación y entrega académica

- **Entrada:** I11 verificado y requisitos del curso contrastados.
- **Trabajo:** ejecutar los criterios AC-01–AC-12; instalar la APK candidata, recorrer la demo, preparar manual, resultados y explicación del diseño.
- **Cierre:** todos los criterios tienen evidencia de la misma versión entregada. Registrar commit, variante, SHA-256 de APK, fecha, dispositivo/API, comando y resultado. Si la rúbrica sigue ausente, declarar validación técnica y conformidad académica pendiente.
- **Evidencia:** informe final, APK entregada por el medio del curso, manual de ejecución y video/capturas si se requieren. La firma de entrega y su distribución siguen las exigencias del curso; llaves fuera del repositorio.

## 7. Matriz de aceptación de esta app

Estos IDs son exclusivos de la entrega local y no sustituyen CA-01–CA-15 del sistema ampliado.

| ID | Conducta que debe demostrarse | Incrementos principales |
| --- | --- | --- |
| AC-01 | Instalar/abrir en Android compatible; build y tests reproducibles desde una revisión identificada. | I01, I12 |
| AC-02 | Rechazar aceptación, inicio pendiente o lectura fallida deja cero nuevos eventos y exportación deshabilitada. | I03, I06, I07 |
| AC-03 | Cada pulsación aceptada crea un único evento válido, ficticio y asociado a su aceptación; sin llamadas/SMS reales. | I02, I03, I07, I08 |
| AC-04 | IDs, contenido y aceptación confirmados se conservan al reabrir; no se inventa una operación no confirmada. | I05, I06, I08 |
| AC-05 | Historial de hasta 100 y almacenamiento de hasta 1000 usan orden estable y límites documentados, sin sobrescritura silenciosa. | I03, I05, I09 |
| AC-06 | CSV válido con Unicode, escape, neutralización de fórmulas y cabecera; filas coinciden con la instantánea. | I04, I09 |
| AC-07 | Guardar, cancelar y fallar en selector/proveedor se distinguen; sin permiso general de archivos ni éxito falso. | I09 |
| AC-08 | Borrar elimina historial después de reiniciar y conserva aceptación. | I05, I10 |
| AC-09 | Revocar confirmado bloquea generación/exportación y persiste al reiniciar aunque falle el borrado; permite reintentarlo; reaceptar no recupera datos. | I06, I07, I10 |
| AC-10 | Rotación, pulsaciones repetidas y operaciones frente a revocación no duplican ni permiten acciones con estado obsoleto. | I08, I09, I10 |
| AC-11 | Sin permisos/datos reales innecesarios, secretos/logs sensibles o backups; las inserciones son atómicas y un fallo de borrado no revierte la revocación. | I05, I10, I11 |
| AC-12 | Manual, APK identificada y evidencias permiten repetir la demo; requisitos y limitaciones están trazados. | I11, I12 |

## 8. Cómo trabajar y conservar evidencias

### Al comenzar una sesión

1. Leer este plan, el avance académico, las instrucciones del repositorio y la rúbrica cuando exista.
2. Comprobar archivos y Git; conservar cambios ajenos. Seleccionar el primer incremento pendiente con sus entradas satisfechas.
3. Explicar qué se hará, los archivos previstos y la comprobación que demostrará el resultado.
4. Implementar solo el incremento explícitamente autorizado y sus archivos permitidos; corregir sus fallos. No cambiar versiones, arquitectura o reglas de conservación sin registrar motivo e impacto.
5. Actualizar evidencia/avance antes de darlo por verificado. Explicar al estudiante qué hace el código y una decisión que pueda defender. Mostrar diff y solicitar aprobación para el siguiente; no continuar automáticamente.

### Estados y evidencia mínima

Estados técnicos: **pendiente**, **en curso**, **bloqueado** y **verificado**. Registrar además aprobación: **pendiente**, **aprobado** o **requiere cambios**. Verificado no autoriza el incremento siguiente. Código escrito, compilación, test y demostración manual son evidencias distintas. Una operación no ejecutada se registra como pendiente; un test sin casos ejecutados no valida una función.

Para cada incremento guardar `docs/evidencias/academico/Ixx-AAAA-MM-DD.md` al ejecutarlo:

```text
Incremento y criterios AC relacionados:
Autorización recibida / archivos permitidos / aprobación al cierre:
Revisión / versiones / dispositivo y API:
Archivos cambiados y decisión técnica:
Datos ficticios y precondiciones:
Comandos / pasos / resultado esperado:
Resultado real / salida / número de pruebas:
Fallos, correcciones y comprobaciones repetidas:
Capturas o artefactos pequeños saneados:
Limitaciones / estado / siguiente incremento:
Explicación breve del estudiante:
```

Usar commits pequeños por incremento una vez revisados. Un commit local conserva el avance; publicar en GitHub o distribuir APKs requiere la autorización aplicable. No guardar builds, bases, credenciales ni llaves en Git.

### Comandos previstos, aún no ejecutados para academic

Desde `academic/`, después de crearlo en I01:

```bash
./gradlew --version
./gradlew :domain:test
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

En Windows usar `gradlew.bat` en lugar de `./gradlew`; en Omarchy usar la terminal de Android Studio o Bash. La última tarea necesita dispositivo/emulador conectado y solo demuestra pruebas si realmente existen y se ejecutan. Los comandos del wrapper actual en la raíz no crean por sí mismos los módulos académicos.

## 9. Riesgos, límites y decisiones pendientes

| Riesgo o dato pendiente | Acción concreta | Momento |
| --- | --- | --- |
| PDF/rúbrica ausente | Recibirlo y contrastar I01–I12, versiones, UI, archivos permitidos, SQLite/Room y aprobaciones. No atribuirle requisitos que solo aparecen en el beta. | Antes de declarar conformidad académica y confirmar la lista de archivos permitidos por incremento. |
| Versiones del beta diferentes a las previas | Matriz oficial + prueba de compilación aislada; no cambiar las versiones exigidas sin aprobación. | I01 |
| Falta de dispositivo o virtualización | Preferir Android propio con depuración USB; registrar API y disponibilidad antes de comprometer fechas. | I01 |
| Poco tiempo o experiencia | Mantener el recorrido local; aplazar mejoras visuales, filtros, gráficos y API. Revisar horas después de I01/I05. | Cada fase |
| Cancelación del selector o proveedor no disponible | Resultado cancelado/error, sin éxito falso; comprobar salida parcial y política de limpieza. | I09 |
| Fallo de BD o carrera con revocación | Cola serial, persistir revocación antes del borrado y probar fallos de cada etapa; no deshacer revocación si falla DELETE. | I05–I10 |
| Alcance confundido con el sistema ampliado | Registro propio de I01–I12; conservar backend/apps previas y no dar por implementada su integración. | Toda la entrega |

La fase 2 y cualquier integración, cliente de red o transmisión permanecen fuera de autorización, incluso al terminar I12; requieren una nueva solicitud explícita. Los eventos de llamadas/mensajes de esta entrega siguen siendo ficticios y su modelo no se reutiliza como contrato remoto sin revisión.

## 10. Estado y siguiente paso concreto

**Código implementado hasta I10.** La pantalla local conecta consentimiento, simulación, SQLite, CSV manual, borrado y revocación con sus errores/reintentos. Pruebas y artefactos exactos en [evidencia I10](evidencias/academico/I10-2026-10-22.md), uso en [manual académico](USO_ACADEMICO.md). No se trata de la fase 2 ni de una integración con los módulos históricos.

**Detenerse antes de I11**, que necesita nueva autorización. I11 revisará seguridad/SSDF y I12 consolidará evidencias/rúbrica/entrega. Siguen pendientes Sync en Android Studio, dispositivo físico y contraste del PDF; no se dan por hechos por compilar o aprobar pruebas en un emulador.
