# AuditLab — Windows y dos celulares, sin USB

> **Revisión de Defender completada — 29/10/2026 (America/Lima).**
> El ZIP original 0.7 pasó un análisis en Windows Server 2022 con Defender activo,
> firmas actualizadas, marca de zona Internet y sin exclusiones que cubran el ZIP.
> Motor `1.1.26100.4`, firmas `1.463.16.0`: sin amenazas detectadas.
> La alerta reportada no se reprodujo; no hay confirmación de falso positivo del proveedor.
> Mantén Defender activo. Si vuelve a bloquearlo, déjalo bloqueado y no añadas exclusiones.
> [Informe de la revisión](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/REVISION_DEFENDER_0.7.md).


Necesitas **una PC Windows encendida y dos teléfonos Android 8 o superior**, con
Internet. La PC ejecuta el servidor; el teléfono A lleva el Agente y B el Centro.
Tailscale permite conectarlos en redes distintas sin comprar dominio ni abrir
puertos del router. Ambos teléfonos deben poder acceder al mismo servidor.

## 1. Descargas

Descarga las apps **0.7.1-preview** y, si necesitas instalar el servidor, el paquete **0.7.0-preview**:

- [Agente 0.7.1](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.7.1-preview.apk).
- [Centro 0.7.1](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.7.1-preview.apk).
- [Servidor Windows 0.7](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.7.0-preview.zip).

El ZIP conserva los APK 0.7 en `apks/`; para la corrección WhatsApp instala los
APK 0.7.1 desde los enlaces separados. [Cambios](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/CORRECCION_WHATSAPP_0.7.1.md). Para el nuevo historial de comunicaciones y el texto SMS opcional se necesita
servidor 0.7 con V9; actualizar solo el Centro no habilita la captura del Agente. [Guía y límites de la actualización](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/ACTUALIZACION_0.7.md).

## 2. Preparar la PC Windows

Abre PowerShell y ejecuta, uno por uno:

```powershell
winget install --id Docker.DockerDesktop -e
winget install --id Microsoft.OpenJDK.21 -e
winget install --id Tailscale.Tailscale -e
```

Reinicia si se solicita. Abre Docker Desktop, acepta su preparación de WSL2 si es
necesaria y espera a que esté listo, usando contenedores Linux. Abre una PowerShell
nueva después de instalar Java.

Extrae **todo** el ZIP en `C:\AuditLab`. Deben aparecer `Iniciar-AuditLab.ps1`,
`api`, `apks` y `source` directamente dentro de esa carpeta. No ejecutes dentro del ZIP.

Si ya usabas AuditLab: detén primero la API con Ctrl+C. Conserva
`C:\AuditLab\.auditlab-windows` y los volúmenes Docker. Guarda la carpeta `api`
anterior como respaldo antes de extraer la nueva en la misma ubicación. El
lanzador reutiliza la configuración y contraseña y aplica las migraciones V4–V9.
No borres la base ni la configuración para resolver errores de inicio.

En PowerShell:

```powershell
cd C:\AuditLab
powershell -NoProfile -ExecutionPolicy Bypass -File .\Iniciar-AuditLab.ps1
```

En una instalación nueva te pedirá **crear la contraseña de `admin`**, de 12 a 128
caracteres. Guárdala: esa es la contraseña del Centro. Mantén esta ventana y Docker
abiertos. En el navegador de la PC, abre:

```text
http://127.0.0.1:8081/ready
```

Debe aparecer `{"status":"ready"}`. La dirección sin `/ready` no tiene página web.

## 3. Conectar por Internet sin USB

Abre Tailscale en Windows e inicia sesión. Instala la aplicación oficial Tailscale
en **los dos teléfonos**, inicia sesión en la misma cuenta y activa su conexión
VPN cuando Android lo solicite. Deben aparecer los tres equipos conectados.

En otra PowerShell de Windows:

```powershell
& "$env:ProgramFiles\Tailscale\tailscale.exe" status
& "$env:ProgramFiles\Tailscale\tailscale.exe" serve status
```

Si el puerto 8443 no está ocupado por otro servicio de Tailscale, ejecuta:

```powershell
& "$env:ProgramFiles\Tailscale\tailscale.exe" serve --bg --https=8443 http://127.0.0.1:8081
```

Si muestra un enlace para habilitar HTTPS, ábrelo y sigue sus instrucciones. Copia
la dirección HTTPS que devuelve el comando, por ejemplo
`https://mi-pc.tail123456.ts.net:8443`. **Usa tu dirección real, no este ejemplo**.

En el navegador de **cada teléfono**, abre tu dirección añadiendo `/ready`. Ambos
deben mostrar `{"status":"ready"}` sin errores de certificado. Hasta lograrlo, no
continúes con la inscripción. [Ayuda de conexión y cierre](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/PRUEBA_SIN_USB.md).

## 4. Instalar las apps y vincularlas

1. En A, abre el APK **Agente**. En B, abre el APK **Centro**. Android puede pedir
   permitir instalaciones desde el navegador/gestor de archivos. Si actualizas,
   conserva los datos; no desinstales para saltarte un conflicto de firma.
2. En el Centro B, introduce **tu URL HTTPS sin `/ready` ni `/v1`**, usuario `admin`
   y la contraseña creada en Windows. No es la contraseña de Tailscale.
3. En el Centro, abre **Más → Crear código de inscripción** y genera el código.
4. En el Agente A, lee/acepta el consentimiento, introduce la misma URL HTTPS y
   el código y pulsa **Inscribir dispositivo**.
5. Permite las notificaciones y pulsa **Habilitar indicador y reanudar** si aparece.
6. En el Centro, abre **Equipos** y actualiza: debe aparecer el dispositivo A.

Si el Agente ya estaba inscrito con otra URL, retira/confirma la inscripción por
la conexión anterior antes de cambiarla. En el Centro cierra la sesión anterior
para editar su URL. No uses `127.0.0.1` en los teléfonos en esta modalidad.

## 5. Activar cada función

### Batería, conexión y metadatos de SMS/llamadas

En el Agente, desplázate a la sección de captura real, marca su autorización y
pulsa **Autorizar e iniciar captura real**. Acepta los permisos opcionales que
necesites. Comprueba que las fuentes indiquen permiso concedido. SMS/llamadas
registran tipo y fecha. El número y el texto se comparten solo si marcas sus casillas adicionales
antes de iniciar; el texto autorizado se guarda con los eventos y puede exportarse en CSV. No se lee agenda ni audio. Los registros
anteriores no se completan. En el Centro usa Eventos → SMS/Llamadas.

### Texto de notificaciones nuevas de WhatsApp

1. En el Agente, baja hasta **Configurar vistas previas de WhatsApp**.
2. Abre **Configurar acceso de Android** y habilita AuditLab en los ajustes de
   acceso a notificaciones. Vuelve al Agente y espera a que diga conectado.
3. Marca la autorización adicional de vistas previas y registro de actividad y pulsa **Autorizar e iniciar sesión**.
4. En el Centro, abre **Eventos → WhatsApp** para texto temporal y tipo/fecha;
   con varios teléfonos, selecciona uno en **Filtrar eventos**. También puedes
   abrir **Equipos → Ver dispositivo → Ver notificaciones de WhatsApp**.
5. Con WhatsApp en segundo plano en A, recibe un mensaje nuevo de un contacto de
   prueba para que WhatsApp publique una notificación con texto visible.

No aparece el historial del chat ni mensajes sin notificación o con texto oculto.
Las respuestas enviadas dentro del chat no se detectan de forma general; solo se
registra una respuesta propia cuando Android la expone en una notificación.
Hasta 20 vistas previas/5 minutos en memoria; no se exportan a CSV. Conceder el
permiso Android por sí solo no activa la sesión. Para detener, pulsa **DETENER**
en la notificación persistente o el botón del Agente. Cada sesión exige autorización.

### Pantalla del teléfono A

En A, pulsa **Compartir pantalla en vivo**, lee la explicación y acepta el diálogo
de captura de Android. En B: **Equipos → Ver dispositivo → Ver pantalla compartida**.
Es una vista actualizada aproximadamente una vez por segundo, sin audio ni grabación.
El propietario de A inicia y detiene la sesión; B no la inicia remotamente.

## Si aparece un error

- **Credenciales rechazadas:** comprueba primero `/ready` en ambos teléfonos.
  Usa `admin` y la contraseña de la misma instalación del servidor Windows. Una
  sesión de otra instalación/URL requiere cerrar sesión y volver a iniciar.
- **Actualiza el servidor:** estás usando el backend anterior. Instala el ZIP 0.7
  conservando la configuración y ejecuta el lanzador nuevo.
- **No llegan notificaciones:** confirma sesión activa, acceso Android concedido,
  notificaciones WhatsApp habilitadas y texto disponible. Un chat abierto puede
  no generar notificación; no se inventan mensajes cuando no la hay.
- **Se detuvo en segundo plano:** revisa el ahorro de batería del fabricante.
  Forzar la detención o reiniciar el teléfono exige volver a autorizar las sesiones.

La PC, API, Docker y Tailscale deben seguir funcionando. Son APK debug de prueba.
En 0.7 se registran compilación, revisión estática, migración de desarrollo y revisión
visual de tarjetas. Las suites automáticas y el recorrido WhatsApp real de esta
revisión no se ejecutaron. Windows y teléfonos físicos quedan por comprobar.
