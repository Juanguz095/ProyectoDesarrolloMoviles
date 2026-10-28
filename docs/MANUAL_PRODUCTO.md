# AuditLab — Manual del producto completo

Versión candidata: **0.2.0-rc1**, dos apps Android y API Ktor/PostgreSQL.
Este documento no sustituye la evidencia de ejecución Android ni acredita un piloto publicado.

## Preparación

- Android 8/API26 o posterior, dos dispositivos propios o administrados con consentimiento.
- API con PostgreSQL migrado, cuenta admin y cuentas auditor según necesidad. Seguir [despliegue](despliegue.md).
- URL HTTPS con certificado reconocido por Android. El certificado local de QA no es un certificado de piloto.
- Compilar desde la raíz con `python3 scripts/gradle.py :apps:agent:assembleDebug :apps:control:assembleDebug`.
- APKs: `apps/agent/build/outputs/apk/debug/agent-debug.apk` y `apps/control/build/outputs/apk/debug/control-debug.apk`.
- Instalar el agente en A y el centro en B mediante `adb -s SERIAL install -r RUTA_APK` o el instalador del dispositivo.
- La variante debug sirve para desarrollo. Para entrega, construir `assembleRelease` en ambos módulos y firmar con una clave del responsable, fuera del checkout. `python3 scripts/sign_release.py --help` explica el procedimiento sin generar ni publicar claves. Conservar la misma firma al actualizar. Una firma de distribución distinta de la debug no permite actualizar esa instalación directamente: retirar primero la inscripción de desarrollo y cerrar su sesión; la instalación de entrega necesita su propia aceptación. No desinstalar ni borrar datos automáticamente para resolver la incompatibilidad.
- En emulador, `http://10.0.2.2:8081` conecta con el host local. En un teléfono usar HTTPS; esa dirección no apunta a tu servidor de Internet. No publicar el fixture QA.

## Centro de control (B)

1. Introducir el origen del servicio, por ejemplo `https://auditlab.tudominio.com` (sin `/v1`, ruta ni parámetros), usuario y contraseña. La sesión y su URL quedan cifradas con Keystore; reiniciar no debe cambiar el servidor de la sesión.
2. Pulsar **Actualizar dispositivos**. Se muestran estado administrativo y última recepción. `active` significa inscrito, no demuestra conexión en este instante.
3. Admin: **Crear código de un uso**. Entregar el código al usuario de A por un canal autorizado. Vence en diez minutos. No incorporarlo a capturas ni al informe.
4. **Ver eventos** selecciona el dispositivo. **Consultar todos los eventos** elimina esa selección.
5. Aplicar filtros: categoría `app_lifecycle`, `sync` o `device_status`; fechas con zona UTC, por ejemplo `2026-10-25T00:00:00Z`; `desde` inclusivo y `hasta` exclusivo. Búsqueda exacta por ID o código permitido (`agent_started`, `sync_succeeded`, `sync_failed`, `consent_revoked`). Vacío significa sin ese filtro.
6. Se muestra una página de hasta 50 filas. **Más eventos** consulta la siguiente; **Primera página de eventos** reinicia. Al cambiar filtros siempre se descarta el cursor anterior. Si vence, reiniciar la consulta. Dispositivos y auditoría también se paginan; actualizar vuelve al inicio.
7. **Detalle ID** consulta en servidor ID, dispositivo, fechas, consentimiento y metadatos.
8. **Guardar CSV** abre el selector Android. Se exportan todos los resultados de los filtros aplicados, no solo la página visible. Máximo 10 000: un resultado mayor se rechaza explícitamente. Cancelar no guarda reporte. Un fallo de red no abre el flujo de escritura. Un fallo del proveedor de documentos puede dejar un documento incompleto: no usarlo como evidencia; eliminarlo y repetir cuando aparezca confirmación. **Abrir último CSV guardado** abre el documento confirmado en un visor compatible; el destino válido es la URI `content://` devuelta por el proveedor, no una ruta supuesta en almacenamiento compartido. No se vuelve a exportar al abrirlo.
9. **Consultar auditoría** permite ver actor, acción, recurso, filtros, resultado, hora y correlación. La consulta también queda auditada.
10. Admin: **Revocar**, revisar el ID y **Confirmar revocación**. Cancelar no envía la operación. El servidor bloquea futuras escrituras de la credencial anterior. El agente descubre la retirada en el siguiente contacto.
11. **Salir** pide confirmación y elimina la sesión local. El resultado distingue cierre confirmado por servidor de cierre solo local. Si no hay confirmación remota, los tokens remotos siguen sujetos a su caducidad; no se presenta el aviso remoto como exitoso.

El auditor consulta y exporta; no crea códigos ni revoca. El servidor comprueba el rol independientemente de los botones.
Si una sesión antigua carece de origen protegido o no puede descifrarse, usar **Borrar sesión local** e iniciar de nuevo. No se envía su token a otra URL.

## Agente (A)

1. Leer datos y límites, marcar aceptación y pulsar **Registrar consentimiento**. Sin hacerlo no hay inscripción ni captura.
2. Introducir el código nuevo generado en B e inscribir. La aceptación se conserva junto a esa inscripción. El código no se guarda en el estado recuperable de Android; si se recrea el formulario antes de inscribir, puede ser necesario introducirlo de nuevo.
3. Conceder notificaciones y habilitar el canal de monitoreo. El indicador visible permite abrir AuditLab para pausar o revocar. Si está denegado, bloqueado o retirado, el agente permanece pausado: no genera ni transmite eventos.
4. **Generar evento simulado** crea un evento de prueba propio. Para SMS/llamadas reales,
   usar la autorización adicional y seguir [Captura real](CAPTURA_REAL.md). La candidata
   0.3 solicita opcionalmente lectura de metadatos de SMS/llamadas; no consulta contenido,
   números, contactos ni audio.
5. El evento se guarda primero en Room. Ante fallo de red se mantiene pendiente; WorkManager reintenta con red disponible, en lotes de hasta 100 y con los mismos IDs. Android decide cuándo ejecuta el trabajo; no se promete entrega inmediata. También existe trabajo periódico de 15 minutos y limpieza independiente de la red.
6. **Pausar** retira el indicador y bloquea nuevas capturas/envíos; pendientes válidos permanecen hasta reanudar o revocar. **Habilitar indicador y reanudar** permite reanudar explícitamente.
7. Retención local: siete días, hasta 10 000 filas. Primero se libera historial ya enviado; nunca se descarta un pendiente vigente para aceptar otro. Si no hay espacio, se informa y se rechaza la nueva captura. Eventos inválidos se separan como fallidos; no se reintentan continuamente.
8. **Revocar consentimiento** bloquea nuevas operaciones antes de contactar al servidor, guarda la retirada y borra la cola. Sin red permanece una retirada pendiente. La única credencial retenida se usa exclusivamente para avisar de esa retirada; los trabajos de eventos no pueden usarla. Reiniciar no reactiva el monitoreo.
9. WorkManager intenta avisar al recuperar la red, con hasta siete reintentos automáticos además del intento inicial; Android decide cuándo ejecutarlo. **Reintentar retirada pendiente** permite volver a intentarlo explícitamente. Si aparece “no confirmado”, comprobar el centro; no equivale a revocación confirmada en servidor. Tras terminar la retirada local se puede iniciar una inscripción nueva con aceptación y código nuevos.

## Errores y recuperación

| Situación | Acción |
| --- | --- |
| URL/red inaccesible | Corregir URL antes de inscribir/iniciar sesión; revisar red. Con inscripción activa la URL queda ligada a la credencial. |
| TLS rechazado | Corregir certificado/servidor; no desactivar validación TLS. |
| Notificación no disponible | Conceder permiso/habilitar canal en ajustes y reanudar explícitamente. |
| Credencial rechazada o estado local inconsistente | Retirar inscripción; no se reactiva mediante reintentos de eventos. |
| Almacenamiento no confirma escritura/borrado | La UI informa bloqueo; reintentar retirada. No se acredita una operación durable si el almacenamiento falla. |
| CSV sin confirmación | No presentar el archivo como reporte completo; repetir tras corregir la causa. |
| API/BD caída | Operador revisa `/health`, `/ready` y registro sin secretos; seguir despliegue y restauración. |

## Repetir la aceptación en dispositivos propios

Registrar versión Android, hashes de APK y revisión Git. Probar: consentimiento denegado/aceptado, inscripción, indicador, evento visible en B, filtros/detalle/CSV/auditoría, modo avión, reinicio del agente, recuperación sin duplicados, pausa por retirar notificaciones, revocación local offline y remota, logout/refresh y rol auditor. Usar datos ficticios, no incluir contraseñas/tokens/códigos en las evidencias. La [matriz de entrega](evidencias/P5-P8-2026-10-25.md) indica qué se ha ejecutado realmente.


## Verificación automatizada de desarrollo

La suite `scripts/check_android.py` ejecuta las actividades reales contra el fixture propio en dos emuladores dedicados. Comprueba inscripción/lectura cruzada, código desde la UI, reinicios de proceso, red desconectada y recuperación por WorkManager, bloqueo de notificaciones desde ajustes del sistema, retirada remota/local offline, CSV real con cancelación/rotación, rol auditor, texto al 150 % y TLS del cliente Android.

El test TLS primero rechaza el certificado QA por defecto, habilita **solo en su proceso de prueba** la CA exacta conservando validación de hostname y vuelve a comprobar el rechazo al restaurar la confianza original. Eso no instala certificados en el sistema ni cambia la app de producción. El piloto exige su certificado reconocido por Android.

Ver precondiciones, informes, comandos y paquete con APKs/API/código en [pruebas integradas](PRUEBAS_INTEGRADAS.md). La [última repetición](evidencias/P7-P8-2026-10-26.md) conserva su resultado y hashes; los fallos corregidos anteriores permanecen en la [evidencia previa](evidencias/P5-P8-2026-10-25.md).
