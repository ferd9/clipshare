import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { useClipTrimmer } from './useClipTrimmer';

function formatSeconds(ms: number): string {
  return (ms / 1000).toFixed(1) + 's';
}

interface ClipTrimmerProps {
  blob: Blob;
  previewUrl: string;
  maxDurationMs: number;
  onConfirm: (trimStartMs: number, trimEndMs: number) => void;
  onDiscard: () => void;
}

/**
 * Recorte post-grabación con filmstrip (docs/SPEC.md sección 9) — visualmente similar a un
 * editor tipo Coub, pero operando siempre sobre la GRABACIÓN propia (el blob que ya capturó
 * useCanvasRecorder), nunca sobre el video fuente: nada acá descarga ni reprocesa el original.
 * El recorte final lo aplica ffmpeg server-side (ver FfmpegProcessor/ClipProcessingWorker)
 * sobre ese mismo archivo ya subido.
 */
export function ClipTrimmer({ blob, previewUrl, maxDurationMs, onConfirm, onDiscard }: ClipTrimmerProps) {
  const trimmer = useClipTrimmer(blob, maxDurationMs);
  const previewRef = useRef<HTMLVideoElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState<'start' | 'end' | null>(null);
  const [muted, setMuted] = useState(true);

  // Loop de reproducción acotado a [trimStart, trimEnd] — mismo criterio visual que la
  // referencia (Coub), reproduciendo siempre la grabación propia.
  useEffect(() => {
    const video = previewRef.current;
    if (!video || !trimmer.ready) return undefined;

    video.currentTime = trimmer.trimStartMs / 1000;
    let rafId: number;
    const tick = () => {
      if (video.currentTime * 1000 >= trimmer.trimEndMs) {
        video.currentTime = trimmer.trimStartMs / 1000;
      }
      rafId = requestAnimationFrame(tick);
    };
    video.play().catch(() => {
      // Autoplay bloqueado (política del navegador) — no es crítico, el usuario puede tocar
      // play manualmente; el recorte en sí no depende de que la vista previa esté sonando.
    });
    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [trimmer.ready, trimmer.trimStartMs, trimmer.trimEndMs]);

  function msFromPointer(event: ReactPointerEvent): number {
    const track = trackRef.current;
    if (!track || !trimmer.durationMs) return 0;
    const rect = track.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    return ratio * trimmer.durationMs;
  }

  function handlePointerMove(event: ReactPointerEvent) {
    if (!dragging) return;
    const ms = msFromPointer(event);
    if (dragging === 'start') trimmer.setTrimStartMs(ms);
    else trimmer.setTrimEndMs(ms);
  }

  function startDrag(handle: 'start' | 'end') {
    return (event: ReactPointerEvent<HTMLDivElement>) => {
      event.currentTarget.setPointerCapture(event.pointerId);
      setDragging(handle);
    };
  }

  if (!trimmer.ready) {
    return <p className="clips-loading">Preparando vista previa…</p>;
  }

  const startPct = trimmer.durationMs ? (trimmer.trimStartMs / trimmer.durationMs) * 100 : 0;
  const endPct = trimmer.durationMs ? (trimmer.trimEndMs / trimmer.durationMs) * 100 : 100;

  return (
    <div className="clip-trimmer">
      <div className="clip-trimmer-preview-wrap">
        <video ref={previewRef} src={previewUrl} muted={muted} playsInline className="clip-trimmer-preview" />
        <button
          type="button"
          className="clip-trimmer-mute"
          onClick={() => setMuted((m) => !m)}
          aria-label={muted ? 'Activar sonido' : 'Silenciar'}
        >
          {muted ? '🔇' : '🔊'}
        </button>
      </div>

      <p className="clip-trimmer-duration">
        {formatSeconds(trimmer.trimEndMs - trimmer.trimStartMs)} seleccionados (máx. {formatSeconds(maxDurationMs)})
      </p>

      <div
        className="clip-trimmer-track"
        ref={trackRef}
        onPointerMove={handlePointerMove}
        onPointerUp={() => setDragging(null)}
      >
        {trimmer.filmstrip.length > 0 && (
          <div className="clip-trimmer-filmstrip">
            {trimmer.filmstrip.map((src, i) => (
              <img key={i} src={src} alt="" draggable={false} />
            ))}
          </div>
        )}
        <div className="clip-trimmer-dim" style={{ left: 0, width: `${startPct}%` }} />
        <div className="clip-trimmer-dim" style={{ left: `${endPct}%`, width: `${100 - endPct}%` }} />
        <div className="clip-trimmer-selection" style={{ left: `${startPct}%`, width: `${endPct - startPct}%` }} />
        <div className="clip-trimmer-handle" style={{ left: `${startPct}%` }} onPointerDown={startDrag('start')} />
        <div className="clip-trimmer-handle" style={{ left: `${endPct}%` }} onPointerDown={startDrag('end')} />
      </div>

      <div className="clip-trimmer-actions">
        <button type="button" onClick={onDiscard}>
          Grabar de nuevo
        </button>
        <button type="button" onClick={() => onConfirm(trimmer.trimStartMs, trimmer.trimEndMs)}>
          Confirmar recorte
        </button>
      </div>
    </div>
  );
}
