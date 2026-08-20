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
 *
 * @param recordedDurationMs cuánto duró realmente la grabación (recorder.elapsedMs de
 * useCanvasRecorder) — se usa como fuente de verdad para la duración total en vez de
 * `video.duration`: los blobs que arma MediaRecorder son WebM sin duración en el header (es
 * un formato pensado para streaming), así que el navegador reporta `Infinity` hasta forzar
 * una búsqueda cerca del final — un truco frágil que además dejaba `durationMs` en
 * `Infinity`/`NaN` y terminaba mandando "NaN" al backend. Achicar el <video> oculto a "solo
 * buscar frames puntuales" evita todo ese problema: buscar un currentTime específico no
 * necesita saber la duración total de antemano.
 */
export function useClipTrimmer(blob: Blob | null, recordedDurationMs: number, maxDurationMs: number) {
  const [state, setState] = useState<TrimmerState>(INITIAL_STATE);

  useEffect(() => {
    if (!blob || recordedDurationMs <= 0) {
      setState(INITIAL_STATE);
      return;
    }

    let cancelled = false;
    const url = URL.createObjectURL(blob);
    const durationMs = Math.round(recordedDurationMs);

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

      const canvas = document.createElement('canvas');
      canvas.width = FILMSTRIP_WIDTH;
      canvas.height = FILMSTRIP_HEIGHT;
      const ctx = canvas.getContext('2d');
      const frames: string[] = [];

      for (let i = 0; i < FILMSTRIP_FRAMES; i++) {
        // Un pelín antes del final de cada tramo (0.95x), no justo en el límite — algunos
        // navegadores clampean un seek exactamente igual a la duración real al frame anterior
        // de forma inconsistente.
        const targetSeconds = (durationMs * (i + 0.95)) / FILMSTRIP_FRAMES / 1000;
        await new Promise<void>((resolve) => {
          const onSeeked = () => {
            video.removeEventListener('seeked', onSeeked);
            resolve();
          };
          video.addEventListener('seeked', onSeeked);
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
      // miniaturas — la duración real ya la sabíamos de antemano (recordedDurationMs), así
      // que el rango de recorte sigue siendo correcto aunque falle esto.
      if (!cancelled) {
        setState({
          ready: true,
          durationMs,
          filmstrip: [],
          trimStartMs: 0,
          trimEndMs: Math.min(durationMs, maxDurationMs),
        });
      }
    });

    return () => {
      cancelled = true;
      URL.revokeObjectURL(url);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blob, recordedDurationMs]);

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
