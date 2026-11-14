"""Save/load model artifacts with joblib plus a metadata block."""
from __future__ import annotations

import platform
from datetime import datetime, timezone
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any

import joblib

PACKAGES = ["numpy", "pandas", "scipy", "statsmodels", "scikit-learn", "quantile-forest", "joblib"]


def package_versions() -> dict[str, str]:
    out = {"python": platform.python_version()}
    for p in PACKAGES:
        try:
            out[p] = version(p)
        except PackageNotFoundError:
            out[p] = "not installed"
    return out


def save(path: Path, model: Any, metadata: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    meta = {"created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "package_versions": package_versions(), **metadata}
    joblib.dump({"model": model, "metadata": meta}, path, compress=3)


def load(path: Path) -> tuple[Any, dict[str, Any]]:
    obj = joblib.load(path)
    return obj["model"], obj["metadata"]
