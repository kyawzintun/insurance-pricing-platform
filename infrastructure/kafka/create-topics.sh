#!/usr/bin/env bash
set -euo pipefail

# Compose waits for Kafka's metadata API health check before starting this job.
for topic in \
  insurance.user.events \
  insurance.quote.events \
  insurance.pricing.events \
  insurance.user.events.dlq \
  insurance.quote.events.dlq \
  insurance.pricing.events.dlq
do
  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:19092 \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions 3 \
    --replication-factor 1
done

/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --describe --topic 'insurance.*'
