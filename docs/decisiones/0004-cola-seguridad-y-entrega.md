# ADR 0004 — Cola durable, ejecución Android y dependencias de la candidata

Estado: adoptado para 0.2.0-rc1. Resultados y límites en [el informe](../evidencias/P5-P8-2026-10-25.md).

## Decisiones

- Room 2.7.2 y esquema v2 exportado; migración explícita 1→2, sin recreación destructiva. Un proceso comparte la puerta de coordinación de UI, workers y retirada; la cola nunca almacena tokens.
- WorkManager 2.10.1 con restricciones de red, lotes de 100 e IDs conservados, backoff exponencial y límite de intentos. La retención no exige red. Trabajos de privacidad y eventos tienen propósitos distintos y comprueban el dispositivo de su generación. La unicidad del trabajo de privacidad incluye el ID de dispositivo: una retirada antigua pendiente no absorbe la de una inscripción nueva, y KEEP conserva el backoff de cada inscripción.
- El indicador tiene una pausa durable independiente de la cancelación asíncrona de notificaciones. Ni un reinicio ni un worker vuelven a mostrarlo sin acción explícita del usuario. El indicador y consentimiento se comprueban antes de leer sensores y antes de cada lote.
- AES/GCM de la plataforma con Android Keystore, IV aleatorio y AAD por app/registro. Se comprueban escrituras y borrados; no se regenera una clave para intentar descifrar datos existentes. Credenciales y sesiones incluyen el origen autorizado dentro del cifrado.
- Las operaciones de las dos actividades usan el alcance del ViewModel en Main y hacen IO fuera del hilo principal; se conservan durante recreación. El alcance de composición se reserva como adaptador para las pantallas de prueba. La retirada usa además una puerta inmediata y el alcance de aplicación.
- Se conserva el cliente HTTP de plataforma ya utilizado en el módulo común. HTTPS verifica certificado y hostname, no sigue redirecciones, limita respuestas y solo permite HTTP en loopback. Retrofit/OkHttp era una propuesta inicial del plan; añadir otra capa de transporte no es requisito de interoperabilidad. JVM y Android comprueban el mismo contrato.
- La revisión del runtime resolvió avisos en Netty 4.1.118.Final y Logback 1.5.18: BOM Netty 4.1.137.Final y Logback 1.5.34. Ktor 3.1.2 y las versiones de herramientas se conservan. El informe registra la base de avisos y la resolución exacta examinada; no se presume seguridad futura por fijar estas versiones.
- El código de instrumentación y sus datos ficticios se incluyen solo en APKs androidTest. No se empaquetan JUnit, Robolectric ni clases de pruebas en las apps.

## Consecuencias

La retirada offline conserva solo la credencial necesaria para avisar al servidor, sin capacidad de capturar o enviar nuevos eventos. Un error de almacenamiento impide afirmar éxito. Reinscribir exige aceptación y código nuevos.

Los tests nativos usan instancias privadas, APKs debug y dos emuladores explícitos, sin borrar datos ni desinstalar. La CA de QA solo se habilita dentro del proceso de una prueba TLS; la confianza de producción no se modifica. La firma del responsable y el piloto HTTPS siguen siendo entregables externos.
