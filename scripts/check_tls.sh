#!/usr/bin/env bash
# Comprobación TLS de extremo a extremo, reproducible en local.
#
# Requisitos:
#   - `./gradlew :backend:ktor:installDist` ejecutado antes.
#   - Variables de base de datos y `AUDITLAB_AUTH_SIGNING_KEY_BASE64` exportadas en el entorno
#     (por ejemplo, cargando el archivo local de secretos; nunca se versiona).
#   - `AUDITLAB_BOOTSTRAP_PASSWORD` para crear la cuenta de la comprobación.
#   - `openssl`, `curl`, `keytool` y el Python con `jsonschema`, `referencing`, `requests` y `yaml`.
#
# Qué demuestra:
#   1. Ktor sirve HTTPS con un certificado propio y solo abre el puerto cifrado.
#   2. El puerto TLS rechaza HTTP en claro.
#   3. Sin la CA, la verificación del certificado falla.
#   4. Cada respuesta real valida contra contracts/openapi.yaml (scripts/check_live_contract.py).
#   5. El cliente JVM de apps/common funciona por HTTPS con un truststore propio.
#   6. Los valores secretos no aparecen en el registro del servidor.
#
# No imprime contraseñas, tokens ni códigos de inscripción.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

PORT=${AUDITLAB_TLS_PORT:-18443}
DIR=${AUDITLAB_TLS_CHECK_DIR:-$ROOT/.tooling/tls-check}
PYTHON=${AUDITLAB_CHECK_PYTHON:-python3}
BIN=backend/ktor/build/install/ktor/bin/ktor
USER_NAME=${AUDITLAB_TLS_CHECK_USER:-tls_check_admin}

for required in AUDITLAB_BOOTSTRAP_PASSWORD AUDITLAB_AUTH_SIGNING_KEY_BASE64 AUDITLAB_DB_JDBC_URL; do
  if [ -z "${!required:-}" ]; then
    echo "Falta $required en el entorno." >&2
    exit 2
  fi
done
if [ ! -x "$BIN" ]; then
  echo "Falta $BIN. Ejecuta antes: ./gradlew :backend:ktor:installDist" >&2
  exit 2
fi

mkdir -p "$DIR"
chmod 700 "$DIR"
CERT="$DIR/cert.pem"
KEY="$DIR/key.pem"
LOG="$DIR/server.log"

if [ ! -f "$CERT" ] || [ ! -f "$KEY" ]; then
  # Certificado autofirmado de prueba con CA propia; nunca para un piloto real.
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$KEY" -out "$CERT" -days 2 \
    -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1
  chmod 600 "$KEY"
fi

export AUDITLAB_BOOTSTRAP_USERNAME="$USER_NAME"
export AUDITLAB_BOOTSTRAP_ROLE=admin
"$BIN" bootstrap-user >/dev/null

export AUDITLAB_TLS_CERTIFICATE="$CERT"
export AUDITLAB_TLS_PRIVATE_KEY="$KEY"
export AUDITLAB_TLS_PORT="$PORT"
"$BIN" >"$LOG" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  if curl -fsS --cacert "$CERT" "https://localhost:$PORT/health" >/dev/null 2>&1; then break; fi
  sleep 0.5
done

echo "1) HTTPS con CA propia"
curl -fsS --cacert "$CERT" "https://localhost:$PORT/health"; echo
curl -fsS --cacert "$CERT" "https://localhost:$PORT/ready"; echo

echo "2) HTTP en claro contra el puerto TLS debe fallar"
if curl -sS --max-time 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  echo "   FALLO: el puerto TLS aceptó HTTP en claro" >&2
  exit 1
fi
echo "   ok: no hay HTTP en claro en el puerto TLS"

echo "3) Certificado no confiable debe ser rechazado"
if curl -sS --max-time 3 "https://localhost:$PORT/health" >/dev/null 2>&1; then
  echo "   FALLO: se aceptó el certificado sin CA" >&2
  exit 1
fi
echo "   ok: sin la CA, la verificación falla"

echo "4) Contrato en vivo por HTTPS"
AUDITLAB_LIVE_URL="https://localhost:$PORT" \
AUDITLAB_LIVE_USER="$USER_NAME" \
AUDITLAB_LIVE_PASSWORD="$AUDITLAB_BOOTSTRAP_PASSWORD" \
AUDITLAB_LIVE_CA="$CERT" \
  "$PYTHON" scripts/check_live_contract.py

echo "5) Cliente JVM real (apps:common) por HTTPS con truststore propio"
TRUST="$DIR/trust.p12"
rm -f "$TRUST"
keytool -importcert -noprompt -alias auditlab -file "$CERT" -keystore "$TRUST" \
  -storetype PKCS12 -storepass changeit >/dev/null 2>&1
AUDITLAB_SMOKE_URL="https://localhost:$PORT" \
AUDITLAB_SMOKE_USER="$USER_NAME" \
AUDITLAB_SMOKE_PASSWORD="$AUDITLAB_BOOTSTRAP_PASSWORD" \
AUDITLAB_SMOKE_TRUSTSTORE="$TRUST" \
AUDITLAB_SMOKE_TRUSTSTORE_PASSWORD=changeit \
  "$PYTHON" scripts/gradle.py :apps:common:smokeTest --no-daemon --rerun-tasks

echo "6) Secretos ausentes del registro del servidor"
for name in AUDITLAB_DB_PASSWORD AUDITLAB_AUTH_SIGNING_KEY_BASE64 AUDITLAB_MAINTENANCE_PASSWORD AUDITLAB_BOOTSTRAP_PASSWORD; do
  value="${!name:-}"
  if [ -n "$value" ] && grep -qF "$value" "$LOG"; then
    echo "   FALLO: $name apareció en el registro" >&2
    exit 1
  fi
done
echo "   ok: sin valores de secretos en el registro"
