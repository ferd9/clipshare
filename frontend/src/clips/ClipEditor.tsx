import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import ReactPlayer from 'react-player';
import { extractErrorMessage } from '../auth/AuthContext';
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
    await recorder.start();
  }

  async function handleUpload() {
    if (!recorder.blob) return;
    setUploading(true);
    setUploadError(null);
    try {
      await uploadFromCapture(recorder.blob, {
        sourceUrl,
        sourcePlatform,
        sourceExternalId: sourceExternalId ?? undefined,
        sourceClipStartMs: clipStartMs ?? 0,
        sourceClipEndMs: (clipStartMs ?? 0) + recorder.elapsedMs,
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

      <div className="editor-player">
        <ReactPlayer ref={playerRef} src={sourceUrl} controls playing width="100%" height="100%" />
      </div>

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

        {recorder.blob && !done && (
          <>
            <video src={recorder.previewUrl ?? undefined} controls className="upload-preview" />
            {uploadError && (
              <p className="clips-error" role="alert">
                {uploadError}
              </p>
            )}
            <button type="button" onClick={() => void handleUpload()} disabled={uploading}>
              {uploading ? 'Subiendo…' : 'Subir clip'}
            </button>
            <button type="button" onClick={recorder.reset} disabled={uploading}>
              Grabar de nuevo
            </button>
          </>
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
