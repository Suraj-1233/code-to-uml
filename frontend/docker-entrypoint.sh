#!/bin/sh
set -e

# ─────────────────────────────────────────────────────────────
# Runtime backend URL injection
# Replace the placeholder baked into the Angular build with the
# actual BACKEND_URL environment variable set on the container.
#
# Usage: set BACKEND_URL env var in Railway (or any platform)
# e.g.  BACKEND_URL=https://my-backend.up.railway.app
# ─────────────────────────────────────────────────────────────

PLACEHOLDER="BACKEND_URL_PLACEHOLDER"
TARGET_DIR="/usr/share/nginx/html"

if [ -z "$BACKEND_URL" ]; then
  echo "⚠️  WARNING: BACKEND_URL is not set. API calls will fail."
  echo "   Set BACKEND_URL to your backend service URL (e.g. https://xxx.up.railway.app)"
else
  echo "🔧 Injecting BACKEND_URL: $BACKEND_URL"
  # Replace placeholder in all compiled JS files
  find "$TARGET_DIR" -name "*.js" -exec \
    sed -i "s|${PLACEHOLDER}|${BACKEND_URL}|g" {} +
  echo "✅ BACKEND_URL injected successfully"
fi

# Hand off to the default Nginx entrypoint
exec /docker-entrypoint.sh "$@"
