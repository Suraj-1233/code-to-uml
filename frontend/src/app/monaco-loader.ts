/**
 * Loads the Monaco editor from a CDN at runtime.
 *
 * Loading Monaco this way (via its AMD loader) keeps it out of the Angular/esbuild
 * bundle and avoids the usual web-worker configuration. If the CDN can't be reached
 * the returned promise rejects, and the editor component falls back to a <textarea>.
 */
const VS = 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min/vs';
const BASE = 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min';

// `any` because Monaco is a runtime global here, not a bundled module.
let loadPromise: Promise<any> | null = null;

export function loadMonaco(): Promise<any> {
  const w = window as any;
  if (w.monaco) {
    return Promise.resolve(w.monaco);
  }
  if (loadPromise) {
    return loadPromise;
  }

  loadPromise = new Promise<any>((resolve, reject) => {
    // Cross-origin worker shim: workers can't be loaded directly from another
    // origin, so we proxy through a data: URL that importScripts the CDN worker.
    w.MonacoEnvironment = {
      getWorkerUrl: () =>
        'data:text/javascript;charset=utf-8,' +
        encodeURIComponent(
          `self.MonacoEnvironment = { baseUrl: '${BASE}/' };` +
          `importScripts('${VS}/base/worker/workerMain.js');`
        ),
    };

    const script = document.createElement('script');
    script.src = `${VS}/loader.js`;
    script.onload = () => {
      w.require.config({ paths: { vs: VS } });
      w.require(['vs/editor/editor.main'], () => resolve(w.monaco));
    };
    script.onerror = () => reject(new Error('Failed to load Monaco editor from CDN'));
    document.body.appendChild(script);
  });

  return loadPromise;
}
