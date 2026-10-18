# Ejecutar el MVP local

El recorrido HTTP está cubierto por `python3 -m unittest discover -s backend/api/tests -v`, con datos y credenciales temporales. La [evidencia P0.1](../../docs/evidencias/P0.1-2026-10-17.md) describe resultados y límites. La construcción Android/Ktor se documenta en [desarrollo](../../docs/desarrollo.md).

Requiere Python 3.10 o posterior. No necesita paquetes externos.

La interfaz de este prototipo corresponde a [OpenAPI 0.1](../../contracts/prototype-v0.1.yaml). El contrato raíz 0.2 describe la API Ktor futura y sus rutas nuevas todavía no existen aquí.

1. Desde la raíz del repositorio, define las credenciales locales y una clave aleatoria de al menos 32 bytes:

   ```bash
   export AUDITLAB_ADMIN_PASSWORD='define-una-clave-local'
   export AUDITLAB_AUDITOR_PASSWORD='define-otra-clave-local'
   export AUDITLAB_JWT_SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
   ```

2. Inicia el servidor:

   ```bash
   python3 backend/api/server.py
   ```

3. Abre `http://127.0.0.1:8080` en el navegador. Inicia sesión como `admin`, genera un código de inscripción, pégalo en el panel del agente, acepta el consentimiento y envía un evento simulado. Actualiza el historial; prueba también el rol `auditor` y la revocación.

La base local se crea en `backend/api/data/auditlab.sqlite3` y conserva eventos por 30 días. Configura `AUDITLAB_RETENTION_DAYS` para usar un valor entre 1 y 3650. `AUDITLAB_DATA_DIR` permite guardar la base fuera del checkout. `AUDITLAB_PORT` cambia el puerto; el servidor rechaza direcciones de escucha distintas de loopback.

## Límites

- El agente es una simulación en navegador; la credencial vive solo en memoria de la pestaña. No se instala en Android.
- Los eventos se crean únicamente al pulsar **Registrar y enviar evento simulado**.
- El servidor local usa HTTP solo en `127.0.0.1`. No se debe exponer en una red ni usar para dispositivos reales.
- Para un piloto se requiere migrar a un servidor HTTPS endurecido, PostgreSQL, gestión segura de credenciales, tokens de refresco, límites de solicitud y apps Android nativas con notificación persistente y almacenamiento local protegido.
