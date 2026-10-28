# Probar AuditLab en dos celulares desde Windows

Guia de la candidata **0.3.0-rc1**, con [captura real de metadatos](CAPTURA_REAL.md).

Modalidad local de desarrollo, con dos cables USB. La API queda en la PC y ADB conecta el puerto local de cada celular con ella. No hace falta un dominio ni publicar el servidor. Esta modalidad usa la excepcion loopback ya presente en las APKs; no acredita HTTPS de piloto.

## 1. Instalar los programas de Windows

En PowerShell, ejecutar uno por uno:

```powershell
winget install --id Docker.DockerDesktop -e
winget install --id Microsoft.OpenJDK.21 -e
```

Reiniciar si Windows lo solicita. Abrir Docker Desktop y esperar a que el motor este listo. Debe usar contenedores Linux; en Windows puede solicitar WSL2/virtualizacion. Abrir una PowerShell nueva despues de instalar Java para actualizar el PATH.

## 2. Extraer el paquete Windows

Descargar y extraer completo `AuditLab-Servidor-Windows-0.3.0-rc1.zip`, por ejemplo en `C:\AuditLab`. Deben verse `Iniciar-AuditLab.ps1`, `api`, `apks`, `source` y este manual. Las dos APKs se descargan por separado y se guardan en `apks`. No ejecutar el script dentro de la vista comprimida del ZIP.

**Si actualizas desde 0.2:** detener la API con Ctrl+C, conservar la carpeta
`C:\AuditLab\.auditlab-windows` y actualizar en la misma ruta. Renombrar `api` a
`api-respaldo-0.2` antes de extraer la carpeta `api` nueva. No borrar configuracion,
volumenes ni bases. El lanzador aplica V3 y reutiliza la base y contrasena existentes.

Dentro de esa carpeta, hacer clic en la barra de direccion del Explorador, escribir `powershell` y pulsar Enter. Ejecutar:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Iniciar-AuditLab.ps1
```

La primera vez pide crear una contrasena de 12 a 128 caracteres para el usuario **admin**. Guardarla para el celular B. La base y configuracion quedan en un contenedor/volumen propios y en `.auditlab-windows`, con permisos para el usuario de Windows. Repetir el arranque conserva datos y credenciales; no borrar esa carpeta para recuperar un error.

Mantener esa ventana y Docker Desktop abiertos. En el navegador de la PC, abrir:

```text
http://127.0.0.1:8081/ready
```

El resultado requerido es `{"status":"ready"}`. Si no aparece, resolver ese paso antes de seguir. Los errores de preparacion se guardan en `.auditlab-windows\setup.log`; contiene informacion privada de configuracion y se revisa localmente.

## 3. Preparar ADB y los celulares

Descargar las [Platform Tools oficiales para Windows](https://dl.google.com/android/repository/platform-tools-latest-windows.zip) y extraer la carpeta `platform-tools`.

En cada celular propio:

1. Abrir Ajustes y buscar **Numero de compilacion** (en algunos fabricantes esta dentro de Informacion de software).
2. Pulsarlo siete veces para habilitar Opciones de desarrollador.
3. En Opciones de desarrollador, activar **Depuracion USB**.
4. Conectarlo a la PC por un cable que transfiera datos y aceptar la autorizacion de esa computadora en la pantalla.

Abrir otra PowerShell dentro de `platform-tools` y ejecutar:

```powershell
.\adb.exe devices
```

Deben aparecer dos filas terminadas en `device`. `unauthorized` requiere aceptar el permiso en el celular. Una lista vacia requiere revisar cables, modo USB y, si corresponde, el controlador USB del fabricante.

## 4. Conectar ambos al servidor

Usar los identificadores reales de la lista anterior en estos comandos:

```powershell
.\adb.exe -s SERIAL_DEL_CELULAR_A reverse tcp:8081 tcp:8081
.\adb.exe -s SERIAL_DEL_CELULAR_B reverse tcp:8081 tcp:8081
```

`SERIAL_DEL_CELULAR_A` y `SERIAL_DEL_CELULAR_B` son marcadores para reemplazar, no texto que se deba pegar literalmente. Ambos telefonos necesitan conservar su conexion USB. Si se reconectan o reinician, repetir `adb reverse`.

## 5. Instalar y usar las apps

Guardar las descargas nuevas en `C:\AuditLab\apks` e instalar conservando datos:

```powershell
.\adb.exe -s SERIAL_DEL_CELULAR_A install -r C:\AuditLab\apks\auditlab-agent-0.3.0-rc1.apk
.\adb.exe -s SERIAL_DEL_CELULAR_B install -r C:\AuditLab\apks\auditlab-control-0.3.0-rc1.apk
```

Si hay una version con firma incompatible, no desinstalar automaticamente: conservar los datos y revisar el conflicto. Los permisos sensibles se solicitan dentro del Agente; instalar no equivale a aceptar el monitoreo.

**En B, Centro de control:**

1. URL: `http://127.0.0.1:8081`, sin `/ready` ni `/v1`.
2. Usuario: `admin`. Contrasena: la que elegiste al arrancar.
3. Iniciar sesion y pulsar **Crear codigo de un uso**.

**En A, Agente:**

1. Leer y aceptar el consentimiento.
2. Usar la misma URL `http://127.0.0.1:8081`.
3. Introducir el codigo de B y registrar el dispositivo.
4. Permitir las notificaciones y habilitar el indicador si la app lo solicita.
5. Marcar la autorizacion adicional y pulsar **Autorizar e iniciar captura real**.
6. Responder a los permisos de SMS/llamadas del sistema. Comprobar que cada fuente
   figure autorizada; si dice **sin permiso**, esa fuente no funciona en ese estado.
7. Enviar/recibir un SMS convencional y terminar una llamada. No usar WhatsApp ni RCS.

Actualizar el centro B y abrir los eventos del dispositivo. Deben aparecer como **Real**,
con categoria `sms` o `call`. Se registra tipo y fecha, sin cuerpos ni numeros; las llamadas
aparecen al finalizar. Luego comprobar detalle, filtros, CSV y revocacion con confirmacion.
**Generar evento simulado** queda disponible como prueba adicional de conexion y se identifica como simulado.

## 6. Finalizar y limites

Revocar el dispositivo y cerrar la sesion desde las apps antes de detener la prueba. Despues detener la API con Ctrl+C en su ventana y desconectar los cables. Se conservan los datos locales para repetir el arranque.

Para retirar los reenvios sin desconectar los cables:

```powershell
.\adb.exe -s SERIAL_DEL_CELULAR_A reverse --remove tcp:8081
.\adb.exe -s SERIAL_DEL_CELULAR_B reverse --remove tcp:8081
```

La conexion via USB funciona independientemente del WiFi/datos del telefono: activar modo avion no equivale a desconectar esta ruta de prueba. Para una comprobacion de fallo de transporte, retirar el reenvio de A, generar el evento y restaurar el mismo `adb reverse`. La entrega remota del piloto requiere su URL HTTPS, certificado y validacion independiente.

El asistente Windows se preparo y reviso en un host Linux; **no se ha ejecutado en Windows ni con telefonos fisicos**. La candidata 0.3 modifica los APKs y la API para metadatos reales; consultar [AVANCE](AVANCE.md) para sus resultados. Los 278 casos historicos de 0.2 no validan esta ampliacion. Android puede restringir los permisos segun version, instalador o politica; la ausencia de permiso se muestra y no se sustituye por un evento simulado.
