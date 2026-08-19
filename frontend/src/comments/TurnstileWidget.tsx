import { useEffect, useRef, useState } from 'react';

// Sin site key real configurada en dev (docs/SPEC.md sección 13) — ver el fallback más
// abajo. En producción, VITE_TURNSTILE_SITE_KEY carga el widget real de Cloudflare.
const SITE_KEY = import.meta.env.VITE_TURNSTILE_SITE_KEY as string | undefined;
const SCRIPT_SRC = 'https://challenges.cloudflare.com/turnstile/v0/api.js';

interface TurnstileApi {
  render: (
    container: HTMLElement,
    options: {
      sitekey: string;
      callback: (token: string) => void;
      'expired-callback': () => void;
      'error-callback': () => void;
    },
  ) => string;
  remove: (widgetId: string) => void;
}

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

let scriptLoadPromise: Promise<void> | null = null;
function loadTurnstileScript(): Promise<void> {
  if (!scriptLoadPromise) {
    scriptLoadPromise = new Promise((resolve, reject) => {
      if (document.querySelector(`script[src="${SCRIPT_SRC}"]`)) {
        resolve();
        return;
      }
      const script = document.createElement('script');
      script.src = SCRIPT_SRC;
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('No se pudo cargar Turnstile'));
      document.head.appendChild(script);
    });
  }
  return scriptLoadPromise;
}

/** CAPTCHA anti-bot para comentarios de invitados (docs/SPEC.md sección 11.4). Sin
 * VITE_TURNSTILE_SITE_KEY configurada (el caso de dev/local, ver .env.example) muestra un
 * botón que simula la verificación en vez del widget real — el backend (MockTurnstileClient)
 * igual exige que se haya mandado algún token, no valida nada real en ninguno de los dos
 * lados hasta tener una cuenta de Cloudflare real conectada. */
export function TurnstileWidget({ onToken }: { onToken: (token: string | null) => void }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [devVerified, setDevVerified] = useState(false);

  useEffect(() => {
    if (!SITE_KEY) return;
    let widgetId: string | undefined;
    let cancelled = false;

    loadTurnstileScript()
      .then(() => {
        if (cancelled || !containerRef.current || !window.turnstile) return;
        widgetId = window.turnstile.render(containerRef.current, {
          sitekey: SITE_KEY,
          callback: (token: string) => onToken(token),
          'expired-callback': () => onToken(null),
          'error-callback': () => onToken(null),
        });
      })
      .catch(() => onToken(null));

    return () => {
      cancelled = true;
      if (widgetId !== undefined) window.turnstile?.remove(widgetId);
    };
  }, [onToken]);

  if (!SITE_KEY) {
    return (
      <label className="turnstile-dev">
        <input
          type="checkbox"
          checked={devVerified}
          onChange={(event) => {
            setDevVerified(event.target.checked);
            onToken(event.target.checked ? 'dev-bypass' : null);
          }}
        />
        No soy un robot (modo dev — Turnstile real no configurado)
      </label>
    );
  }

  return <div ref={containerRef} />;
}
