# ADR 0003 — JWT por biblioteca y sesiones revocables

Estado: adoptado en P3.1. Configuración y límites en [autenticación](../autenticacion.md).

## Decisiones

- Auth0 java-jwt 4.6.2 para emitir/verificar JWT con HS256, emisor/audiencia explícitos y credencial distinta de Python. Jackson BOM 2.22.3 para el árbol JSON estricto y las dependencias de JWT. Versiones comprobadas contra metadatos Maven oficiales y compilación/JVM.
- JWT de access válido como máximo 900 segundos; sesión absoluta de 604800 segundos. Comprobar además sesión, usuario activo y rol actual en cada acción protegida, mediante la función transaccional `authorized`.
- Refresh aleatorio opaco de un uso; SHA-256 en almacenamiento. No es un JWT ni una credencial de dispositivo.
- Bloquear primero la sesión, luego releer refresh; mismo orden en logout y acciones autorizadas. Reutilización confirma revocación de toda la familia y después devuelve error. Así el rechazo no revierte accidentalmente la revocación.
- Auditoría mínima en la misma transacción antes de devolver éxito. Los errores de autenticación no incluyen nombres ingresados, hashes ni tokens. Respuestas sin caché, con correlación generada y problemas JSON saneados.
- V2 añade contadores de límites en PostgreSQL, con HMAC por ámbito/identificador. Cuenta, cliente y límite global se mantienen entre reinicios e instancias; no se confía en headers de forwarding. Cuatro verificaciones Argon2id simultáneas por instancia.
- Operaciones SQL bloqueantes se ejecutan en `Dispatchers.IO`; adquisición de pool, espera de bloqueos y sentencias tienen límites. No se instala bootstrap automático ni migraciones elevadas al arrancar.

## Consecuencias

Un access requiere consultar la BD y no funciona durante una caída; se responde 503. La revocación es inmediata para peticiones que adquieran el bloqueo después del commit; una petición concurrente ya confirmada no se revierte. Las acciones futuras deben usar el mismo control de sesión y los bloqueos específicos del recurso.

El cliente debe serializar refresh y persistir la sustitución de tokens; reintentar un padre tras perder su respuesta revoca la familia y exige login. La disponibilidad de `/ready` sigue siendo infraestructura y no comprueba todas las operaciones del producto.

La clave simétrica queda solo en el servidor. HTTPS, terminación proxy, confianza entre instancias, rotación de llaves, revocación administrativa masiva y criterios de operación final siguen bajo P3.6/P8.1. Las apps no han sido conectadas en esta tarea. Se conservan V1 y el contrato 0.2 sin reescribirlos.
