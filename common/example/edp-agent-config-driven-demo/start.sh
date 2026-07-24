#!/bin/bash
# ================================================================
# start.sh - Linux/Mac startup script
# ================================================================
# Usage: export EDP_AGENT_MODEL_API_KEY=sk-xxx && ./start.sh
# ================================================================

set -e

# --- JAR path ---
JAR_FILE="../../agent/edp-agent-java/engine/target/edp-agent-engine-0.1.0-SNAPSHOT.jar"

# --- Check JAR exists ---
if [ ! -f "$JAR_FILE" ]; then
    echo "[ERROR] JAR not found: $JAR_FILE"
    echo "Please build first: cd ../../agent/edp-agent-java/engine && mvn clean package -DskipTests"
    exit 1
fi

# --- Check API key ---
if [ -z "$EDP_AGENT_MODEL_API_KEY" ]; then
    echo "[WARN] EDP_AGENT_MODEL_API_KEY not set, using default in application.yml"
fi

echo "[INFO] Starting edp-agent (smart-customer-service)..."
echo "[INFO] JAR: $JAR_FILE"
echo "[INFO] Config: application.yml"
echo ""

java -jar "$JAR_FILE" --spring.config.additional-location=file:./application.yml
