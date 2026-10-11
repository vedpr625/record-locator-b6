#!/usr/bin/env bash
# setup.sh — run once on a fresh Lightsail Ubuntu instance as root (or with sudo)
# Usage: sudo bash setup.sh
set -euo pipefail

APP_DIR=/opt/record-locator
ENV_DIR=/etc/record-locator
JAR=record-locator.jar

echo "=== 1. Create app user ==="
id record-locator &>/dev/null || useradd --system --shell /bin/false --home-dir $APP_DIR record-locator

echo "=== 2. Create directories ==="
mkdir -p $APP_DIR $ENV_DIR
chown record-locator:record-locator $APP_DIR

echo "=== 3. Install JAR ==="
cp $JAR $APP_DIR/
chown record-locator:record-locator $APP_DIR/$JAR
chmod 555 $APP_DIR/$JAR

echo "=== 4. Install env file (if not already present) ==="
if [ ! -f $ENV_DIR/record-locator.env ]; then
  cp record-locator.env $ENV_DIR/
  echo "  ⚠  Edit $ENV_DIR/record-locator.env — set DB_PASSWORD and review OpenSearch settings"
fi
chmod 600 $ENV_DIR/record-locator.env
chown root:root $ENV_DIR/record-locator.env

echo "=== 5. Install systemd unit ==="
cp record-locator.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable record-locator

echo "=== 6. Create OpenSearch index ==="
echo "  Waiting for OpenSearch to be ready..."
for i in {1..15}; do
  curl -sf http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=5s && break
  sleep 3
done
# Create rl-patients index with mappings for name search
curl -s -X PUT http://localhost:9200/rl-patients -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "phn":        { "type": "keyword" },
      "givenName":  { "type": "text", "analyzer": "standard" },
      "familyName": { "type": "text", "analyzer": "standard" },
      "fullName":   { "type": "text", "analyzer": "standard" },
      "facilityHin":{ "type": "keyword" }
    }
  }
}' | python3 -m json.tool || true

echo "=== 7. Start service ==="
systemctl start record-locator
sleep 3
systemctl status record-locator --no-pager

echo ""
echo "=== Done ==="
echo "  Logs:   journalctl -u record-locator -f"
echo "  Health: curl http://localhost:8080/actuator/health"
echo ""
echo "  If health returns UP — you're live."
echo "  Remember to open port 8080 in your Lightsail firewall (or put nginx in front)."
