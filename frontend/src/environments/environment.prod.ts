// Production environment — BACKEND_URL is injected at Docker build time via ARG
// See frontend/Dockerfile: ARG BACKEND_URL is passed via docker build --build-arg
export const environment = {
  production: true,
  apiUrl: 'BACKEND_URL_PLACEHOLDER',
  googleClientId: '266952244092-44f7ite9ov7r19ctds4pdsrgpv5qm2kk.apps.googleusercontent.com',
};
