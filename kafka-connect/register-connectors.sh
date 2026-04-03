#!/bin/bash
# Registers Iceberg sink connectors for orders and lineitems topics
CONNECT_URL="http://localhost:8083"

# Wait a bit extra after REST API is up to ensure connector plugins are loaded
sleep 10

echo "Checking available connector plugins..."
curl -sf "$CONNECT_URL/connector-plugins" | grep -i iceberg || echo "WARNING: Iceberg plugin not found yet"

echo "Registering orders Iceberg sink connector..."
curl -sf -X POST "$CONNECT_URL/connectors" \
  -H "Content-Type: application/json" \
  -d @/connectors/iceberg-sink-orders.json

echo ""
echo "Registering lineitems Iceberg sink connector..."
curl -sf -X POST "$CONNECT_URL/connectors" \
  -H "Content-Type: application/json" \
  -d @/connectors/iceberg-sink-lineitems.json

echo ""
echo "Connector registration complete. Current connectors:"
curl -sf "$CONNECT_URL/connectors"
