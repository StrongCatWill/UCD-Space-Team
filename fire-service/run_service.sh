#!/bin/zsh
# Starts the fire-service with a FIRMS MAP_KEY typed interactively (never written to disk or logs).
cd "${0:A:h}"
if [[ -z "$FIRMS_MAP_KEY" ]]; then
  print -n "FIRMS MAP_KEY 입력 (화면에 표시되지 않음, 비우고 Enter = ingest 없이 실행): "
  read -rs FIRMS_MAP_KEY; print
fi
export FIRMS_MAP_KEY
export ART=artifacts FIRE_SOURCES=sources.json INGEST_TOKEN="${INGEST_TOKEN:-demo}"
lsof -ti tcp:8000 | xargs kill 2>/dev/null; sleep 1
print "fire-service → http://localhost:8000  (Ctrl+C로 종료)"
exec .venv/bin/uvicorn app:app --host 0.0.0.0 --port 8000
