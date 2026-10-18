# Autenticación y sesiones de la API Ktor

Relación: **P3.1**. Rutas implementadas bajo `/v1/auth`; [OpenAPI 0.2](../contracts/openapi.yaml) sigue describiendo otras rutas aún pendientes. Esta autenticación pertenece a Ktor/PostgreSQL. Las credenciales y el JWT simplificado del prototipo Python no se reutilizan.

## Configuración y arranque

1. Construir la distribución: `python3 scripts/gradle.py :backend:ktor:installDist --no-daemon`.
2. Aplicar migraciones V1 y V2 y repetir `grant-runtime` con la cuenta administrativa siguiendo [base de datos](base-datos.md). V2 añade contadores compartidos; no modifica V1 ni borra datos. No arrancar el servicio con la cuenta administrativa.
3. Crear las cuentas admin/auditor mediante `bootstrap-user`. No hay cuenta ni contraseña por defecto.
4. Suministrar las tres variables runtime `AUDITLAB_DB_JDBC_URL`, `AUDITLAB_DB_USER`, `AUDITLAB_DB_PASSWORD` y `AUDITLAB_AUTH_SIGNING_KEY_BASE64` mediante configuración segura. La clave de firma debe ser **nueva, aleatoria, de al menos 32 bytes, codificada en Base64 estándar**, y compartirse únicamente entre instancias autorizadas de esta API. No introducirla en Git, URLs, capturas, chat o logs. No usar el secreto del prototipo.
5. Arrancar `backend/ktor/build/install/ktor/bin/ktor`. Escucha en loopback, puerto 8081 por defecto. El piloto requiere terminación HTTPS y validación desde Android, aún pendientes de P3.6/P8.1.

Se puede generar la clave con un generador criptográfico desde un gestor de secretos y suministrarla directamente al mecanismo de configuración, sin dejarla en archivos del repositorio. La fixture de pruebas genera una clave efímera en memoria; no es una configuración persistente de piloto.

| Variable | Valor predeterminado / validación |
| --- | --- |
| `AUDITLAB_AUTH_SIGNING_KEY_BASE64` | Sin valor predeterminado; Base64 válido con al menos 32 bytes decodificados. |
| `AUDITLAB_AUTH_ISSUER` | `auditlab-api`; identificador de 1–128 caracteres ASCII permitidos. |
| `AUDITLAB_AUTH_AUDIENCE` | `auditlab-control`; misma validación. |
| `AUDITLAB_AUTH_LOGIN_ACCOUNT_LIMIT` | 5 solicitudes de login por cuenta/minuto, sin distinguir mayúsculas; rango 1–100. |
| `AUDITLAB_AUTH_LOGIN_CLIENT_LIMIT` | 30 solicitudes de login por cliente/minuto; rango 1–1000. |
| `AUDITLAB_AUTH_LOGIN_GLOBAL_LIMIT` | 100 solicitudes de login globales/minuto; rango 1–10000. |
| `AUDITLAB_AUTH_REFRESH_CLIENT_LIMIT` | 60 renovaciones por cliente/minuto; rango 1–1000. |
| `AUDITLAB_AUTH_REFRESH_GLOBAL_LIMIT` | 300 renovaciones globales/minuto; rango 1–10000. |

Una variable `AUDITLAB_AUTH_*` presente activa la validación de configuración completa de autenticación; configuración parcial o inválida impide el arranque. Sin variables de autenticación, los endpoints auth devuelven 503 y salud/disponibilidad conservan su utilidad de infraestructura. `/ready` solo comprueba la BD; no prueba credenciales ni todas las rutas. El comprobador de salud independiente elimina DB/auth de su copia del entorno.

## Operaciones

| Ruta | Entrada y resultado |
| --- | --- |
| `POST /v1/auth/login` | JSON estricto `{username, password}`. Devuelve usuario/rol, `sessionId`, `accessToken`, `refreshToken`, `tokenType`, `expiresIn` y `refreshExpiresIn`. Credenciales incorrectas, cuenta desconocida o desactivada devuelven el mismo 401 `UNAUTHENTICATED`. |
| `POST /v1/auth/refresh` | JSON estricto `{refreshToken}`. Consume el refresh y devuelve nuevas credenciales de la misma sesión; nunca extiende el vencimiento absoluto. |
| `POST /v1/auth/logout` | `Authorization: Bearer <accessToken>`; sin cuerpo ni parámetros. Revoca la sesión actual y sus refresh. Respuesta 204 vacía. Otro intento con la sesión revocada devuelve 401. |

Las respuestas llevan `Cache-Control: no-store` y `X-Correlation-ID` generado por el servidor. Los errores llevan `application/problem+json`, código estable y correlación; 401 incluye `WWW-Authenticate: Bearer`. Login/refresh aceptan únicamente JSON UTF-8, sin campos desconocidos, duplicados, tipos coercionados, segundo documento JSON, compresión o credenciales en query. Cuerpo máximo: 16 KiB; un exceso en auth devuelve 400 `INVALID_REQUEST`. Logout rechaza cuerpo/parámetros.

## Contraseñas y tokens

- Password4j verifica Argon2id con los parámetros guardados en el hash; una cuenta desconocida también realiza una verificación Argon2id. Hasta cuatro verificaciones simultáneas por instancia; saturación devuelve 503.
- Auth0 `java-jwt` 4.6.2 firma y verifica HS256 con algoritmo fijado. Se comprueban emisor, audiencia, `typ: at+jwt`, `token_use: access`, sujeto, sesión, rol, ID, emisión, expiración y vida máxima de 900 segundos. No se acepta refresh como access, algoritmo `none` ni otro algoritmo.
- Access: máximo 15 minutos, limitado además por el vencimiento de su sesión. Sesión/refresh: 7 días absolutos desde login. El cliente debe respetar las duraciones devueltas.
- Refresh: 32 bytes aleatorios, codificación URL-safe y prefijo propio; solo SHA-256 se guarda en PostgreSQL. El servidor no puede reconstruir un refresh a partir del hash. No incluirlo en logs ni auditoría.
- `authorized` valida el JWT y consulta el usuario/rol y sesión actuales en cada operación protegida, dentro de la transacción de la acción. Una cuenta desactivada, sesión vencida/revocada o rol cambiado invalida el access anterior. Un refresh válido de cuenta activa obtiene el rol actual.
- Los roles permitidos se comprueban en servidor. Admin y auditor pueden cerrar su propia sesión; la función común rechaza al auditor en operaciones exclusivas de admin. Las rutas de dispositivos/eventos todavía no existen y tendrán que usar esa función.

## Rotación, concurrencia y auditoría

Todas las operaciones sobre una sesión bloquean primero **su fila**. Refresh consulta el hash para localizar la sesión, bloquea sesión y después relee el refresh; logout usa el mismo orden. Dos refresh simultáneos del mismo token producen una rotación y un rechazo `REFRESH_REUSED`; ese rechazo confirma la revocación de toda la familia antes de responder. El hijo ya emitido también queda inválido. Si logout gana a refresh, refresh falla; si gana refresh, logout revoca también su hijo.

Los refresh consumidos se conservan hasta la futura limpieza de sesiones para detectar reutilización. No borrar los padres de una familia activa. Un cliente debe serializar las renovaciones y reemplazar sus credenciales de forma atómica. Si pierde la respuesta de un refresh, repetir el padre revoca la sesión: debe iniciar sesión de nuevo. No se promete recuperación transparente de ese caso.

Login correcto, login rechazado, refresh y revocación escriben auditoría mínima. Usuario conocido autenticado: actor usuario y recurso sesión; login fallido: actor sistema sin nombre ingresado. Sesión/tokens/auditoría se confirman juntos; un fallo de auditoría no produce éxito ni deja la sesión creada. El rechazo por reutilización se retorna después de confirmar su revocación. Los errores SQL devuelven 503 sin detalles de conexión; otros errores internos se sanean como 500. Correlaciones no se toman de headers del cliente.

## Límites compartidos y privacidad

Los límites usan ventanas fijas de 60 segundos y contadores PostgreSQL atómicos, compartidos entre instancias que usan la misma BD y clave. Se reservan primero cupo global y cliente; también se limita la cuenta de login normalizada antes de Argon2id. Se cuentan intentos válidos e inválidos, no solo contraseñas incorrectas. Un límite devuelve 429 `RATE_LIMITED` con `Retry-After` en segundos hasta la siguiente ventana.

`auth_rate_limits` solo guarda HMAC-SHA256 del ámbito/identificador, inicio de ventana y contador. No almacena nombres, IPs, contraseñas ni tokens en claro. Las entradas de más de diez minutos se eliminan al reservar cupo; son metadatos técnicos, no auditoría. Reiniciar el proceso conserva los contadores. Mantener relojes de instancias sincronizados; las ventanas fijas permiten ráfagas en sus bordes.

Se usa el **peer real del socket**. `X-Forwarded-For` y otros headers no cambian la identidad de cliente. Tras un proxy, los clientes compartirán su cuota; fijar políticas del proxy y límites de piloto en P3.6 sin confiar en headers arbitrarios. Los límites de cuenta permiten que otro actor agote temporalmente el cupo de una cuenta conocida; expiran a los 60 segundos y no bloquean la cuenta permanentemente. La clave nueva cambia los buckets y los access válidos; el procedimiento de rotación/revocación administrativa masiva queda para operación, no se ejecuta automáticamente.

## Comprobación reproducible

```bash
python3 scripts/gradle.py :backend:ktor:test --no-daemon
python3 scripts/check_database.py
```

La segunda usa PostgreSQL real aislado, datos ficticios, clave efímera y cuentas runtime limitadas; incluye pruebas HTTP, concurrencia, rechazo de tokens y roles, persistencia, límites, auditoría y servidor Netty instalado. Reinicia únicamente su propio proceso, comprueba refresh tras reinicio y 503 al detener su BD, y elimina sus recursos temporales. No necesita ni usa la BD/clave del usuario.

Evidencia y resultados concretos en [P3.1](evidencias/P3.1-2026-10-17.md). No se valida aquí login Android, TLS de piloto, endpoints de dispositivos/eventos, rendimiento de entrega o ejecución GitHub Actions.
