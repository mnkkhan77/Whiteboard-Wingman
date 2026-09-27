"""Kafka worker: consume uploads, run the pipeline, publish the result, then commit.

Why confluent-kafka in a plain thread (not aiokafka): parsing is blocking, CPU-heavy work
(PyTorch, Tesseract), so an event loop buys nothing; librdkafka gives us a mature client with
idempotent producing and explicit per-message commits, which is what at-least-once needs.

Delivery semantics (at-least-once):
  * auto-commit is off; an offset is committed only after its result event (parsed / failed /
    DLT copy) has been acknowledged by the broker;
  * if publishing keeps failing, the consumer seeks back so the message is re-processed later;
  * re-processing is safe: chunks.json is atomically overwritten and output eventIds are
    derived from the input eventId, so the backend sees the same id twice and can de-dupe.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from collections.abc import Callable
from typing import Any

from confluent_kafka import Consumer, KafkaError, KafkaException, Producer, TopicPartition
from pydantic import ValidationError

from app.config import Settings
from app.models import DocumentParsedEvent, DocumentUploadedEvent
from app.pipeline import Pipeline

log = logging.getLogger(__name__)

Headers = list[tuple[str, bytes]]

PUBLISH_ATTEMPTS = 3
RESTART_DELAY_SECONDS = 5.0


class PublishError(Exception):
    """A result event could not be acknowledged by the broker."""


class DocumentWorker:
    """Owns the consumer thread. Kafka clients and the pipeline are injected as factories so
    tests can drive `handle_message` with in-memory fakes."""

    def __init__(
        self,
        settings: Settings,
        pipeline_factory: Callable[[], Pipeline],
        *,
        consumer_factory: Callable[[dict[str, Any]], Any] | None = None,
        producer_factory: Callable[[dict[str, Any]], Any] | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.settings = settings
        self._pipeline_factory = pipeline_factory
        self._consumer_factory = consumer_factory or _confluent_consumer
        self._producer_factory = producer_factory or _confluent_producer
        self._sleep = sleep
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.pipeline: Pipeline | None = None
        self.producer: Any = None
        self.subscribed = False
        self.broker_up = False
        self.busy = False
        self.last_error: str | None = None

    # --- lifecycle -------------------------------------------------------------------------

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="kafka-worker", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 30.0) -> None:
        """Ask the loop to exit. An in-flight parse is not interrupted; if it outlives the
        timeout its offset stays uncommitted and the upload is simply re-delivered."""
        self._stop.set()
        if self._thread:
            self._thread.join(timeout)

    @property
    def alive(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    @property
    def ready(self) -> bool:
        # Stats callbacks only fire while polling, so a long parse must not flip us unready.
        return self.alive and self.subscribed and (self.busy or self.broker_up)

    def status(self) -> dict[str, Any]:
        return {
            "ready": self.ready,
            "threadAlive": self.alive,
            "subscribed": self.subscribed,
            "brokerUp": self.broker_up,
            "busy": self.busy,
            "lastError": self.last_error,
        }

    def _run(self) -> None:
        try:
            self.pipeline = self._pipeline_factory()
            if self.settings.warmup_on_start:
                started = time.monotonic()
                self.pipeline.warmup()
                log.info("Models warmed up in %.1fs", time.monotonic() - started)
        except Exception as exc:
            # Parsing is retried per document anyway; don't block consumption on warm-up.
            log.exception("Warm-up failed")
            self.last_error = f"warmup: {exc}"
            if self.pipeline is None:
                return
        while not self._stop.is_set():
            try:
                self._consume_loop()
            except Exception as exc:  # e.g. fatal client error: rebuild the clients
                log.exception("Kafka loop crashed; restarting in %.0fs", RESTART_DELAY_SECONDS)
                self.last_error = str(exc)
                self.subscribed = False
                self._stop.wait(RESTART_DELAY_SECONDS)

    def _consume_loop(self) -> None:
        s = self.settings
        consumer = self._consumer_factory(
            {
                "bootstrap.servers": s.kafka_bootstrap_servers,
                "group.id": s.kafka_group_id,
                "enable.auto.commit": False,
                "auto.offset.reset": "earliest",
                "max.poll.interval.ms": s.kafka_max_poll_interval_ms,
                "session.timeout.ms": s.kafka_session_timeout_ms,
                "statistics.interval.ms": 10_000,
                "stats_cb": self._on_stats,
                "error_cb": self._on_error,
            }
        )
        self.producer = None
        try:
            self.producer = self._producer_factory(
                {
                    "bootstrap.servers": s.kafka_bootstrap_servers,
                    "enable.idempotence": True,
                    "acks": "all",
                    "error_cb": self._on_error,
                }
            )
            consumer.subscribe([s.kafka_topic_uploaded], on_assign=self._on_assign)
            self.subscribed = True
            log.info("Consuming %s as group %s", s.kafka_topic_uploaded, s.kafka_group_id)
            while not self._stop.is_set():
                msg = consumer.poll(1.0)
                if msg is None:
                    continue
                if msg.error():
                    self._on_message_error(msg.error())
                    continue
                self.broker_up = True
                self.handle_message(consumer, msg)
        finally:
            self.subscribed = False
            consumer.close()
            if self.producer is not None:
                self.producer.flush(5)

    # --- message handling ------------------------------------------------------------------

    def handle_message(self, consumer: Any, msg: Any) -> None:
        """Process one record; commit only once its outcome is safely on a topic."""
        s = self.settings
        event, problem = decode_uploaded(msg.value())
        if event is None:
            log.error("Poison message at %s[%s]@%s: %s", msg.topic(), msg.partition(), msg.offset(), problem)
            headers: Headers = [
                *(msg.headers() or []),
                ("dlt-exception-message", (problem or "")[:1000].encode()),
                ("dlt-original-topic", str(msg.topic()).encode()),
                ("dlt-original-partition", str(msg.partition()).encode()),
                ("dlt-original-offset", str(msg.offset()).encode()),
            ]
            if self._publish_or_rewind(consumer, msg, s.kafka_topic_dlt, msg.key(), msg.value(), headers):
                self._commit(consumer, msg)
            return

        assert self.pipeline is not None
        self.busy = True
        try:
            result = self.pipeline.process(event)
        finally:
            self.busy = False
        topic = s.kafka_topic_parsed if isinstance(result, DocumentParsedEvent) else s.kafka_topic_failed
        if self._publish_or_rewind(consumer, msg, topic, str(event.pack_id).encode(), result.to_json_bytes()):
            self._commit(consumer, msg)

    def _publish_or_rewind(
        self,
        consumer: Any,
        msg: Any,
        topic: str,
        key: bytes | None,
        value: bytes | None,
        headers: Headers | None = None,
    ) -> bool:
        """Publish with retries; on final failure seek back (no commit) and return False."""
        for attempt in range(1, PUBLISH_ATTEMPTS + 1):
            try:
                self._publish(topic, key, value, headers)
                return True
            except PublishError as exc:
                log.warning("Publish to %s failed (attempt %d/%d): %s", topic, attempt, PUBLISH_ATTEMPTS, exc)
                self.last_error = str(exc)
                self._sleep(2 * attempt)
        # Leave the offset uncommitted and rewind so this upload is re-processed later.
        consumer.seek(TopicPartition(msg.topic(), msg.partition(), msg.offset()))
        return False

    def _publish(self, topic: str, key: bytes | None, value: bytes | None, headers: Headers | None) -> None:
        """Produce and block until the broker acknowledges (or the timeout expires)."""
        outcome: dict[str, Any] = {}

        def on_delivery(err: Any, _msg: Any) -> None:
            outcome["error"] = err
            outcome["done"] = True

        try:
            self.producer.produce(topic, key=key, value=value, headers=headers, on_delivery=on_delivery)
        except Exception as exc:  # BufferError (local queue full), KafkaException, ...
            raise PublishError(f"produce failed: {exc}") from exc
        self.producer.flush(self.settings.publish_timeout_seconds)
        if not outcome.get("done"):
            raise PublishError(f"no delivery report within {self.settings.publish_timeout_seconds}s")
        if outcome["error"] is not None:
            raise PublishError(str(outcome["error"]))

    def _commit(self, consumer: Any, msg: Any) -> None:
        try:
            consumer.commit(message=msg, asynchronous=False)
        except Exception as exc:  # e.g. rebalance in progress: we'll just see it again
            log.warning("Commit failed for offset %s (will be re-delivered): %s", msg.offset(), exc)

    # --- librdkafka callbacks --------------------------------------------------------------

    def _on_assign(self, _consumer: Any, partitions: list[Any]) -> None:
        self.broker_up = True
        log.info("Assigned partitions: %s", [p.partition for p in partitions])

    def _on_stats(self, stats_json: str) -> None:
        try:
            brokers = json.loads(stats_json).get("brokers", {}).values()
            self.broker_up = any(b.get("state") == "UP" and b.get("nodeid", -1) >= 0 for b in brokers)
        except (ValueError, AttributeError):
            pass

    def _on_error(self, err: Any) -> None:
        if err.code() == KafkaError._ALL_BROKERS_DOWN:
            self.broker_up = False
        self.last_error = str(err)
        log.warning("Kafka error: %s", err)

    def _on_message_error(self, err: Any) -> None:
        if err.fatal():
            raise KafkaException(err)
        if err.code() != KafkaError._PARTITION_EOF:
            # e.g. UNKNOWN_TOPIC_OR_PART until the backend creates the topic: keep polling.
            log.warning("Consumer error: %s", err)
            self.last_error = str(err)


def decode_uploaded(value: bytes | None) -> tuple[DocumentUploadedEvent | None, str | None]:
    """Decode and validate a record value; returns (event, None) or (None, reason)."""
    if value is None:
        return None, "empty (null) message value"
    try:
        return DocumentUploadedEvent.model_validate_json(value), None
    except ValidationError as exc:
        return None, f"invalid uploaded event: {exc.error_count()} error(s): {exc.errors(include_url=False)}"
    except (UnicodeDecodeError, ValueError) as exc:
        return None, f"undecodable message: {exc}"


def _confluent_consumer(config: dict[str, Any]) -> Consumer:
    return Consumer(config)


def _confluent_producer(config: dict[str, Any]) -> Producer:
    return Producer(config)
