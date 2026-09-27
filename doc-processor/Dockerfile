# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------------------------
# base: system libs, Python deps, pre-downloaded models, app code
# ---------------------------------------------------------------------------------------------
# Pinned to the patch release + Debian suite; bump deliberately.
FROM python:3.12.14-slim-trixie AS base

# pip's cache lives in a BuildKit cache mount (never in a layer), so it stays enabled.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HF_HOME=/opt/hf-cache \
    DOCLING_ARTIFACTS_PATH=/opt/docling-models \
    MPLCONFIGDIR=/tmp/matplotlib \
    # Unstructured phones home on import unless told not to.
    DO_NOT_TRACK=true \
    SCARF_NO_ANALYTICS=true

# Tesseract is the single OCR engine for both Docling (TesseractCli) and Unstructured images.
# libmagic1: file-type sniffing used by Unstructured.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng libmagic1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements-core.txt requirements.txt requirements-nodeps.txt ./
# CPU-only PyTorch first, pins read from requirements.txt: the default PyPI wheel bundles CUDA
# (~2.5 GB extra). The following `-r requirements.txt` then finds torch already satisfied.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --index-url https://download.pytorch.org/whl/cpu \
        $(grep -E '^torch(vision)?==' requirements.txt) \
    && pip install -r requirements.txt \
    && pip install --no-deps -r requirements-nodeps.txt

COPY scripts/prefetch_models.py scripts/prefetch_models.py
RUN python scripts/prefetch_models.py

# From here on nothing may reach the network: models come from the baked-in caches.
ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    STORAGE_ROOT=/data/uploads

COPY app ./app

# ---------------------------------------------------------------------------------------------
# test: `docker build --target test -t doc-processor:test . && docker run --rm doc-processor:test`
# ---------------------------------------------------------------------------------------------
FROM base AS test
COPY requirements-dev.txt ./
RUN --mount=type=cache,target=/root/.cache/pip pip install -r requirements-dev.txt
COPY tests ./tests
COPY pytest.ini ./
CMD ["python", "-m", "pytest", "-v"]

# ---------------------------------------------------------------------------------------------
# runtime (default target)
# ---------------------------------------------------------------------------------------------
FROM base AS runtime

# UID 1000 matches the default host user on most Linux machines, so files written to the
# bind-mounted uploads dir stay readable/writable by the backend running on the host.
RUN useradd --uid 1000 --create-home --shell /usr/sbin/nologin app \
    && mkdir -p /data/uploads \
    && chown app:app /data/uploads
USER app

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
