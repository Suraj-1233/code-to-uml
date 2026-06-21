# ──────────────────────────────────────────────
# Stage 1 — Build the Angular app
# ──────────────────────────────────────────────
FROM node:22-alpine AS builder

WORKDIR /app

# Copy lock files first for layer caching
COPY package.json package-lock.json ./

# Install dependencies (ci = clean install, respects lock file)
RUN npm ci

# Copy source and build for production
# BACKEND_URL is NOT injected at build time — it is injected at runtime
# by docker-entrypoint.sh using the BACKEND_URL environment variable.
# This allows the same Docker Hub image to be used across all environments.
COPY . .
RUN npm run build

# ──────────────────────────────────────────────
# Stage 2 — Serve with Nginx
# ──────────────────────────────────────────────
FROM nginx:1.27-alpine AS runtime

LABEL maintainer="codetouml"
LABEL description="code-to-uml Angular frontend"

# Remove the default Nginx site
RUN rm -rf /usr/share/nginx/html/*

# Copy built assets from the builder stage
# Angular (with @angular/build:application) outputs to dist/frontend/browser
COPY --from=builder /app/dist/frontend/browser /usr/share/nginx/html

# Custom Nginx config to support Angular's client-side routing (HTML5 pushState)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Runtime entrypoint: replaces BACKEND_URL_PLACEHOLDER in compiled JS
# with the actual BACKEND_URL env var before Nginx starts
COPY docker-entrypoint.sh /docker-app-entrypoint.sh
RUN chmod +x /docker-app-entrypoint.sh

EXPOSE 80 443

ENTRYPOINT ["/docker-app-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
