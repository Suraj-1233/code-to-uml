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
# Angular 21 (with @angular/build:application) outputs to dist/frontend/browser
COPY --from=builder /app/dist/frontend/browser /usr/share/nginx/html

# Custom Nginx config to support Angular's client-side routing (HTML5 pushState)
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
