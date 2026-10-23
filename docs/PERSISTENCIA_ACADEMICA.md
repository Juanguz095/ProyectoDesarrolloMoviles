# Persistencia académica — I05 e I06

Solo para el build `academic/`. Implementación conectada a la pantalla I07–I10, sin bibliotecas nuevas. SQLite y sus transiciones cuentan con ejecución instrumentada en Android; dispositivos, revisiones y resultados exactos se registran en [I10](evidencias/academico/I10-2026-10-22.md).

## Esquema y conservación

`AuditDatabase` es un SQLiteOpenHelper; archivo privado `auditlab-events.db`, versión actual **2**. No tiene permisos de red ni acceso a comunicaciones. Los datos de cada prueba se aíslan en una BD temporal con UUID y nunca se borran bases existentes para aprobar tests.

| Tabla | Datos | Reglas |
| --- | --- | --- |
| `events` | UUID, instante numérico, tipo, dirección, marca simulada y snapshot completo de aceptación. | Contexto no depende de una fila mutable; UUID canónico, enums, política e instantes validados. Índice por timestamp descendente/UUID ascendente. |
| `acceptance` | Fila singleton con estado, ID/política/fecha si Granted, fecha opcional y limpieza pendiente si Revoked. | Ausencia = Pending, nunca Granted. CHECK impide decisiones incoherentes y una segunda fila. |

**Migración 1 → 2:** añade únicamente `acceptance`, conserva eventos, IDs, contexto e índice. Los eventos heredados no implican aceptación automática. Una versión desconocida falla explícitamente, sin DROP/recreación de tablas. La prueba de migración Android reconstruye el esquema v1 quitando solo la tabla nueva de su BD de prueba; ese DROP no aparece en una migración de producción.

La propuesta inicial contemplaba SharedPreferences con commit. Se eligió SQLite también para la decisión: permite guardar todos sus campos de forma atómica, sin depender de un valor en memoria que pudiera quedar actualizado tras un commit fallido de preferencias. Esto no añade dependencias ni modifica herramientas; se mantiene el contrato AcceptanceStore.

No hay retención temporal automática. Los eventos permanecen hasta borrado o revocación. El repositorio y el trigger SQL impiden superar 1000; una petición de cupo mayor no amplía ese máximo. Historial devuelve como máximo 100 sin eliminar los anteriores. I09 implementa una instantánea completa para exportación, independiente del límite de visualización.

## Transacciones y fallos

- `SQLiteEventRepository`: comprobar duplicado, contar e insertar dentro de una misma transacción. No hay contador en memoria; dos conexiones independientes se serializan mediante SQLite. Borrado también transaccional.
- `SQLiteAcceptanceStore`: confirmar cada decisión en su propia transacción. Para revocar, AuditService guarda primero Revoked con limpieza pendiente, luego intenta borrar y después confirmar la limpieza. Un rollback de DELETE no deshace el guardado anterior.
- Limpieza pendiente bloquea nueva aceptación en AcceptanceService y también en el adaptador. Una decisión corrupta o ilegible nunca se convierte en aceptación. Se permite intentar guardar una retirada nueva sin tener que decodificar antes la decisión dañada.
- Si falla guardar la retirada inicial, se informa error y la instancia de AuditService bloquea sus operaciones protegidas. No se promete retirada tras reiniciar ni en otra instancia si esa escritura no llegó a confirmarse.
- Los adaptadores traducen fallos esperados de SQLite/decodificación a StorageException, sin exponer detalles internos ni devolver éxitos vacíos. Ninguno puede ejecutarse en el hilo principal. No se permite anidar una escritura en una transacción ajena que pudiera deshacerla.

## Integración actual

AuditApplication conserva AuditSession fuera de la Activity recreable. Comparte una instancia de AuditDatabase y un coordinador entre servicios/almacenes. Composición equivalente a la utilizada:

```kotlin
val coordinator = SynchronizedAuditCoordinator()
val database = AuditDatabase(applicationContext)
val events = SQLiteEventRepository(database)
val acceptance = SQLiteAcceptanceStore(database, coordinator)
val clock = EpochClock { System.currentTimeMillis() }
val ids = EventIdGenerator { java.util.UUID.randomUUID() }
val decisions = AcceptanceService(acceptance, clock, ids, "1.0", coordinator)
val audit = AuditService(events, acceptance, clock, ids, "1.0", coordinator)
```

Los métodos se ejecutan en un worker compartido, con UI bloqueada durante la lectura inicial y sin aceptación automática. I08 implementa cola/busy/observadores/cierre detrás de operaciones pendientes. El helper puede reabrirse después de cerrar; la rotación conserva la misma sesión. La exclusión del dominio no coordina procesos distintos; la app principal no los declara.

La creación de tablas/índice/trigger usa IF NOT EXISTS para dos conexiones que abren simultáneamente una base nueva; se corrigió tras un fallo Android real. No sustituye las restricciones ni recrea tablas con pérdida. snapshot() consulta hasta 1001 filas para detectar un almacenamiento inválido; ExportService rechaza más de 1000 antes de escribir, sin exportar un subconjunto silencioso.

## Pruebas y reproducción

| Suite actual | Ejecutadas | Resultado y alcance |
| --- | --- | --- |
| Kotlin/JUnit del dominio | 93 | Aprobadas; modelos, reglas, CSV, aceptación, cola y exportación con dobles en memoria. |
| SQLite host/Python | 15 | Todas aprobadas; DDL extraído de Kotlin, restricciones, reapertura, transacciones y migración SQL en esta máquina. No ejecutan APIs Android. |
| SQLiteEventTests | 13 | Aprobadas en Android; cupo, orden, transacciones, concurrencia y reapertura. |
| SQLiteAcceptanceTests | 16 | Aprobadas en Android; migración, decisiones, fallos y guardado independiente de retirada. |
| SimulationUiTests | 6 | Consentimiento, cuatro botones, busy, recreación, reapertura y recuperación de error. |
| ExportUiTests | 10 | Destino/streams, todos los IDs, cero eventos, cancelación, errores y URI file rechazada. |
| PrivacyUiTests | 9 | Confirmaciones, fallos de cada etapa, reintentos y callbacks tardíos frente a borrado/revocación. |

Desde la raíz de esta nube:

```bash
python3 .tooling/academic-setup/run-gradle.py :domain:test :domain:build :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --rerun-tasks --no-daemon
python3 academic/tools/check_sqlite_schema.py
```

En Android Studio, abrir `academic/`, seleccionar JDK17/SDK36 y conectar Android API26+ con depuración autorizada; comprobar conexión con `adb devices -l`. Desde `academic/`:

```bash
./gradlew :domain:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

El runner `com.auditlab.academic.tests.AuditLabTestRunner` utiliza Instrumentation del framework y emite estados estándar para los **54 casos**. Opcionalmente acepta `-e testGroup PrivacyUiTests` u otro grupo del manual, y rechaza un filtro sin casos. No necesita nuevas dependencias. Los errores se reportan como fallos, no se omiten. Informes del comando conectado en `academic/app/build/reports/androidTests/connected` y `outputs/androidTest-results/connected`; verificar que sean nuevos y ejecuten casos reales, no solo compilen el APK.

La Activity es funcional hasta I10. Los tests de solo SQLite cierran/reabren el helper; no equivalen a detener el proceso. I10 añade un recorrido separado con detención/relanzamiento real, selector del sistema y comparación de IDs, documentado en su evidencia. Android puede mantener cambios confirmados en WAL: una copia fuera del proceso debe incluir DB y su archivo -wal, no copiar únicamente el DB principal y declarar pérdida de datos. No se borra WAL para conseguir un test satisfactorio.

Manual de la app y recuperación de errores: [USO_ACADEMICO.md](USO_ACADEMICO.md). Evidencias históricas [I05](evidencias/academico/I05-2026-10-22.md), [I06](evidencias/academico/I06-2026-10-22.md) y verificación integrada [I10](evidencias/academico/I10-2026-10-22.md).

I11 añade decodificación estricta de tipos INTEGER/TEXT: ni texto numérico malformado, ni REAL, ni un entero truncado pueden convertirse en un evento o aceptación válidos. Las pruebas de filas dañadas rechazan la lectura y siguen permitiendo intentar guardar una revocación nueva. [Revisión y resultados](evidencias/academico/I11-2026-10-23.md).
