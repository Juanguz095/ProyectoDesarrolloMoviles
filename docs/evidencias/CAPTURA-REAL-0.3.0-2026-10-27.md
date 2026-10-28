# Captura real — AuditLab 0.3.0-rc1

27 de octubre de 2026, America/Lima (ejecuciones del 28 en UTC).

## Cambio de alcance implementado

La solicitud exige eventos reales de llamadas/SMS. La candidata anterior 0.2 solo
aceptaba eventos simulados. Se amplían P3.3, P5.3, P6.2 y sus validaciones de P7/P8.

- Agente: autorización adicional, recibo local de alcance/fecha, permisos opcionales
  READ_SMS/READ_CALL_LOG, servicio foreground visible `specialUse`, observadores y
  reconciliación de las filas de Android. Consulta exclusivamente `_id/date/type`.
- Detecta SMS recibido/enviado y llamadas recibidas/salientes/perdidas/rechazadas
  según el registro del sistema. Excluye borradores, fallidos y el historial anterior
  a la activación. No consulta contenido, números, contactos, grabaciones ni chats/RCS.
- Batería/conexión se registran automáticamente. El servicio intenta el envío y
  WorkManager conserva el reintento. Mantiene pausa, retirada y aislamiento existentes.
- Reconciliación por ventana autorizada: no se pierden llamadas largas porque la fecha
  del registro sea el inicio y su inserción ocurra al terminar. Regresión JVM específica.
  Caché de IDs observados evita recapturar filas cuyo evento enviado haya salido de Room
  por capacidad; se limita a la ventana vigente y se reinicia con una activación nueva.
- La API conserva `isSimulated=false` y valida categorías/códigos/direcciones mediante
  listas cerradas. Flyway V3 amplía checks sin modificar las migraciones anteriores.
- El Centro admite filtros `sms`/`call`, títulos legibles y marca Real/Simulado, detalle
  y CSV con la misma distinción. No cambian las restricciones admin/auditor.

## Comprobaciones ejecutadas

| Comprobación | Resultado |
| --- | --- |
| JVM común | 75 aprobadas |
| Agente JVM/Robolectric | 26 aprobadas |
| Centro JVM/Robolectric | 9 aprobadas, incluida presentación de SMS real |
| Ktor unitarias | 8 aprobadas |
| PostgreSQL real aislado | 54 aprobadas, sin omitidas |
| Contrato OpenAPI | 49 aprobadas |
| Herramientas | 32 aprobadas |
| Total host | 253 aprobadas |
| Recorrido Android final | 1 caso integrado aprobado; cinco tipos de eventos reales |
| Lint debug | 0 errores; 6 avisos agente/2 centro |
| Auditoría de APK | Permisos previstos, servicio no exportado, TLS/loopback, firma y Dex aprobados |
| Actualización de base existente | 30 035 eventos originales conservados, mismo fingerprint |

La actualización preservó los eventos anteriores a V3; los recorridos posteriores añaden
sus propios datos ficticios. Las APKs mantienen versión mínima 26 y destino/compilación 35;
no se añadieron dependencias. Firma debug igual a la entrega anterior para actualizar
sin desinstalar. No equivale a la firma de distribución del responsable.

## Recorrido nativo final

Emulador Android 9/API28 dedicado `auditlab-real-api28`, sin KVM. Se usó una copia de la
imagen base inicializada, sin copiar ni modificar el overlay de datos de los AVD previos.

1. SMS previo al consentimiento: no aparece en los eventos de la sesión nueva.
2. Consentimiento e inscripción desde MainActivity real, Room y Keystore.
3. Casilla adicional obligatoria; botón de captura deshabilitado antes de aceptarla.
4. Permisos de lectura preconcedidos por ADB **solo en el AVD de pruebas**; no valida
   todavía los diálogos de permisos/restricciones de un teléfono o Android reciente.
5. SMS entrante y llamada perdida generados con el módem del emulador.
6. Llamada saliente desde el marcador Android y finalización con la tecla de colgar.
7. SMS saliente mediante el botón de la app SMS de Android; sin insertar filas falsas
   directamente en los proveedores de comunicaciones.
8. Los cinco tipos llegan a Ktor/PostgreSQL con marca real; se consultan mediante el
   cliente común del Centro. Todos excluyen campos privados y fechas anteriores al inicio.
9. Segundo barrido sin nuevos IDs; exportación CSV con marca real.
10. Retirada desde la pantalla: servicio detenido, credencial eliminada y cola vacía.

El Centro Android 0.3 tiene pruebas de UI en JVM; no se repitió su recorrido nativo
completo de 17 etapas ni dos teléfonos para esta candidata. El informe no atribuye
los 278 casos históricos de 0.2 a estos APK nuevos.

## Fallos conservados y correcciones

Se conservaron fallos iniciales de lint (feature telephony opcional), compilación de la
prueba (cursor explícito, independencia de constantes internas) y migración (nombre
real `events_metadata_check`). Las repeticiones finales indicadas arriba pasaron.

Primer arranque bloqueado por espacio: no se borraron imágenes, bases, fixtures ni datos.
Primer recorrido: el orquestador no reconocía el marcador porque `run-as test` no existe
como binario en API28; se cambió a un shell. Segundo: el marcador iniciaba lentamente la
llamada, y el driver intentaba cancelarla antes de que existiera. Ahora espera el módem
y cuelga desde Android. Ambos intentos fallidos se conservan. Hubo un recorrido completo
posterior aprobado y una repetición sobre el APK final después de la corrección de
llamadas largas; únicamente esta última vincula los hashes de entrega.

Evidencias privadas: `.tooling/real-events-validation/`: XML JVM, logs de compilación,
PostgreSQL, native-real.log, native-verdict.json, native-result.json, native-artifacts.json,
auditoría, fingerprint de actualización e intentos anteriores. No publicar logs crudos.
[Resumen sin credenciales](CAPTURA-REAL-0.3.0-2026-10-27.json).

## Límites de entrega

No hay ejecución de PowerShell/Windows ni teléfonos físicos en este host Linux.
No se acredita Android reciente, recepción por una red celular comercial, RCS, apps
de mensajería, distribución en Google Play, HTTPS de piloto ni firma del responsable.
Android puede restringir READ_SMS/READ_CALL_LOG por versión, instalador o política.
Sin permiso la app muestra la fuente no autorizada y no la sustituye por simulación.
El servicio se inicia por acción del usuario y no reinicia la captura de telefonía
después de morir el proceso/reiniciar el equipo. La revocación remota se conoce en el
siguiente contacto. [Uso y actualización](../CAPTURA_REAL.md).
