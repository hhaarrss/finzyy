FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -i https://pypi.org/simple --timeout 120
COPY . .
EXPOSE 8000
# With MIGRATION_DATABASE_URL set (the owner login), apply schema changes with it first; the
# server itself then runs as DATABASE_URL (the restricted app login). Without it, unchanged.
# See docs/security/database-access.md.
CMD ["sh", "-c", "if [ -n \"$MIGRATION_DATABASE_URL\" ]; then DATABASE_URL=\"$MIGRATION_DATABASE_URL\" python scripts/ensure_schema.py || exit 1; fi; exec uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000}"]
