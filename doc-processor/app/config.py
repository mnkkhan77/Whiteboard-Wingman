"""Runtime configuration, read from environment variables (see README for the full list)."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # --- Kafka -----------------------------------------------------------------------------
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_group_id: str = "doc-processor"
    kafka_topic_uploaded: str = "wingman.document.uploaded"
    kafka_topic_parsed: str = "wingman.document.parsed"
    kafka_topic_failed: str = "wingman.document.failed"
    kafka_topic_dlt: str = "wingman.document.uploaded.DLT"
    # A 1000-page PDF can take many minutes; the consumer does not poll while parsing, so this
    # must exceed the slowest parse or the broker evicts us from the group mid-document.
    kafka_max_poll_interval_ms: int = 1_800_000
    kafka_session_timeout_ms: int = 45_000
    publish_timeout_seconds: float = 30.0
    consumer_enabled: bool = True

    # --- Storage ---------------------------------------------------------------------------
    storage_root: Path = Path("/data/uploads")

    # --- Parsing ---------------------------------------------------------------------------
    parse_max_attempts: int = Field(default=3, ge=1)
    parse_retry_backoff_seconds: float = 2.0
    ocr_languages: str = "eng"  # comma-separated Tesseract language codes
    docling_artifacts_path: Path | None = None  # None -> Docling's default cache dir
    docling_timeout_seconds: float | None = 900.0
    warmup_on_start: bool = True

    # --- Chunking --------------------------------------------------------------------------
    # all-MiniLM-L6-v2 truncates at 256 word pieces including [CLS]/[SEP], hence 254.
    tokenizer_model: str = "sentence-transformers/all-MiniLM-L6-v2"
    chunk_max_tokens: int = 254
    chunk_max_chars: int = 1200  # Unstructured hard limit
    chunk_soft_max_chars: int = 1000  # Unstructured: start a new chunk after this many chars
    chunk_combine_under_chars: int = 300  # Unstructured: merge tiny sections

    log_level: str = "INFO"

    @property
    def ocr_language_list(self) -> list[str]:
        return [lang.strip() for lang in self.ocr_languages.split(",") if lang.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
