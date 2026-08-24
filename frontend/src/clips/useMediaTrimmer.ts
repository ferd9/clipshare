import { useEffect, useRef, useState } from 'react';

const FILMSTRIP_FRAMES = 10;
const FILMSTRIP_WIDTH = 120;
const FILMSTRIP_HEIGHT = 68;
const MIN_TRIM_MS = 1000;
const METADATA_TIMEOUT_MS = 15_000;

interface TrimmerState {
  ready: boolean;
  durationMs: number;
  trimStartMs: number;
  trimEndMs: number;
  error: string | null;
  zoom: number;
  maxZoom: number;
  viewStartMs: number;
  viewEndMs: number;
  /** Filmstrip de la vista actual (afectada por zoom/pan) — se muestra en el track principal. */
  filmstrip: string[];
  /** Filmstrip de TODO el video, generada una sola vez — se muestra en la mini-barra de
   * resumen para poder ubicarse y desplazarse sin importar el zoom. */
  overviewFilmstrip: string[];
}

const INITIAL_STATE: TrimmerState = {
  ready: false,
  durationMs: 0,
  trimStartMs: 0,
  trimEndMs: 0,
  error: null,
  zoom: 1,
  maxZoom: 1,
  viewStartMs: 0,
  viewEndMs: 0,
  filmstrip: [],
  overviewFilmstrip: [],
};

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, max));
}

/** Busca `count` frames repartidos en [rangeStartMs, rangeEndMs] sobre un <video> ya cargado
 * y los dibuja a un JPEG chico — compartido entre la filmstrip de la vista actual y la de la
 * mini-barra de resumen (ver useMediaTrimmer). `stillCurrent` corta el loop temprano si el
 * caller ya no necesita el resultado (cambió de vista/zoom, o se desmontó el componente). */
async function captureFrames(video: HTMLVideoElement, rangeStartMs: number, rangeEndMs: number, count: number, stillCurrent: () => boolean): Promise<string[]> {
  const canvas = document.createElement('canvas');
  canvas.width = FILMSTRIP_WIDTH;
  canvas.height = FILMSTRIP_HEIGHT;
  const ctx = canvas.getContext('2d');
  const span = rangeEndMs - rangeStartMs;
  const frames: string[] = [];

  for (let i = 0; i < count; i++) {
    // Un pelín antes del final de cada tramo (0.95x), no justo en el límite — algunos
    // navegadores clampean un seek exactamente igual a la duración real al frame anterior de
    // forma inconsistente.
    const targetSeconds = (rangeStartMs + (span * (i + 0.95)) / count) / 1000;
    const seeked = await new Promise<boolean>((resolve) => {
      const timeoutId = setTimeout(() => resolve(false), METADATA_TIMEOUT_MS);
      const onSeeked = () => {
        clearTimeout(timeoutId);
        video.removeEventListener('seeked', onSeeked);
        resolve(true);
      };
      video.addEventListener('seeked', onSeeked);
      video.currentTime = targetSeconds;
    });
    if (!stillCurrent()) return frames;
    if (!seeked) break; // best-effort: se publica igual, solo con menos miniaturas
    ctx?.drawImage(video, 0, 0, FILMSTRIP_WIDTH, FILMSTRIP_HEIGHT);
    frames.push(canvas.toDataURL('image/jpeg', 0.6));
  }
  return frames;
}

function createHiddenVideo(videoUrl: string): HTMLVideoElement {
  const video = document.createElement('video');
  video.src = videoUrl; // blob: URL ya en memoria (ver ClipEditPage/getEditableBlobUrl) —
  // crear más de un <video> contra la misma blob URL no pega de nuevo por red.
  video.muted = true;
  video.playsInline = true;
  return video;
}

/**
 * Recorte sobre el archivo "editable" ya normalizado por el worker (fase STAGE, ver
 * docs/SPEC.md) — un mp4 real con duración real en el contenedor.
 *
 * El zoom acerca la VISTA del timeline (viewStartMs/viewEndMs) para elegir el recorte con
 * precisión cuando la fuente es mucho más larga que los ~20s seleccionables; el pan
 * (setViewStartMs) mueve esa vista de forma independiente de dónde está la selección — sin
 * esto, una vez acercado el zoom no había manera de recorrer el resto del video sin volver a
 * alejar, mover la selección, y volver a acercar.
 */
export function useMediaTrimmer(videoUrl: string | null, maxDurationMs: number) {
  const [state, setState] = useState<TrimmerState>(INITIAL_STATE);
  const viewVideoRef = useRef<HTMLVideoElement | null>(null);
  const filmstripRequestIdRef = useRef(0);

  // Efecto 1: carga metadata una sola vez por video (se mantiene el <video> vivo en un ref
  // para que el efecto 3 pueda re-usarlo y solo tenga que volver a buscar/dibujar frames, sin
  // recargar el archivo entero cada vez que cambia el zoom/pan).
  useEffect(() => {
    viewVideoRef.current = null;
    if (!videoUrl) {
      setState(INITIAL_STATE);
      return;
    }

    let cancelled = false;
    const video = createHiddenVideo(videoUrl);

    // Sin timeout, un video que nunca dispara loadedmetadata/error (conexión colgada, o el
    // propio navegador atascado con <video> — pasó exactamente eso probando con
    // claude-in-chrome, ver clipshare-stack-quirks) deja "Preparando vista previa…" para
    // siempre, sin ningún mensaje ni salida para el usuario.
    const timeoutId = setTimeout(() => {
      if (cancelled) return;
      setState({ ...INITIAL_STATE, error: 'El video tardó demasiado en cargar' });
    }, METADATA_TIMEOUT_MS);

    video.onloadedmetadata = () => {
      clearTimeout(timeoutId);
      if (cancelled) return;
      const durationMs = Math.round(video.duration * 1000);
      viewVideoRef.current = video;
      setState({
        ready: true,
        durationMs,
        trimStartMs: 0,
        trimEndMs: Math.min(durationMs, maxDurationMs),
        error: null,
        zoom: 1,
        maxZoom: Math.max(1, Math.floor(durationMs / maxDurationMs)),
        viewStartMs: 0,
        viewEndMs: durationMs,
        filmstrip: [],
        overviewFilmstrip: [],
      });
    };
    video.onerror = () => {
      clearTimeout(timeoutId);
      if (cancelled) return;
      setState({ ...INITIAL_STATE, error: 'No se pudo leer el video' });
    };

    return () => {
      cancelled = true;
      clearTimeout(timeoutId);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [videoUrl]);

  // Efecto 2: filmstrip de resumen (TODO el video) — se genera una sola vez apenas se conoce
  // la duración, en un <video> propio para no pelearse por seeks con el de la vista actual
  // (efecto 3, que corre mucho más seguido). No depende del zoom/pan.
  useEffect(() => {
    if (!state.ready || !videoUrl || state.durationMs <= 0) return;
    let cancelled = false;
    const video = createHiddenVideo(videoUrl);
    video.onloadedmetadata = () => {
      captureFrames(video, 0, state.durationMs, FILMSTRIP_FRAMES, () => !cancelled).then((frames) => {
        if (!cancelled) setState((s) => ({ ...s, overviewFilmstrip: frames }));
      });
    };
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.ready, state.durationMs]);

  // Efecto 3: (re)genera la filmstrip de la vista actual — corre de nuevo cada vez que cambia
  // [viewStartMs, viewEndMs] (al hacer zoom o pan). Un requestId descarta resultados de una
  // corrida vieja si el usuario cambia de vista de nuevo antes de que la anterior termine.
  useEffect(() => {
    const video = viewVideoRef.current;
    if (!state.ready || !video || state.viewEndMs <= state.viewStartMs) return;

    const requestId = ++filmstripRequestIdRef.current;
    let cancelled = false;
    captureFrames(video, state.viewStartMs, state.viewEndMs, FILMSTRIP_FRAMES, () => !cancelled && filmstripRequestIdRef.current === requestId).then((frames) => {
      if (cancelled || filmstripRequestIdRef.current !== requestId) return;
      setState((s) => ({ ...s, filmstrip: frames }));
    });

    return () => {
      cancelled = true;
    };
  }, [state.ready, state.viewStartMs, state.viewEndMs]);

  function setTrimStartMs(ms: number) {
    setState((s) => ({
      ...s,
      trimStartMs: clamp(ms, Math.max(0, s.trimEndMs - maxDurationMs), s.trimEndMs - MIN_TRIM_MS),
    }));
  }

  function setTrimEndMs(ms: number) {
    setState((s) => ({
      ...s,
      trimEndMs: clamp(ms, s.trimStartMs + MIN_TRIM_MS, Math.min(s.durationMs, s.trimStartMs + maxDurationMs)),
    }));
  }

  /**
   * Mueve la ventana completa (recorte de hasta 20s) a lo largo del timeline, preservando su
   * duración actual — a diferencia de setTrimStartMs/setTrimEndMs (que mueven un extremo por
   * separado, para achicar/agrandar la ventana).
   */
  function setWindowStartMs(ms: number) {
    setState((s) => {
      const windowLen = s.trimEndMs - s.trimStartMs;
      const clampedStart = clamp(ms, 0, s.durationMs - windowLen);
      return { ...s, trimStartMs: clampedStart, trimEndMs: clampedStart + windowLen };
    });
  }

  /**
   * Selección "de punta a punta" desde un ancla fija hacia donde esté el puntero ahora —
   * permite arrancar una selección nueva y extenderla para cualquier lado del punto donde se
   * apretó. El ancla queda fija; el otro extremo sigue al puntero, acotado a maxDurationMs.
   */
  function setRangeFromAnchor(anchorMs: number, movingMs: number) {
    setState((s) => {
      let start: number;
      let end: number;
      if (movingMs >= anchorMs) {
        start = anchorMs;
        end = clamp(movingMs, start + MIN_TRIM_MS, Math.min(s.durationMs, start + maxDurationMs));
      } else {
        end = anchorMs;
        start = clamp(movingMs, Math.max(0, end - maxDurationMs), end - MIN_TRIM_MS);
      }
      return { ...s, trimStartMs: Math.max(0, start), trimEndMs: Math.min(s.durationMs, end) };
    });
  }

  /** Mueve la VISTA (zoom/pan), preservando su ancho actual — independiente de la selección,
   * para poder recorrer cualquier parte del video sin importar dónde esté el recorte elegido
   * (ver mini-barra de resumen en ClipTrimmer). */
  function setViewStartMs(ms: number) {
    setState((s) => {
      const viewWidth = s.viewEndMs - s.viewStartMs;
      const clampedStart = clamp(ms, 0, Math.max(0, s.durationMs - viewWidth));
      return { ...s, viewStartMs: clampedStart, viewEndMs: clampedStart + viewWidth };
    });
  }

  function applyZoomCenteredOn(s: TrimmerState, nextZoom: number, centerMs: number): TrimmerState {
    const viewWidth = s.durationMs / nextZoom;
    let viewStart = centerMs - viewWidth / 2;
    let viewEnd = viewStart + viewWidth;
    if (viewStart < 0) {
      viewEnd -= viewStart;
      viewStart = 0;
    }
    if (viewEnd > s.durationMs) {
      viewStart -= viewEnd - s.durationMs;
      viewEnd = s.durationMs;
    }
    return { ...s, zoom: nextZoom, viewStartMs: clamp(viewStart, 0, s.durationMs), viewEndMs: clamp(viewEnd, 0, s.durationMs) };
  }

  // Acercar/alejar mantiene el CENTRO DE LA VISTA actual (no el de la selección) — al ser el
  // pan independiente ahora, "dónde estoy mirando" y "qué tengo seleccionado" son cosas
  // distintas, y lo natural es que el zoom no te saque de donde ya estabas viendo.
  function zoomIn() {
    setState((s) => applyZoomCenteredOn(s, Math.min(s.maxZoom, s.zoom * 2), (s.viewStartMs + s.viewEndMs) / 2));
  }

  function zoomOut() {
    setState((s) => applyZoomCenteredOn(s, Math.max(1, s.zoom / 2), (s.viewStartMs + s.viewEndMs) / 2));
  }

  /** Acerca la vista para que encuadre EXACTAMENTE el fragmento ya elegido — en vez de tener
   * que acercar a mano con +/− hasta encontrarlo. Centrado en la selección, no en la vista
   * actual (a diferencia de zoomIn/zoomOut), que es justamente el punto de esta acción. */
  function zoomToSelection() {
    setState((s) => {
      const span = Math.max(s.trimEndMs - s.trimStartMs, 1);
      const nextZoom = clamp(s.durationMs / span, 1, s.maxZoom);
      return applyZoomCenteredOn(s, nextZoom, (s.trimStartMs + s.trimEndMs) / 2);
    });
  }

  return {
    ...state,
    setTrimStartMs,
    setTrimEndMs,
    setWindowStartMs,
    setRangeFromAnchor,
    zoomToSelection,
    setViewStartMs,
    zoomIn,
    zoomOut,
  };
}
