# P3.8/P5.6/P6.6/P7.6 — notificaciones de WhatsApp

Fecha: 29 de octubre de 2026, UTC. Revisión: árbol de trabajo sin commit; commit
base, conteos y SHA256 de los cuatro APK instalados en el [informe JSON](WHATSAPP-0.4.0-2026-10-29.json).
Versión debug 0.4.0-preview, código 5. [Guía funcional y límites](../WHATSAPP_NOTIFICACIONES.md).

## Implementado

- Agente: consentimiento adicional por sesión, permiso de acceso a notificaciones
  de Android, filtro exclusivo WhatsApp/Business, descarte de mensajes previos,
  resúmenes y respuestas locales reconocibles. Servicio visible con DETENER.
- API: autorización de dispositivo, contexto de consentimiento y sesión UUID;
  lectura admin/auditor auditada, aislamiento, deduplicación y rechazo de datos
  inválidos. Últimas 20 vistas previas en RAM, máximo 5 minutos; cierre tras 30
  segundos sin actividad. Una sesión antigua no puede cerrar una nueva.
- Auditoría: recibo de consentimiento, inicio/fin y consultas; migraciones V5/V6
  sin contenido. Sin texto en Room, eventos, CSV, PostgreSQL o respaldos SQL.
- Centro: consulta con la sesión JWT/refresh existente, estados de conexión y
  sesión, retirada del texto tras detener o fallar; FLAG_SECURE. Se evita que una
  respuesta tardía repueble el estado después de pasar la actividad al fondo.

## Comprobaciones ejecutadas

| Comprobación | Resultado final |
| --- | --- |
| JVM común | 79 aprobadas |
| Agente/Robolectric, incluido parser | 33 aprobadas |
| Centro/Robolectric, incluidos vista previa, desconexión y limpieza | 14 aprobadas |
| Backend unitario, límites/TTL/sesiones | 15 aprobadas |
| Integración PostgreSQL aislado | 56 aprobadas |
| Total JVM/Robolectric/PostgreSQL | **197; cero fallos, errores u omitidas** |
| Herramientas Python | 32 aprobadas |
| Pruebas de contrato Python | 49 aprobadas |
| Contrato en vivo | 23 respuestas válidas |
| OpenAPI/documentación | 4 contratos; 42 tareas/15 criterios trazados |
| Lint | 0 errores; Agente 12 avisos, Centro 5 avisos/1 información |
| Compilación/APK | Ambas apps y pruebas compilan; permisos, red, firma y clases correctos |
| Dos AVD, APK finales | **2 pruebas nativas aprobadas**, ninguna fallida u omitida |

Comandos, todos con salida final 0:

```bash
python3 scripts/gradle.py :apps:agent:assembleDebug :apps:control:assembleDebug :apps:common:test :apps:agent:testDebugUnitTest :apps:control:testDebugUnitTest :backend:ktor:test :backend:ktor:installDist --offline --max-workers=2
python3 scripts/check_database.py
python3 scripts/gradle.py :apps:agent:assembleDebugAndroidTest :apps:control:assembleDebugAndroidTest :apps:agent:lintDebug :apps:control:lintDebug --offline --max-workers=2
python3 scripts/gradle.py :apps:control:assembleDebug :apps:control:assembleDebugAndroidTest :apps:control:testDebugUnitTest :apps:control:lintDebug --offline --max-workers=2
python3 -m unittest discover -s scripts/tests -q
.venv/bin/python -m unittest discover -s contracts/tests -q
.venv/bin/python scripts/check_contract.py
python3 scripts/local_fixture.py run -- .venv/bin/python scripts/check_live_contract.py
python3 scripts/check_apk.py
python3 scripts/check_docs.py
python3 scripts/check_whatsapp_pair.py --agent-serial emulator-5592 --control-serial emulator-5594
git diff --check
```

El verificador PostgreSQL usa una base nueva y comprueba migraciones repetibles,
roles, CLI, reinicio y disponibilidad; elimina únicamente sus recursos temporales.
La base QA persistente recibe V5/V6 sin borrar sus datos existentes.

## Recorrido nativo observado

Dos AVD dedicados: Agente API28 (`emulator-5592`), Centro API26
(`emulator-5594`). Se concedió y retiró el acceso real a notificaciones mediante el
sistema Android. El botón permanece deshabilitado antes del consentimiento, y
conceder acceso Android por sí solo no inicia la captura. Cada nueva sesión vuelve
a exigir la casilla local.

El ensayo inyecta un `StatusBarNotification` sintético con forma MessagingStyle
en el procesamiento del listener; no instala una aplicación que suplante WhatsApp.
El parser descarta otra aplicación y una notificación anterior al consentimiento.
Dos entregas de la misma notificación producen una sola vista previa.

Con el Agente mostrando Ajustes, su servicio visible sigue enviando/renovando la
sesión. El Centro real consulta a la API y muestra el texto sintético del otro AVD.
La acción DETENER elimina la sesión y el texto del Centro. Una nueva notificación
tras detener no se transmite. Otra sesión requiere consentimiento nuevo; retirar
el permiso Android la detiene y elimina sus vistas previas.

La ronda final corresponde a los SHA256 actuales de ambos APK y ambas APK de
instrumentación; se contrastaron con los artefactos registrados al instalar.
Configuraciones privadas de prueba retiradas; Agente retirado/restablecido,
Centro sin sesión y permiso adicional Android retirado. Datos QA de servidor y
evidencias conservados fuera de `/tmp`.

## Fallos encontrados y correcciones

- Método de extracción MessagingStyle no público y constructor de prueba no
  disponible: sustituidos por AndroidX y constructor público de Android.
- Restaurar notificaciones antiguas puede perder claves Person: filtro conservador
  por identidad local/nombre cuando no están disponibles las claves.
- La base rechazaba el nuevo recurso de auditoría: V6 amplía exclusivamente la
  lista de metadatos permitidos; título/texto siguen excluidos. No se altera V5 aplicada.
- Lint detectó conversión insegura Context→Activity: se usa LocalActivity.
- Dos intentos nativos iniciales fallaron en los selectores de la prueba: controles
  fuera de la pantalla y estado del nodo de texto hijo. Se usa la semántica Compose
  y desplazamiento real; ambos intentos fallidos quedan conservados.
- La primera ronda completa pasó. Se repitió satisfactoriamente después del ajuste
  de limpieza al pasar al fondo, usando los APK finales.

## Límites y tarea siguiente

La prueba nativa valida permiso, consentimiento, servicio, parser, API y vista del
Centro con entrada sintética. **No prueba la publicación de notificaciones por una
cuenta real de WhatsApp**. P7.6 queda parcial hasta repetir con WhatsApp instalado,
un contacto de prueba y teléfonos propios. Android reciente, distribución firmada
por el responsable y piloto HTTPS/Windows siguen pendientes. No se declara cierre
total de P7.6 ni P8 por estas pruebas.
