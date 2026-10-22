# Persistencia académica — I05 e I06

Solo para el build `academic/`. Esta implementación local usa APIs Android y ninguna biblioteca nueva. Su estado real: compila y tiene pruebas instrumentadas, pero todavía no se ha ejecutado en Android.

## Esquema y conservación

`AuditDatabase` es un SQLiteOpenHelper; archivo privado `auditlab-events.db`, versión actual **2**. No tiene permisos de red ni acceso a comunicaciones. Los datos de cada prueba se aíslan en una BD temporal con UUID y nunca se borran bases existentes para aprobar tests.

| Tabla | Datos | Reglas |
| --- | --- | --- |
| `events` | UUID, instante numérico, tipo, dirección, marca simulada y snapshot completo de aceptación. | Contexto no depende de una fila mutable; UUID canónico, enums, política e instantes validados. Índice por timestamp descendente/UUID ascendente. |
| `acceptance` | Fila singleton con estado, ID/política/fecha si Granted, fecha opcional y limpieza pendiente si Revoked. | Ausencia = Pending, nunca Granted. CHECK impide decisiones incoherentes y una segunda fila. |

**Migración 1 → 2:** añade únicamente `acceptance`, conserva eventos, IDs, contexto e índice. Los eventos heredados no implican aceptación automática. Una versión desconocida falla explícitamente, sin DROP/recreación de tablas. La prueba de migración Android reconstruye el esquema v1 quitando solo la tabla nueva de su BD de prueba; ese DROP no aparece en una migración de producción.

La propuesta inicial contemplaba SharedPreferences con commit. Se eligió SQLite también para la decisión: permite guardar todos sus campos de forma atómica, sin depender de un valor en memoria que pudiera quedar actualizado tras un commit fallido de preferencias. Esto no añade dependencias ni modifica herramientas; se mantiene el contrato AcceptanceStore.

No hay retención temporal automática. Los eventos permanecen hasta borrado o revocación. El repositorio y el trigger SQL impiden superar 1000; una petición de cupo mayor no amplía ese máximo. Historial devuelve como máximo 100 sin eliminar los anteriores. La consulta completa para exportación se resolverá en I09.

## Transacciones y fallos

- `SQLiteEventRepository`: comprobar duplicado, contar e insertar dentro de una misma transacción. No hay contador en memoria; dos conexiones independientes se serializan mediante SQLite. Borrado también transaccional.
- `SQLiteAcceptanceStore`: confirmar cada decisión en su propia transacción. Para revocar, AuditService guarda primero Revoked con limpieza pendiente, luego intenta borrar y después confirmar la limpieza. Un rollback de DELETE no deshace el guardado anterior.
- Limpieza pendiente bloquea nueva aceptación en AcceptanceService y también en el adaptador. Una decisión corrupta o ilegible nunca se convierte en aceptación. Se permite intentar guardar una retirada nueva sin tener que decodificar antes la decisión dañada.
- Si falla guardar la retirada inicial, se informa error y la instancia de AuditService bloquea sus operaciones protegidas. No se promete retirada tras reiniciar ni en otra instancia si esa escritura no llegó a confirmarse.
- Los adaptadores traducen fallos esperados de SQLite/decodificación a StorageException, sin exponer detalles internos ni devolver éxitos vacíos. Ninguno puede ejecutarse en el hilo principal. No se permite anidar una escritura en una transacción ajena que pudiera deshacerla.

## Integración futura

Compartir una instancia de AuditDatabase y un coordinador por almacenes; crear las dependencias fuera de la Activity recreable. Ejemplo de composición, **aún no conectado a la UI**:

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

Ejecutar métodos en un worker compartido, mantener la UI bloqueada durante la lectura inicial y no llamar a accept automáticamente. La cola Android, busy y cierre después de operaciones pendientes se implementarán en I08. La instancia de SQLiteOpenHelper se cierra al terminar su dueño, nunca durante una operación. La exclusión del dominio no coordina procesos distintos; esta app no declara procesos adicionales.

## Pruebas y reproducción

| Suite actual | Ejecutadas | Resultado y alcance |
| --- | --- | --- |
| Kotlin/JUnit del dominio | 75 | Todas aprobadas; modelos, reglas, CSV y aceptación con dobles en memoria. |
| SQLite host/Python | 15 | Todas aprobadas; DDL extraído de Kotlin, restricciones, reapertura, transacciones y migración SQL en esta máquina. No ejecutan APIs Android. |
| SQLiteEventTests | 0 de 12 | Compiladas dentro del APK de instrumentación; ejecución Android pendiente. |
| SQLiteAcceptanceTests | 0 de 15 | Compiladas; incluyen reapertura, migración, fallos de escritura/borrado, decisión corrupta, políticas y rechazo en hilo principal. |

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

El runner `com.auditlab.academic.tests.AuditLabTestRunner` usa Instrumentation del framework, emite estados estándar por caso y ejecuta los 27 casos completos; no implementa filtros. No necesita nuevas dependencias. Los errores de un caso se reportan como fallos, no se omiten. Informes del comando conectado bajo `academic/app/build/reports/androidTests/connected` y `outputs/androidTest-results/connected`; verificar que sean nuevos y que indiquen **27 ejecutados**, no solo APK compilado. En esta nube la falta de dispositivo/emulador y el fallo ADB impiden esa ejecución.

La Activity continúa mostrando el título de I01. Estos tests no prueban la futura UI ni equivalen a una prueba después de matar el proceso/reeniciar el dispositivo: cierran y reabren el helper sobre el mismo archivo. Registrar esas pruebas adicionales al disponer de Android. Evidencias [I05](evidencias/academico/I05-2026-10-22.md) e [I06](evidencias/academico/I06-2026-10-22.md).
