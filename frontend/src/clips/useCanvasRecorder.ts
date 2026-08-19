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

  const start = useCallback(async () => {
    setState((s) => ({ ...s, error: null }));

    let displayStream: MediaStream;
    try {
      const constraints = { video: true, audio: true, preferCurrentTab: true } as DisplayMediaStreamOptions;
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

    const canvas = document.createElement('canvas');
    canvas.width = sourceVideo.videoWidth || 1280;
    canvas.height = sourceVideo.videoHeight || 720;
    const ctx = canvas.getContext('2d');

    const draw = () => {
      if (ctx && sourceVideoRef.current) {
        ctx.drawImage(sourceVideoRef.current, 0, 0, canvas.width, canvas.height);
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
