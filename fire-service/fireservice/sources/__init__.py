"""Source plugins. Import a module here to register it; enable it in sources.json."""
import json
from .base import REGISTRY, register, standardize, STD_COLS
from . import firms_api, local_csv   # noqa: F401  (registration side effect)


def load_sources(path: str) -> list:
    cfg = json.load(open(path))
    out = []
    for s in cfg.get('sources', []):
        if not s.get('enabled', True):
            continue
        kind = s['kind']
        if kind not in REGISTRY:
            raise ValueError(f'unknown source kind {kind}; registered: {sorted(REGISTRY)}')
        out.append(REGISTRY[kind](**{k: v for k, v in s.items() if k not in ('kind', 'enabled')}))
    return out
