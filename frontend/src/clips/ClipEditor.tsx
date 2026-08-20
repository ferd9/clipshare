import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import ReactPlayer from 'react-player';
import { extractErrorMessage } from '../auth/AuthContext';
import { ClipTrimmer } from './ClipTrimmer';
import { uploadFromCapture } from './clipsApi';
import { useCanvasRecorder } from './useCanvasRecorder';
import type { ClipPlatform } from './types';
import './clips.css';

interface ClipEditorProps {
  sourceUrl: string;
  sourcePlatform: Exclude<ClipPlatform, 'NONE'>;
  sourceExternalId: string | null;
  onCancel: () => void;
}

export function ClipEditor({ sourceUrl, sourcePlatform, sourceExternalId, onCancel }: ClipEditorProps) {
  const navigate = useNavigate();
  const playerRef = useRef<HTMLVideoElement>(null);
  const playerContainerRef = useRef<HTMLDivElement>(null);
  const recorder = useCanvasRecorder();

  const [clipStartMs, setClipStartMs] = useState<number | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  function currentPlayerMs(): number {
    const seconds = playerRef.current?.currentTime;
    return Number.isFinite(seconds) ? Math.round((seconds as number) * 1000) : 0;
  }

  async function handleStartRecording() {
    setClipStartMs(currentPlayerMs());
    // Se le pasa el contenedor del reproductor para recortar la grabación a esa región —
    // sin esto se graba la pestaña entera (header, contador, botón "Detener" incluidos).
    await recorder.start(playerContainerRef.current);
  }

  async function handleUpload(rawTrimStartMs: number, rawTrimEndMs: number) {
    if (!recorder.blob) return;
    // Los handles se arrastran con matemática de posición (ratio * duración), así que llegan
    // como decimales — el backend espera enteros (@RequestParam int), y un "1234.5" ahí
    // rompe con 400 "Valor inválido para 'sourceClipStartMs'".
    const trimStartMs = Math.round(rawTrimStartMs);
    const trimEndMs = Math.round(rawTrimEndMs);

    setUploading(true);
    setUploadError(null);
    try {
      await uploadFromCapture(recorder.blob, {
        sourceUrl,
        sourcePlatform,
        sourceExternalId: sourceExternalId ?? undefined,
        // Metadata informativa (a qué tramo del video original corresponde) ajustada al
        // recorte elegido, no a la grabación completa.
        sourceClipStartMs: (clipStartMs ?? 0) + trimStartMs,
        sourceClipEndMs: (clipStartMs ?? 0) + trimEndMs,
        trimStartMs,
        trimEndMs,
      });
      setDone(true);
    } catch (err) {
      setUploadError(extractErrorMessage(err, 'No se pudo subir el clip'));
    } finally {
      setUploading(false);
    }
  }

  if (done) {
    return (
      <div className="upload-page">
        <p className="upload-success">¡Listo! Tu clip se está procesando — en unos segundos aparece en el feed.</p>
        <button type="button" onClick={() => navigate('/')}>
          Ir al feed
        </button>
      </div>
    );
  }

  return (
    <div className="editor-page">
      <button type="button" className="editor-back" onClick={onCancel}>
        ← Cambiar link
      </button>

      {!recorder.blob && (
        <div className="editor-player" ref={playerContainerRef}>
          <ReactPlayer ref={playerRef} src={sourceUrl} controls playing width="100%" height="100%" />
        </div>
      )}

      <div className="editor-controls">
        {!recorder.isRecording && !recorder.blob && (
          <>
            <p className="import-hint">
              Ubicá el video donde querés que arranque el clip y presioná grabar — se
              comparte tu pestaña (permiso del navegador) y se graban hasta 20s.
            </p>
            <button type="button" onClick={() => void handleStartRecording()}>
              ● Empezar a grabar
            </button>
          </>
        )}

        {recorder.isRecording && (
          <>
            <p className="editor-recording">
              ● Grabando… {(recorder.elapsedMs / 1000).toFixed(1)}s / {recorder.maxDurationMs / 1000}s
            </p>
            <button type="button" onClick={recorder.stop}>
              Detener
            </button>
          </>
        )}

        {recorder.blob && !done && !uploading && (
          <ClipTrimmer
            blob={recorder.blob}
            previewUrl={recorder.previewUrl ?? ''}
            recordedDurationMs={recorder.elapsedMs}
            maxDurationMs={recorder.maxDurationMs}
            onDiscard={recorder.reset}
            onConfirm={(trimStartMs, trimEndMs) => void handleUpload(trimStartMs, trimEndMs)}
          />
        )}

        {uploading && <p className="clips-loading">Subiendo…</p>}
        {uploadError && (
          <p className="clips-error" role="alert">
            {uploadError}
          </p>
        )}

        {recorder.error && (
          <p className="clips-error" role="alert">
            {recorder.error}
          </p>
        )}
      </div>
    </div>
  );
}
