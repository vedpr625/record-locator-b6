#!/usr/bin/env bash
# Run ON the Azure VM (Ubuntu 22.04/24.04) as root, from a folder containing:
#   record-locator.jar  record-locator-demo.env  record-locator-demo.service
# Usage: sudo bash setup-demo.sh
set -euo pipefail

APP_DIR=/opt/record-locator
ENV_DIR=/etc/record-locator
DATA_DIR=/var/lib/record-locator
HERE="$(cd "$(dirname "$0")" && pwd)"

for f in record-locator.jar record-locator-demo.env record-locator-demo.service; do
  [ -f "$HERE/$f" ] || { echo "missing $HERE/$f"; exit 1; }
done

echo "=== 1. Java 21 ==="
if ! java -version 2>&1 | grep -q 'version "21'; then
  apt-get update -y
  apt-get install -y openjdk-21-jre-headless
fi

echo "=== 2. User and directories ==="
id record-locator &>/dev/null || useradd --system --shell /usr/sbin/nologin --home-dir "$APP_DIR" record-locator
mkdir -p "$APP_DIR" "$ENV_DIR" "$DATA_DIR"
chown record-locator:record-locator "$APP_DIR" "$DATA_DIR"
chmod 700 "$DATA_DIR"

echo "=== 3. Install JAR ==="
install -o record-locator -g record-locator -m 555 "$HERE/record-locator.jar" "$APP_DIR/record-locator.jar"

echo "=== 4. Env file (kept if it already exists) ==="
if [ ! -f "$ENV_DIR/record-locator.env" ]; then
  install -o root -g root -m 600 "$HERE/record-locator-demo.env" "$ENV_DIR/record-locator.env"
  PW="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  sed -i "s/^DB_PASSWORD=CHANGE_ME/DB_PASSWORD=$PW/" "$ENV_DIR/record-locator.env"
  echo "  Created $ENV_DIR/record-locator.env with a random H2 password."
fi
chmod 600 "$ENV_DIR/record-locator.env"

if grep -q 'CHANGE-ME' "$ENV_DIR/record-locator.env"; then
  echo
  echo "  !! Set IDP_ISSUER_URI in $ENV_DIR/record-locator.env, then run:"
  echo "       sudo systemctl enable --now record-locator"
  install -m 644 "$HERE/record-locator-demo.service" /etc/systemd/system/record-locator.service
  systemctl daemon-reload
  exit 0
fi

echo "=== 5. systemd unit and start ==="
install -m 644 "$HERE/record-locator-demo.service" /etc/systemd/system/record-locator.service
systemctl daemon-reload
systemctl enable --now record-locator

for i in $(seq 1 30); do
  curl -sf http://localhost:8080/actuator/health | grep -q UP && { echo "Health: UP"; exit 0; }
  sleep 3
done
echo "Not healthy yet - check: journalctl -u record-locator -n 80 --no-pager"
exit 1
