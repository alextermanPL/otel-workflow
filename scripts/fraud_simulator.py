#!/usr/bin/env python3
"""
Fraud check simulator.

Consumes FraudCheckCommand messages from the fraud-check-commands Kafka topic,
applies a configurable rejection rate, and publishes FraudCheckResult back to
the fraud-check-results topic.

Configuration (environment variables):
  BOOTSTRAP_SERVERS  Kafka bootstrap servers  (default: localhost:19092)
  REJECT_RATE        Fraction of payments to reject  (default: 0.15)
"""
import json
import logging
import os
import random
import time

from confluent_kafka import Consumer, KafkaError, Producer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BOOTSTRAP_SERVERS = os.getenv("BOOTSTRAP_SERVERS", "localhost:19092")
COMMAND_TOPIC = "fraud-check-commands"
RESULT_TOPIC = "fraud-check-results"
REJECT_RATE = float(os.getenv("REJECT_RATE", "0.15"))


def main() -> None:
    log.info(f"Fraud simulator starting — reject_rate={REJECT_RATE:.0%}  brokers={BOOTSTRAP_SERVERS}")

    producer = Producer({"bootstrap.servers": BOOTSTRAP_SERVERS})

    consumer = Consumer(
        {
            "bootstrap.servers": BOOTSTRAP_SERVERS,
            "group.id": "fraud-simulator",
            "auto.offset.reset": "latest",
            "enable.auto.commit": True,
        }
    )

    # Retry until the broker is reachable
    while True:
        try:
            consumer.subscribe([COMMAND_TOPIC])
            # Probe with a short poll — raises on hard failures
            consumer.poll(timeout=2.0)
            log.info("Connected. Listening for fraud check commands...")
            break
        except Exception as exc:
            log.warning(f"Broker not ready yet ({exc}) — retrying in 3 s")
            time.sleep(3)

    try:
        while True:
            msg = consumer.poll(timeout=1.0)
            if msg is None:
                continue
            if msg.error():
                if msg.error().code() != KafkaError._PARTITION_EOF:
                    log.error(f"Consumer error: {msg.error()}")
                continue

            try:
                cmd = json.loads(msg.value().decode("utf-8"))
                workflow_id = cmd["workflowId"]
                payment_id = cmd["paymentId"]

                # Simulate processing time: 100 ms – 2 s
                delay = random.uniform(0.1, 2.0)
                time.sleep(delay)

                status = "REJECT" if random.random() < REJECT_RATE else "CONTINUE"
                result = {
                    "workflowId": workflow_id,
                    "paymentId": payment_id,
                    "status": status,
                    "reason": "Fraud detected" if status == "REJECT" else None,
                }

                producer.produce(RESULT_TOPIC, json.dumps(result).encode("utf-8"))
                producer.flush()

                log.info(f"payment={payment_id}  workflow={workflow_id}  → {status}  (delay={delay:.2f}s)")

            except Exception as exc:
                log.error(f"Error processing message: {exc}")
    finally:
        consumer.close()


if __name__ == "__main__":
    main()
