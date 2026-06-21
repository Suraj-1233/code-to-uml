#!/bin/sh
set -e

# ─────────────────────────────────────────────────────────────
# Runtime API base-URL injection
# Replaces BACKEND_URL_PLACEHOLDER (baked into the Angular build)
# with the BACKEND_URL env var.
#
#  • EC2 / docker-compose  → leave BACKEND_URL empty. The app then calls a
#    same-origin "/api" path which Nginx proxies to the backend container
#    (see nginx.conf). No CORS, only ports 80/443 exposed.
#  • Different origin       → set BACKEND_URL to an absolute URL,
#    e.g. https://api.example.com
# ─────────────────────────────────────────────────────────────

PLACEHOLDER="BACKEND_URL_PLACEHOLDER"
TARGET_DIR="/usr/share/nginx/html"
URL="${BACKEND_URL:-}"

echo "🔧 API base URL = '${URL}' (empty = relative /api via the Nginx proxy)"
find "$TARGET_DIR" -name "*.js" -exec sed -i "s|${PLACEHOLDER}|${URL}|g" {} +

# Hand off to the default Nginx entrypoint
exec /docker-entrypoint.sh "$@"
