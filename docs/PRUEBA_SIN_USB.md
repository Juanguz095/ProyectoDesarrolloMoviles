# AuditLab sin USB — Windows y dos celulares

Para la versión 0.4, empieza por [la guía completa con descargas](INICIO_WINDOWS_0.4.md).
Los APK admiten HTTPS. Para conectarlos sin cables se puede usar
**Tailscale Serve**, una conexión privada entre tu PC y tus celulares. Sirve en el
mismo Wi-Fi o en redes distintas con Internet. La PC sigue siendo el servidor y
debe permanecer encendida, con AuditLab y Tailscale funcionando.

No necesitas contratar un servidor o comprar un dominio para esta modalidad.
Tailscale es una aplicación adicional que debes instalar e iniciar sesión en los
tres equipos. La cuenta de Tailscale conecta los equipos; la cuenta `admin` de
AuditLab sigue siendo la del servidor Windows.

## 1. Encender el servidor en Windows

Abrir Docker Desktop. Desde la carpeta donde extrajiste el servidor:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Iniciar-AuditLab.ps1
```

Conservar esa ventana abierta. En el navegador de la **PC**, comprobar:

```text
http://127.0.0.1:8081/ready
```

Debe mostrar `{"status":"ready"}`. La dirección terminada solamente en `/` no
tiene página web. Si `/ready` no responde, resolver primero el inicio del servidor.

## 2. Conectar la PC y ambos celulares a Tailscale

En otra PowerShell de Windows, instalar Tailscale:

```powershell
winget install --id Tailscale.Tailscale -e
```

Abrir Tailscale desde Inicio e iniciar sesión. En ambos celulares, instalar la
aplicación oficial **Tailscale**, iniciar sesión en la misma cuenta y activar su
conexión VPN cuando Android solicite autorización. Los tres deben aparecer conectados.

Descargas oficiales: [Windows](https://tailscale.com/download/windows) y
[Android](https://play.google.com/store/apps/details?id=com.tailscale.ipn).

No hace falta conectar cables, usar `adb reverse` ni abrir puertos del router.

## 3. Crear la dirección HTTPS privada de AuditLab

Desde PowerShell de Windows:

```powershell
& "$env:ProgramFiles\Tailscale\tailscale.exe" status
& "$env:ProgramFiles\Tailscale\tailscale.exe" serve status
```

Confirmar que la PC está conectada. Si ya existe una configuración en el puerto
**8443**, conservarla y no ejecutar el siguiente comando hasta revisar qué servicio
la utiliza. No usar `serve reset`: eliminaría configuraciones anteriores.

Si 8443 está libre, ejecutar:

```powershell
& "$env:ProgramFiles\Tailscale\tailscale.exe" serve --bg --https=8443 http://127.0.0.1:8081
```

La primera vez Tailscale puede mostrar un enlace para habilitar HTTPS en tu red
privada. Abrirlo en tu navegador, habilitar HTTPS y completar/repetir el comando.
La configuración de acceso de esa red debe permitir a tus celulares conectar a la PC.

Copiar la dirección HTTPS que aparece, por ejemplo:

```text
https://mi-pc.tail123456.ts.net:8443
```

**Es un ejemplo: usa la dirección real mostrada en tu PC.** Mantener `https://` y
el puerto que muestra el comando. `Serve` comparte dentro de tu red privada;
no habilitar `Funnel`, que tiene otro alcance.

## 4. Comprobar desde cada celular

Con Tailscale conectado en los tres equipos, abrir en el navegador de **cada celular**
la dirección HTTPS obtenida y añadir `/ready`:

```text
https://TU-DIRECCION-REAL:8443/ready
```

Ambos deben mostrar `{"status":"ready"}` sin errores de certificado. Si falla,
comprobar la conexión Tailscale, el servidor Windows y `tailscale serve status`.
No desactivar la comprobación de certificados.

En las dos apps de AuditLab, usar la misma dirección HTTPS **sin `/ready` ni `/v1`**.
No usar `127.0.0.1`, `10.0.2.2` ni una dirección HTTP de la red local en este modo.

## 5. Inscribir y utilizar

1. Centro: URL HTTPS anterior, usuario `admin` y contraseña elegida la primera vez que
   ejecutaste el servidor Windows. No es la contraseña de Tailscale.
2. Crear un código de inscripción.
3. Agente: introducir la misma URL HTTPS, aceptar el consentimiento e inscribir con ese código.
4. Habilitar el indicador visible. Aceptar la autorización adicional y pulsar
   **Autorizar e iniciar captura real**, respondiendo a los permisos del sistema.
5. Verificar que SMS/llamadas figuren autorizados. Probar un SMS convencional y terminar
   una llamada; actualizar el historial del Centro. [Alcance de los eventos](CAPTURA_REAL.md).

Si ya inscribiste el Agente con la URL USB, primero retirar y confirmar esa inscripción
mediante la conexión que todavía funcione; después aceptar de nuevo con la dirección HTTPS.
El Agente vincula las credenciales al servidor y no permite reemplazar su URL mientras
está inscrito o tiene una retirada pendiente. No desinstalar ni borrar datos para saltar
esa protección. En el Centro, cerrar la sesión anterior antes de editar la URL.

## Apagar

Pausar/retirar el monitoreo desde el Agente cuando corresponda y detener la API con
Ctrl+C. El proxy de Tailscale puede permanecer configurado, pero no servirá eventos
si la API o la PC están apagadas. Para retirar únicamente el proxy creado en 8443:

```powershell
& "$env:ProgramFiles\Tailscale\tailscale.exe" serve --https=8443 off
```

Usar ese comando solo si 8443 sigue siendo el servicio de AuditLab creado aquí.

## Estado de esta guía

Preparada para P8.3. Se revisaron las restricciones de los APK/API y la sintaxis de
Serve en su [fuente oficial](https://github.com/tailscale/tailscale/blob/main/cmd/tailscale/cli/serve_v2.go).
**No se ha ejecutado Windows/Tailscale ni conectado teléfonos del usuario desde este
entorno.** No se creó una cuenta, no se habilitó una red ni se publicó un servidor.
La configuración e inicio de sesión se realizan en tus equipos. No cambian los APK,
la base de datos ni las pruebas ya registradas para 0.3.
