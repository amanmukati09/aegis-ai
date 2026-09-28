# Slim single-stage image. Core deps are light; heavy ML (torch/tigramite) is
# install-to-activate on a capable host — see requirements-heavy.txt / LAPTOP_SETUP.md.
FROM python:3.11-slim AS runtime
WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    ML_DATA_DIR=/app/data

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Non-root. Pre-create the data dir (RL Q-table persistence) owned by the runtime user so
# the mounted named volume is writable — a fresh volume inherits this mount-point ownership.
RUN useradd -r -u 1001 aegis \
    && mkdir -p /app/data \
    && chown -R aegis:aegis /app/data
USER aegis

EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
