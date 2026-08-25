import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { useAudioTrimmer } from './useAudioTrimmer';
import { useWaveform } from './useWaveform';

const TAP_THRESHOLD_PX = 4;

function formatSeconds(ms: number): string {
  return (ms / 1000).toFixed(1) + 's';
}

interface AudioTrimmerProps {
  audioUrl: string;
  durationMs: number;
  maxDurationMs: number;
  onChange: (trimStartMs: number, trimEndMs: number) => void;
  /** Ícono de "quitar" al costado de la pista (ver AudioPicker) — opcional para no atar este
   * componente a la noción de "pista de reemplazo que se puede sacar" si se reusara en otro
   * contexto. */
  onRemove?: () => void;
  /** Nombre real del archivo (subida) o título obtenido por yt-dlp (link) — se muestra debajo
   * de la pista en vez del viejo reproductor nativo + link crudo como "nombre". */
  trackName?: string | null;
  /** Link original si esta pista se importó desde un enlace (ver AudioTrack.sourceUrl) — si
   * está presente, trackName se muestra como link clickeable hacia ahí. */
  sourceUrl?: string | null;
  /** Nivel del control deslizante de volumen (0-1) — ClipEditPage lo necesita para mandarlo en
   * POST /finalize (ver FfmpegProcessor.finalizeClip, filtro "volume=" antes de mezclar). Antes
   * era puramente local (solo afectaba la vista previa), sin ningún efecto en el clip final. */
  onVolumeChange: (volume: number) => void;
  /** Largo (ms) del fragmento de VIDEO elegido en ClipTrimmer — cuando cambia, ese mismo largo
   * se aplica acá (manteniendo fijo el punto de inicio ya elegido para el audio, solo se mueve
   * el final), para que el fragmento de audio siempre dure lo mismo que el de video. undefined
   * o ≤0 = todavía no hay un largo de video real que aplicar (no se toca nada). */
  targetLengthMs?: number;
  /** Se dispara al mover la POSICIÓN del fragmento ya seleccionado (arrastrar el bloque entero,
   * o el tap-para-centrar) — a diferencia de agrandar/achicarlo con los bordes. ClipEditPage lo
   * usa para reiniciar la vista previa del video, así ambos arrancan sincronizados al escuchar
   * el resultado. */
  onPositionChange?: () => void;
  /** Se incrementa cada vez que el usuario termina de cambiar la posición o la longitud del
   * fragmento de VIDEO (ver ClipTrimmer.onPositionChange) — reinicia la reproducción de este
   * audio a su propio trimStart, dirección inversa de onPositionChange de arriba. */
  restartSignal?: number;
  /** Se incrementa cuando el usuario usa "Sorprendeme" (ver ClipTrimmer.handleSurpriseMe) —
   * reacomoda el INICIO del fragmento de audio a un punto al azar, preservando la longitud
   * actual (ya sincronizada con la del video vía targetLengthMs, así que acá solo hace falta
   * mover la posición). */
  randomizeSignal?: number;
  /** Avisa a ClipEditPage qué posición de inicio se sorteó recién (en respuesta a
   * randomizeSignal) — se usa para completar la entrada del historial de "Sorprendeme" que ya
   * se había creado del lado del video (ver ClipEditPage.pendingSurpriseIdRef). */
  onRandomize?: (startMs: number) => void;
  /** Reaplica una posición de inicio EXACTA (de un sorteo anterior elegido del historial) sin
   * generar una nueva al azar — a diferencia de randomizeSignal, que sí sortea. */
  restoreStartMs?: number;
  restoreSignal?: number;
}

/**
 * Recorte del fragmento de audio de reemplazo (docs/SPEC.md sección 1) — mismas interacciones
 * que ClipTrimmer (tocar/arrastrar el fondo para elegir un fragmento en cualquier dirección,
 * arrastrar la selección para moverla, los bordes para ajustarla), pero sin filmstrip/zoom: un
 * audio no tiene frames que mostrar y no necesita precisión al milisegundo como un corte de
 * video — en cambio, muestra la forma de onda real (ver useWaveform) de fondo, generada en el
 * navegador, a modo de referencia visual tipo Audacity. Siempre se muestra (no solo cuando el
 * audio dura más que el máximo): si ya entra entero, la selección arranca cubriendo todo el
 * track, para que la pista de reemplazo se vea igual de consistente que la del audio original
 * (ver ClipTrimmer) — misma altura, mismo estilo de selección.
 */
export function AudioTrimmer({
  audioUrl,
  durationMs,
  maxDurationMs,
  onChange,
  onRemove,
  trackName,
  sourceUrl,
  onVolumeChange,
  targetLengthMs,
  onPositionChange,
  restartSignal,
  randomizeSignal,
  onRandomize,
  restoreStartMs,
  restoreSignal,
}: AudioTrimmerProps) {
  const trimmer = useAudioTrimmer(durationMs, maxDurationMs);
  const peaks = useWaveform(audioUrl);
  const previewRef = useRef<HTMLAudioElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState<'start' | 'end' | 'window' | 'select' | null>(null);
  const [playing, setPlaying] = useState(true);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);

  const windowDragOriginRef = useRef<{ clientX: number; trimStartMs: number } | null>(null);
  const selectAnchorRef = useRef<number | null>(null);
  const selectStartClientRef = useRef<{ x: number; y: number } | null>(null);
  const selectMovedRef = useRef(false);

  useEffect(() => {
    onChange(trimmer.trimStartMs, trimmer.trimEndMs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [trimmer.trimStartMs, trimmer.trimEndMs]);

  // El fragmento de audio siempre dura lo mismo que el de video (pedido explícito) — se
  // mantiene el inicio ya elegido para el audio, solo se ajusta el final. setTrimEndMs ya
  // clampea solo (a la duración real del audio y a maxDurationMs), así que si el video mide más
  // que lo que queda de audio desde ese inicio, el audio simplemente usa lo que tiene.
  useEffect(() => {
    if (!targetLengthMs || targetLengthMs <= 0) return;
    trimmer.setTrimEndMs(trimmer.trimStartMs + targetLengthMs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetLengthMs]);

  // Loop de reproducción acotado a [trimStart, trimEnd] — mismo criterio que la vista previa
  // del video en ClipTrimmer. Respeta el toggle de play/pausa (playing), ver comentario
  // equivalente ahí sobre por qué no se reinicia el currentTime mientras está pausado.
  useEffect(() => {
    const audio = previewRef.current;
    if (!audio) return undefined;
    audio.currentTime = trimmer.trimStartMs / 1000;
    let rafId: number;
    const tick = () => {
      if (playing && audio.currentTime * 1000 >= trimmer.trimEndMs) {
        audio.currentTime = trimmer.trimStartMs / 1000;
      }
      rafId = requestAnimationFrame(tick);
    };
    if (playing) {
      audio.play().catch(() => {
        // Autoplay bloqueado — no crítico, el recorte no depende de que esté sonando.
      });
    } else {
      audio.pause();
    }
    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [trimmer.trimStartMs, trimmer.trimEndMs, playing]);

  useEffect(() => {
    if (previewRef.current) previewRef.current.volume = volume;
  }, [volume]);

  // Reinicia la reproducción a su propio trimStart cada vez que restartSignal cambia (el
  // usuario cambió la posición o la longitud del fragmento de VIDEO, ver ClipTrimmer) — para
  // que al escuchar el resultado, video y audio arranquen sincronizados en vez de quedar
  // desfasados a mitad del loop de cada uno.
  useEffect(() => {
    if (restartSignal === undefined || !previewRef.current) return;
    previewRef.current.currentTime = trimmer.trimStartMs / 1000;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [restartSignal]);

  // Nueva posición al azar para el fragmento de audio cuando el usuario usa "Sorprendeme" (ver
  // ClipTrimmer.handleSurpriseMe) — mantiene la longitud actual (setWindowStartMs no la toca) y
  // deja que el propio clamp interno de useAudioTrimmer se encargue de no pasarse del final.
  useEffect(() => {
    if (randomizeSignal === undefined) return;
    const length = trimmer.trimEndMs - trimmer.trimStartMs;
    const newStart = Math.random() * Math.max(durationMs - length, 0);
    trimmer.setWindowStartMs(newStart);
    onRandomize?.(newStart);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [randomizeSignal]);

  // Reaplica una posición exacta elegida del historial de "Sorprendeme" (ver ClipEditPage) —
  // a diferencia del efecto de arriba, acá NO se sortea nada nuevo.
  useEffect(() => {
    if (restoreSignal === undefined || restoreStartMs === undefined) return;
    trimmer.setWindowStartMs(restoreStartMs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [restoreSignal]);

  // Se avisa al padre igual que con el rango de recorte (arriba) — lo necesita para mandarlo
  // en POST /finalize cuando corresponda mezclar audio original + reemplazo.
  useEffect(() => {
    onVolumeChange(volume);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [volume]);

  function msFromPointer(event: ReactPointerEvent): number {
    const track = trackRef.current;
    if (!track || !durationMs) return 0;
    const rect = track.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    return ratio * durationMs;
  }

  function handlePointerMove(event: ReactPointerEvent) {
    if (!dragging) return;

    if (dragging === 'select') {
      const origin = selectStartClientRef.current;
      if (origin) {
        const distance = Math.hypot(event.clientX - origin.x, event.clientY - origin.y);
        if (distance > TAP_THRESHOLD_PX) selectMovedRef.current = true;
      }
      if (selectAnchorRef.current !== null) trimmer.setRangeFromAnchor(selectAnchorRef.current, msFromPointer(event));
      return;
    }

    if (dragging === 'window') {
      const track = trackRef.current;
      const origin = windowDragOriginRef.current;
      if (!track || !origin || !durationMs) return;
      const rect = track.getBoundingClientRect();
      const deltaMs = ((event.clientX - origin.clientX) / rect.width) * durationMs;
      trimmer.setWindowStartMs(origin.trimStartMs + deltaMs);
      return;
    }

    const ms = msFromPointer(event);
    if (dragging === 'start') trimmer.setTrimStartMs(ms);
    else trimmer.setTrimEndMs(ms);
  }

  function startDrag(handle: 'start' | 'end') {
    return (event: ReactPointerEvent<HTMLDivElement>) => {
      event.stopPropagation();
      event.currentTarget.setPointerCapture(event.pointerId);
      setDragging(handle);
    };
  }

  function startWindowDrag(event: ReactPointerEvent<HTMLDivElement>) {
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    windowDragOriginRef.current = { clientX: event.clientX, trimStartMs: trimmer.trimStartMs };
    setDragging('window');
  }

  function startSelect(event: ReactPointerEvent<HTMLDivElement>) {
    event.currentTarget.setPointerCapture(event.pointerId);
    selectAnchorRef.current = msFromPointer(event);
    selectStartClientRef.current = { x: event.clientX, y: event.clientY };
    selectMovedRef.current = false;
    setDragging('select');
  }

  function endDrag() {
    const isTapCenter = dragging === 'select' && !selectMovedRef.current && selectAnchorRef.current !== null;
    if (isTapCenter) {
      trimmer.setWindowStartMs(selectAnchorRef.current! - maxDurationMs / 2);
    }
    // Solo cuenta como "cambio de posición" un arrastre que mueve el bloque entero (o el tap
    // que lo centra de golpe) — preservan la duración del fragmento, a diferencia de agrandar/
    // achicarlo con los bordes ('start'/'end') o de una selección nueva de punta a punta
    // ('select' con arrastre real, que define un largo distinto, no solo una posición).
    if (dragging === 'window' || isTapCenter) {
      onPositionChange?.();
    }
    selectAnchorRef.current = null;
    selectStartClientRef.current = null;
    setDragging(null);
  }

  const startPct = durationMs ? (trimmer.trimStartMs / durationMs) * 100 : 0;
  const endPct = durationMs ? (trimmer.trimEndMs / durationMs) * 100 : 100;

  return (
    <div className="audio-trimmer">
      {/* eslint-disable-next-line jsx-a11y/media-has-caption -- vista previa de edición, sin controles propios */}
      <audio ref={previewRef} src={audioUrl} muted={muted} />
      <p className="clip-trimmer-duration">
        {formatSeconds(trimmer.trimEndMs - trimmer.trimStartMs)} seleccionados (máx. {formatSeconds(maxDurationMs)})
      </p>
      <div className="audio-trimmer-track-row">
        <button
          type="button"
          className="audio-trimmer-play-button"
          onClick={() => setPlaying((p) => !p)}
          aria-label={playing ? 'Pausar' : 'Reproducir'}
          title={playing ? 'Pausar' : 'Reproducir'}
        >
          {playing ? '⏸' : '▶'}
        </button>

        <div
          className="audio-trimmer-track"
          ref={trackRef}
          onPointerDown={startSelect}
          onPointerMove={handlePointerMove}
          onPointerUp={endDrag}
        >
          {peaks && (
            <div className="audio-trimmer-waveform">
              {Array.from(peaks).map((v, i) => (
                <div key={i} className="audio-trimmer-bar" style={{ height: `${Math.max(v * 100, 4)}%` }} />
              ))}
            </div>
          )}
          <div className="clip-trimmer-dim" style={{ left: 0, width: `${startPct}%` }} />
          <div className="clip-trimmer-dim" style={{ left: `${endPct}%`, width: `${100 - endPct}%` }} />
          <div
            className="clip-trimmer-selection"
            style={{ left: `${startPct}%`, width: `${Math.max(endPct - startPct, 1)}%` }}
            onPointerDown={startWindowDrag}
          />
          <div className="clip-trimmer-handle" style={{ left: `${startPct}%` }} onPointerDown={startDrag('start')} />
          <div className="clip-trimmer-handle" style={{ left: `${endPct}%` }} onPointerDown={startDrag('end')} />
        </div>

        {onRemove && (
          <button type="button" className="audio-trimmer-remove-button" onClick={onRemove} aria-label="Quitar audio" title="Quitar audio">
            🗑
          </button>
        )}
      </div>

      <div className="media-volume-row">
        <button
          type="button"
          className="media-volume-icon"
          onClick={() => setMuted((m) => !m)}
          aria-label={muted ? 'Activar sonido' : 'Silenciar'}
        >
          {muted ? '🔇' : '🔊'}
        </button>
        <input
          type="range"
          min={0}
          max={1}
          step={0.01}
          value={volume}
          onChange={(event) => setVolume(Number(event.target.value))}
          aria-label="Volumen del audio"
        />
      </div>

      {trackName && (
        <p className="audio-trimmer-name">
          {sourceUrl ? (
            <a href={sourceUrl} target="_blank" rel="noopener noreferrer" className="clip-trimmer-source-link">
              {trackName}
            </a>
          ) : (
            trackName
          )}
        </p>
      )}
    </div>
  );
}
