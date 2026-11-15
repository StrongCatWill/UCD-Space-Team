# Python fire-service (FastAPI + fire-lstm-v1). Build context = fire-service/.
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
# CPU-only torch keeps the image ~1 GB smaller; version matches requirements.txt
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.15.0 \
 && pip install --no-cache-dir -r requirements.txt
COPY . .
ENV ART=artifacts \
    FIRE_SOURCES=sources.json \
    PORT=8000
# FIRMS_MAP_KEY and INGEST_TOKEN come from the platform's secret settings, never from the image
EXPOSE 8000
CMD ["sh", "-c", "exec uvicorn app:app --host 0.0.0.0 --port ${PORT}"]
