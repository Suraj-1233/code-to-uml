// Production environment — BACKEND_URL is injected at Docker build time via ARG
// See frontend/Dockerfile: ARG BACKEND_URL is passed via docker build --build-arg
export const environment = {
  production: true,
  apiUrl: 'BACKEND_URL_PLACEHOLDER',
};
