FROM python:3.12.12-slim-bookworm@sha256:593bd06efe90efa80dc4eee3948be7c0fde4134606dd40d8dd8dbcade98e669c

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TRINITY_DATABASE=/data/trinity.sqlite3
WORKDIR /app
COPY requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock
COPY pyproject.toml ./
COPY trinity_gateway ./trinity_gateway
RUN pip install --no-cache-dir --no-deps . && \
    useradd --uid 10001 --create-home trinity && mkdir -p /data && chown trinity:trinity /data
USER trinity
EXPOSE 8000
CMD ["uvicorn", "trinity_gateway.server:app_factory", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log"]
