"""Kafka worker semantics with in-memory fakes: publish-then-commit, DLT, rewind on failure."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

import pytest

from app.consumer import DocumentWorker, decode_uploaded

UPLOADED_TOPIC = "wingman.document.uploaded"


@dataclass
class FakeMessage:
    _value: bytes | None
    _key: bytes | None = b"42"
    _offset: int = 7
    _headers: list[tuple[str, bytes]] | None = None

    def value(self) -> bytes | None:
        return self._value

    def key(self) -> bytes | None:
        return self._key

    def topic(self) -> str:
        return UPLOADED_TOPIC

    def partition(self) -> int:
        return 0

    def offset(self) -> int:
        return self._offset

    def headers(self) -> list[tuple[str, bytes]] | None:
        return self._headers

    def error(self) -> None:
        return None


@dataclass
class FakeProducer:
    log: list[tuple[str, Any]]
    fail: bool = False
    pending: list[Any] = field(default_factory=list)

    def produce(
        self, topic: str, key: Any = None, value: Any = None, headers: Any = None, on_delivery: Any = None
    ) -> None:
        self.log.append(("produce", {"topic": topic, "key": key, "value": value, "headers": headers}))
        self.pending.append(on_delivery)

    def flush(self, _timeout: float = 0) -> int:
        for callback in self.pending:
            callback("broker unavailable" if self.fail else None, None)
        self.pending.clear()
        return 0


@dataclass
class FakeConsumer:
    log: list[tuple[str, Any]]

    def commit(self, message: Any = None, asynchronous: bool = True) -> None:
        assert asynchronous is False
        self.log.append(("commit", message.offset()))

    def seek(self, partition: Any) -> None:
        self.log.append(("seek", partition.offset))


@pytest.fixture
def worker_env(settings, pipeline):
    log: list[tuple[str, Any]] = []
    worker = DocumentWorker(settings, pipeline_factory=lambda: pipeline, sleep=lambda _s: None)
    worker.pipeline = pipeline
    worker.producer = FakeProducer(log)
    return worker, FakeConsumer(log), log


def _produced(log: list[tuple[str, Any]]) -> list[dict[str, Any]]:
    return [entry for kind, entry in log if kind == "produce"]


def test_valid_upload_publishes_parsed_then_commits(worker_env, make_upload) -> None:
    worker, consumer, log = worker_env
    worker.handle_message(consumer, FakeMessage(make_upload().to_json_bytes()))

    assert [kind for kind, _ in log] == ["produce", "commit"]
    sent = _produced(log)[0]
    assert sent["topic"] == "wingman.document.parsed"
    assert sent["key"] == b"42"
    body = json.loads(sent["value"])
    assert body["packId"] == 42 and body["chunksPath"] == "packs/42/chunks.json"


def test_pipeline_failure_publishes_failed_event_then_commits(worker_env, make_upload) -> None:
    worker, consumer, log = worker_env
    worker.handle_message(consumer, FakeMessage(make_upload(content=None).to_json_bytes()))

    assert [kind for kind, _ in log] == ["produce", "commit"]
    sent = _produced(log)[0]
    assert sent["topic"] == "wingman.document.failed"
    assert json.loads(sent["value"])["errorCode"] == "FILE_NOT_FOUND"


@pytest.mark.parametrize(
    "raw",
    [b"not json {", b'{"packId": "abc"}', b"\xff\xfe\x00", b'{"eventId": "e", "packId": 1}', None],
)
def test_poison_message_goes_to_dlt_and_is_committed(worker_env, raw) -> None:
    worker, consumer, log = worker_env
    worker.handle_message(consumer, FakeMessage(raw, _headers=[("trace", b"t1")]))

    assert [kind for kind, _ in log] == ["produce", "commit"]
    sent = _produced(log)[0]
    assert sent["topic"] == "wingman.document.uploaded.DLT"
    assert sent["value"] == raw and sent["key"] == b"42"  # raw bytes preserved
    headers = dict(sent["headers"])
    assert headers["trace"] == b"t1"
    assert headers["dlt-original-topic"] == UPLOADED_TOPIC.encode()
    assert headers["dlt-original-offset"] == b"7"
    assert headers["dlt-exception-message"]


def test_publish_failure_does_not_commit_and_rewinds(worker_env, make_upload) -> None:
    worker, consumer, log = worker_env
    worker.producer.fail = True
    worker.handle_message(consumer, FakeMessage(make_upload().to_json_bytes(), _offset=11))

    kinds = [kind for kind, _ in log]
    assert "commit" not in kinds
    assert kinds.count("produce") == 3  # retried
    assert log[-1] == ("seek", 11)  # re-delivered later; re-processing is idempotent


def test_decode_uploaded_reports_reason() -> None:
    event, problem = decode_uploaded(b"[]")
    assert event is None and problem
