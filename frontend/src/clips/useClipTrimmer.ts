import { useEffect, useState } from 'react';

const FILMSTRIP_FRAMES = 10;
const FILMSTRIP_WIDTH = 120;
const FILMSTRIP_HEIGHT = 68;
const MIN_TRIM_MS = 1000;

interface TrimmerState {
  ready: boolean;
  durationMs: number;
  filmstrip: string[];
  trimStartMs: number;
  trimEndMs: number;
}

const INITIAL_STATE: TrimmerState = {
  ready: false,
  durationMs: 0,
  filmstrip: [],
  trimStartMs: 0,
  trimEndMs: 0,
};

/**
 * Genera una filmstrip (miniaturas reales) y maneja el rango de recorte de una grabación ya
 * hecha (el blob que devuelve useCanvasRecorder) — nunca del video fuente, ver
 * ClipTrimmer.tsx. Las miniaturas salen de sembrar un <video> oculto con el propio blob y
 * sacar snapshots a intervalos regulares; no requiere descargar ni procesar nada server-side.
 */
export function useClipTrimmer(blob: Blob | null, maxDurationMs: number) {
  const [state, setState] = useState<TrimmerState>(INITIAL_STATE);

  useEffect(() => {
    if (!blob) {
      setState(INITIAL_STATE);
      return;
    }

    let cancelled = false;
    const url = URL.createObjectURL(blob);

    (async () => {
      const video = document.createElement('video');
      video.src = url;
      video.muted = true;
      video.playsInline = true;

      await new Promise<void>((resolve, reject) => {
        video.onloadedmetadata = () => resolve();
        video.onerror = () => reject(new Error('No se pudo leer la grabación'));
      });
      if (cancelled) return;

      const durationMs = Math.round((video.duration || 0) * 1000);
      const canvas = document.createElement('canvas');
      canvas.width = FILMSTRIP_WIDTH;
      canvas.height = FILMSTRIP_HEIGHT;
      const ctx = canvas.getContext('2d');
      const frames: string[] = [];

      for (let i = 0; i < FILMSTRIP_FRAMES && durationMs > 0; i++) {
        const targetSeconds = (durationMs * i) / FILMSTRIP_FRAMES / 1000;
        await new Promise<void>((resolve) => {
          video.onseeked = () => resolve();
          video.currentTime = targetSeconds;
        });
        if (cancelled) return;
        ctx?.drawImage(video, 0, 0, FILMSTRIP_WIDTH, FILMSTRIP_HEIGHT);
        frames.push(canvas.toDataURL('image/jpeg', 0.6));
      }
      if (cancelled) return;

      setState({
        ready: true,
        durationMs,
        filmstrip: frames,
        trimStartMs: 0,
        trimEndMs: Math.min(durationMs, maxDurationMs),
      });
    })().catch(() => {
      // Sin filmstrip (blob raro/no seekeable todavía) el recorte igual funciona, solo sin
      // miniaturas — no vale la pena bloquear todo el flujo por esto.
      if (!cancelled) setState((s) => ({ ...s, ready: true }));
    });

    return () => {
      cancelled = true;
      URL.revokeObjectURL(url);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blob]);

  function setTrimStartMs(ms: number) {
    setState((s) => ({
      ...s,
      trimStartMs: Math.max(0, Math.min(ms, s.trimEndMs - MIN_TRIM_MS)),
    }));
  }

  function setTrimEndMs(ms: number) {
    setState((s) => ({
      ...s,
      trimEndMs: Math.min(s.durationMs, Math.max(ms, s.trimStartMs + MIN_TRIM_MS), s.trimStartMs + maxDurationMs),
    }));
  }

  return { ...state, setTrimStartMs, setTrimEndMs };
}
