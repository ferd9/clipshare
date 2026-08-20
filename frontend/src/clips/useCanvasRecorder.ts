import { useCallback, useEffect, useRef, useState } from 'react';

const MAX_DURATION_MS = 20_000;

interface CanvasRecorderState {
  isRecording: boolean;
  elapsedMs: number;
  blob: Blob | null;
  previewUrl: string | null;
  error: string | null;
}

const INITIAL_STATE: CanvasRecorderState = {
  isRecording: false,
  elapsedMs: 0,
  blob: null,
  previewUrl: null,
  error: null,
};

interface CropRect {
  sx: number;
  sy: number;
  sWidth: number;
  sHeight: number;
}

/**
 * Recorta el frame capturado a la posición en pantalla de `element` (el reproductor), en vez
 * de grabar la pestaña entera — sin esto, el clip final incluye el header de ClipShare, el
 * contador "Grabando…" y el botón "Detener" de fondo, porque getDisplayMedia comparte toda la
 * pestaña, no solo el reproductor.
 *
 * Por PROPORCIÓN dentro del viewport (0 a 1), no multiplicando por devicePixelRatio: la
 * primera versión asumía que el stream capturado mide exactamente
 * `window.innerWidth/Height * devicePixelRatio` píxeles, pero Chrome no garantiza esa
 * relación al compartir una pestaña (puede recapturar a otra resolución) — con esa cuenta el
 * recorte quedaba mal ubicado en la práctica (recortaba de más o de menos según el caso).
 * Calculando qué FRACCIÓN del viewport ocupa el reproductor y aplicando esa misma fracción a
 * `sourceVideo.videoWidth/Height` (la resolución REAL que ya llegó, sea cual sea), el
 * resultado no depende de esa relación en absoluto.
 *
 * Asume que lo compartido es efectivamente "esta pestaña" en su posición actual de scroll
 * (lo que pide `preferCurrentTab: true`) — best-effort, no garantizado: si el usuario elige
 * compartir otra ventana/pantalla desde el selector nativo del navegador, el recorte va a
 * quedar mal ubicado. Por eso se calcula una sola vez al arrancar (no en cada frame) y se
 * descarta (cae a grabar el frame completo) si da un rectángulo degenerado.
 */
function computeCropRect(element: HTMLElement | null | undefined, sourceVideo: HTMLVideoElement): CropRect | null {
  if (!element || !sourceVideo.videoWidth || !sourceVideo.videoHeight) return null;
  const rect = element.getBoundingClientRect();
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  if (rect.width <= 0 || rect.height <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return null;

  const leftRatio = rect.left / viewportWidth;
  const topRatio = rect.top / viewportHeight;
  const widthRatio = rect.width / viewportWidth;
  const heightRatio = rect.height / viewportHeight;

  const sx = Math.max(0, Math.round(leftRatio * sourceVideo.videoWidth));
  const sy = Math.max(0, Math.round(topRatio * sourceVideo.videoHeight));
  // Ancho/alto pares: yuv420p (submuestreo de croma) los exige, y ffmpeg server-side rechaza
  // directo un ancho impar sin escribir nada (ver el "-vf scale" en FfmpegProcessor, que
  // ahora también se blinda del lado del backend — doble resguardo, no writes basura acá
  // tampoco). toEven trunca hacia abajo, nunca se pasa del borde real del frame capturado.
  const toEven = (n: number) => n - (n % 2);
  const sWidth = toEven(Math.min(sourceVideo.videoWidth - sx, Math.round(widthRatio * sourceVideo.videoWidth)));
  const sHeight = toEven(Math.min(sourceVideo.videoHeight - sy, Math.round(heightRatio * sourceVideo.videoHeight)));
  if (sWidth <= 0 || sHeight <= 0) return null;

  return { sx, sy, sWidth, sHeight };
}

/**
 * Graba hasta 20s de lo que el usuario comparte de su propia pestaña (getDisplayMedia) y lo
 * redibuja frame a frame en un <canvas> oculto antes de pasarlo a MediaRecorder — igual que
 * describe docs/SPEC.md sección 9 (Caso B), paso 3.
 *
 * Por qué screen-share y no leer directo el <video> del embed: YouTube/Vimeo/Twitch se
 * respetan usando su reproductor oficial (iframe), y un iframe de otro origen no se puede
 * "pintar" en un canvas (aislamiento del navegador, la razón de ser de esa restricción es
 * justamente impedir la extracción de video). getDisplayMedia con permiso explícito del
 * usuario es la única vía compatible con eso — nunca se descarga el video fuente.
 */
export function useCanvasRecorder() {
  const [state, setState] = useState<CanvasRecorderState>(INITIAL_STATE);

  const displayStreamRef = useRef<MediaStream | null>(null);
  const sourceVideoRef = useRef<HTMLVideoElement | null>(null);
  const canvasStreamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const mimeTypeRef = useRef<string>('video/webm');
  const rafIdRef = useRef<number | null>(null);
  const tickIntervalRef = useRef<number | null>(null);
  const stopTimeoutRef = useRef<number | null>(null);
  const startTimeRef = useRef(0);

  const releaseCaptureResources = useCallback(() => {
    if (rafIdRef.current !== null) cancelAnimationFrame(rafIdRef.current);
    rafIdRef.current = null;
    if (tickIntervalRef.current !== null) window.clearInterval(tickIntervalRef.current);
    tickIntervalRef.current = null;
    if (stopTimeoutRef.current !== null) window.clearTimeout(stopTimeoutRef.current);
    stopTimeoutRef.current = null;

    displayStreamRef.current?.getTracks().forEach((track) => track.stop());
    displayStreamRef.current = null;
    canvasStreamRef.current?.getTracks().forEach((track) => track.stop());
    canvasStreamRef.current = null;

    sourceVideoRef.current?.remove();
    sourceVideoRef.current = null;
  }, []);

  const stop = useCallback(() => {
    if (recorderRef.current && recorderRef.current.state !== 'inactive') {
      recorderRef.current.stop();
    }
  }, []);

  useEffect(() => () => releaseCaptureResources(), [releaseCaptureResources]);

  const start = useCallback(async (cropElement?: HTMLElement | null) => {
    setState((s) => ({ ...s, error: null }));

    let displayStream: MediaStream;
    try {
      // width/height "ideal" en píxeles de dispositivo (no CSS): sin esto, Chrome puede
      // capturar la pestaña a una resolución "conveniente" propia (no necesariamente la del
      // viewport) y reescalar/letterboxear el contenido para que entre — lo que rompe
      // cualquier cuenta de recorte basada en la posición del reproductor en pantalla, aunque
      // esa cuenta sea por proporción (ver computeCropRect). Pedir explícitamente el tamaño
      // real de la ventana como "ideal" hace que el stream capturado corresponda 1:1 (o muy
      // cerca) con lo que measurea getBoundingClientRect(), sin ese reescalado de por medio.
      const dpr = window.devicePixelRatio || 1;
      const constraints = {
        video: {
          width: { ideal: Math.round(window.innerWidth * dpr) },
          height: { ideal: Math.round(window.innerHeight * dpr) },
        },
        audio: true,
        preferCurrentTab: true,
      } as DisplayMediaStreamOptions;
      displayStream = await navigator.mediaDevices.getDisplayMedia(constraints);
    } catch {
      setState((s) => ({ ...s, error: 'Necesitamos permiso para compartir la pestaña para poder grabar.' }));
      return;
    }
    displayStreamRef.current = displayStream;

    const sourceVideo = document.createElement('video');
    sourceVideo.muted = true;
    sourceVideo.playsInline = true;
    sourceVideo.srcObject = displayStream;
    sourceVideoRef.current = sourceVideo;
    await sourceVideo.play();

    // El tamaño del canvas se fija UNA vez (no se puede reajustar en caliente sin
    // artefactos) a partir de esta primera medición. Pero el RECORTE que se dibuja en cada
    // frame se recalcula en draw(), no acá — esta primera medición puede caer en un instante
    // en que el layout todavía no llegó a su estado final "grabando" (ej. el botón "Empezar
    // a grabar" recién está por cambiar a "Grabando…/Detener", que ocupa otro alto), y
    // remedir en cada frame (barato: getBoundingClientRect() 30 veces por segundo no pesa
    // nada) elimina esa categoría entera de bug de timing sin downside real.
    const initialCrop = computeCropRect(cropElement, sourceVideo);

    const canvas = document.createElement('canvas');
    canvas.width = initialCrop ? initialCrop.sWidth : sourceVideo.videoWidth || 1280;
    canvas.height = initialCrop ? initialCrop.sHeight : sourceVideo.videoHeight || 720;
    const ctx = canvas.getContext('2d');

    const draw = () => {
      if (ctx && sourceVideoRef.current) {
        const crop = computeCropRect(cropElement, sourceVideoRef.current);
        if (crop) {
          ctx.drawImage(sourceVideoRef.current, crop.sx, crop.sy, crop.sWidth, crop.sHeight, 0, 0, canvas.width, canvas.height);
        } else {
          ctx.drawImage(sourceVideoRef.current, 0, 0, canvas.width, canvas.height);
        }
      }
      rafIdRef.current = requestAnimationFrame(draw);
    };
    draw();

    const canvasStream = canvas.captureStream(30);
    const audioTrack = displayStream.getAudioTracks()[0];
    if (audioTrack) canvasStream.addTrack(audioTrack);
    canvasStreamRef.current = canvasStream;

    const mimeType =
      ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'].find((type) =>
        MediaRecorder.isTypeSupported(type),
      ) ?? '';
    mimeTypeRef.current = mimeType || 'video/webm';

    const recorder = new MediaRecorder(canvasStream, mimeType ? { mimeType } : undefined);
    chunksRef.current = [];
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunksRef.current.push(event.data);
    };
    recorder.onstop = () => {
      const blob = new Blob(chunksRef.current, { type: mimeTypeRef.current });
      const previewUrl = URL.createObjectURL(blob);
      setState((s) => ({ ...s, isRecording: false, blob, previewUrl }));
      releaseCaptureResources();
    };
    recorderRef.current = recorder;

    // Si el usuario corta el compartir desde el propio prompt nativo del navegador.
    displayStream.getVideoTracks()[0]?.addEventListener('ended', stop);

    recorder.start();
    startTimeRef.current = Date.now();
    setState({ isRecording: true, elapsedMs: 0, blob: null, previewUrl: null, error: null });

    tickIntervalRef.current = window.setInterval(() => {
      setState((s) => ({ ...s, elapsedMs: Date.now() - startTimeRef.current }));
    }, 200);
    stopTimeoutRef.current = window.setTimeout(stop, MAX_DURATION_MS);
  }, [releaseCaptureResources, stop]);

  const reset = useCallback(() => {
    setState((s) => {
      if (s.previewUrl) URL.revokeObjectURL(s.previewUrl);
      return INITIAL_STATE;
    });
  }, []);

  return { ...state, maxDurationMs: MAX_DURATION_MS, start, stop, reset };
}
