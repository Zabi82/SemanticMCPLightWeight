FROM confluentinc/cp-kafka-connect:7.6.0

# Copy the pre-built Iceberg Kafka Connect runtime JAR into the plugin path.
# Run kafka-connect/setup-connector.sh first to build/download this JAR.
COPY plugins/ /usr/share/confluent-hub-components/iceberg-kafka-connect/
