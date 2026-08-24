import { useState } from 'react';

const MIN_TRIM_MS = 1000;

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, max));
}

interface AudioTrimState {
  trimStartMs: number;
  trimEndMs: number;
}

/**
 * Recorte del fragmento de audio de reemplazo a usar (docs/SPEC.md sección 1) — a diferencia
 * de useMediaTrimmer (video), acá la duración ya se conoce de entrada (la devuelve el propio
 * POST /api/audio/upload o /import-link, ver AudioTrackResponse), así que no hace falta cargar
 * ningún elemento oculto ni generar filmstrip: es puro estado síncrono. Mismo criterio de
 * recorte que el video (máximo 20s, elegible en cualquier dirección) pero SIN zoom/pan — un
 * audio no necesita precisión de frame, agregarle esa complejidad no aporta nada acá.
 */
export function useAudioTrimmer(durationMs: number, maxDurationMs: number) {
  const [state, setState] = useState<AudioTrimState>(() => ({
    trimStartMs: 0,
    trimEndMs: Math.min(durationMs, maxDurationMs),
  }));

  function setTrimStartMs(ms: number) {
    setState((s) => ({
      ...s,
      trimStartMs: clamp(ms, Math.max(0, s.trimEndMs - maxDurationMs), s.trimEndMs - MIN_TRIM_MS),
    }));
  }

  function setTrimEndMs(ms: number) {
    setState((s) => ({
      ...s,
      trimEndMs: clamp(ms, s.trimStartMs + MIN_TRIM_MS, Math.min(durationMs, s.trimStartMs + maxDurationMs)),
    }));
  }

  /** Mueve la ventana completa preservando su duración — ver comentario equivalente en
   * useMediaTrimmer.setWindowStartMs. */
  function setWindowStartMs(ms: number) {
    setState((s) => {
      const windowLen = s.trimEndMs - s.trimStartMs;
      const clampedStart = clamp(ms, 0, durationMs - windowLen);
      return { trimStartMs: clampedStart, trimEndMs: clampedStart + windowLen };
    });
  }

  /** Selección desde un ancla fija hacia donde esté el puntero — ver comentario equivalente
   * en useMediaTrimmer.setRangeFromAnchor. */
  function setRangeFromAnchor(anchorMs: number, movingMs: number) {
    setState(() => {
      let start: number;
      let end: number;
      if (movingMs >= anchorMs) {
        start = anchorMs;
        end = clamp(movingMs, start + MIN_TRIM_MS, Math.min(durationMs, start + maxDurationMs));
      } else {
        end = anchorMs;
        start = clamp(movingMs, Math.max(0, end - maxDurationMs), end - MIN_TRIM_MS);
      }
      return { trimStartMs: Math.max(0, start), trimEndMs: Math.min(durationMs, end) };
    });
  }

  return { ...state, setTrimStartMs, setTrimEndMs, setWindowStartMs, setRangeFromAnchor };
}
