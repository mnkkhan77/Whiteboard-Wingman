"""Build-time model download so the container runs fully offline and the first upload is fast.

Fetches only what the pipeline uses: Docling's layout model (Heron, PyTorch variant) and
TableFormer, plus the embedding model's tokenizer for token-accurate chunk sizing.
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path


def main() -> None:
    artifacts = Path(os.environ.get("DOCLING_ARTIFACTS_PATH", "/opt/docling-models"))
    tokenizer_model = os.environ.get("TOKENIZER_MODEL", "sentence-transformers/all-MiniLM-L6-v2")

    from docling.utils.model_downloader import download_models

    download_models(
        output_dir=artifacts,
        with_layout=True,
        with_tableformer=True,
        with_code_formula=False,
        with_picture_classifier=False,
        with_rapidocr=False,
        with_easyocr=False,
    )
    # Drop weights the pipeline never loads: the ONNX export of the layout model (~160 MB; we
    # use the default transformers engine) and TableFormer "fast" (~145 MB; we run the default
    # ACCURATE mode, pinned in DoclingParser).
    for unused in [*artifacts.glob("*layout-heron-onnx*"), *artifacts.glob("*/model_artifacts/tableformer/fast")]:
        shutil.rmtree(unused, ignore_errors=True)

    from transformers import AutoTokenizer

    AutoTokenizer.from_pretrained(tokenizer_model)
    print(f"Models ready in {artifacts} and {os.environ.get('HF_HOME', '~/.cache/huggingface')}")


if __name__ == "__main__":
    main()
