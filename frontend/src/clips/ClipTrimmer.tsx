import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { useMediaTrimmer } from './useMediaTrimmer';
import { useWaveform } from './useWaveform';

const TAP_THRESHOLD_PX = 4;

function formatSeconds(ms: number): string {
  return (ms / 1000).toFixed(1) + 's';
}

interface ClipTrimmerProps {
  videoUrl: string;
  maxDurationMs: number;
  onChange: (trimStartMs: number, trimEndMs: number) => void;
  /** true mientras no haya ninguna pista de reemplazo elegida — controla si se muestra el "+"
   * sobre la onda del audio original (ver AudioPicker: una vez elegida una pista, ese picker
   * pasa a mostrar su propia UI en otro lado, y este botón no hace falta hasta que se quite). */
  showAddAudioButton: boolean;
  onAddAudioClick: () => void;
  /** Título del video ORIGINAL (yt-dlp, solo EXTERNAL_CAPTURE) — se muestra arriba del video.
   * El nombre del audio de reemplazo NO va acá — ya se muestra debajo de su propia onda
   * (ver AudioTrimmer), repetirlo arriba del video era redundante. */
  sourceTitle?: string | null;
  /** Link original (solo EXTERNAL_CAPTURE) — si está presente, sourceTitle se muestra como link
   * clickeable hacia ahí (ver Clip.sourceUrl). */
  sourceUrl?: string | null;
  /** Nivel del control deslizante de volumen (0-1) — ClipEditPage lo necesita para mandarlo en
   * POST /finalize (ver FfmpegProcessor.finalizeClip, filtro "volume=" antes de mezclar). Antes
   * era puramente local (solo afectaba la vista previa), sin ningún efecto en el clip final. */
  onVolumeChange: (volume: number) => void;
  /** Se incrementa cada vez que el usuario mueve la POSICIÓN del fragmento de audio importado
   * (ver AudioTrimmer.onPositionChange) — reinicia la vista previa del video a su propio
   * trimStart, para que al escuchar el resultado video y audio arranquen sincronizados. */
  restartSignal?: number;
}

/**
 * Recorte con filmstrip (docs/SPEC.md sección 9) sobre el archivo "editable" ya normalizado
 * por el worker (fase STAGE) — mismo look tipo Coub que la versión anterior, pero ahora
 * operando sobre un mp4 real servido por GET /api/clips/{id}/editable en vez de un blob de
 * MediaRecorder. El recorte final lo aplica ffmpeg server-side recién en /finalize.
 *
 * Interacciones sobre el track principal (siempre relativas a la VISTA actual — ver
 * useMediaTrimmer.viewStartMs/viewEndMs):
 * - Tocar/arrastrar el fondo arranca una selección nueva desde ese punto, extensible para
 *   cualquier lado (setRangeFromAnchor) — un tap simple (sin arrastrar) centra una ventana del
 *   largo máximo ahí, para no dejar una selección de 1s por accidente.
 * - Arrastrar la selección ya elegida la mueve entera, preservando su duración.
 * - Arrastrar un handle de punta achica/agranda ese extremo.
 *
 * El zoom (lupa +/− al costado del track) acerca esa vista para elegir con precisión frame a
 * frame; una vez acercado, la mini-barra de resumen de abajo (todo el video, con un recuadro
 * marcando la parte visible) permite recorrer el resto del video sin perder el zoom.
 */
export function ClipTrimmer({
  videoUrl,
  maxDurationMs,
  onChange,
  showAddAudioButton,
  onAddAudioClick,
  sourceTitle,
  sourceUrl,
  onVolumeChange,
  restartSignal,
}: ClipTrimmerProps) {
  const trimmer = useMediaTrimmer(videoUrl, maxDurationMs);
  // Pista de audio ORIGINAL del clip, como referencia visual (misma forma de onda que se usa
  // para el audio de reemplazo, ver AudioPicker/AudioTrimmer) — a diferencia del filmstrip de
  // arriba, siempre muestra la duración COMPLETA (no la vista con zoom/pan), con la selección
  // actual marcada encima; no es interactiva por su cuenta, solo referencia de dónde hay
  // sonido en el video al elegir el recorte.
  const originalAudioPeaks = useWaveform(videoUrl);
  const previewRef = useRef<HTMLVideoElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const minimapRef = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState<'start' | 'end' | 'window' | 'select' | null>(null);
  const [minimapDragging, setMinimapDragging] = useState(false);
  const [muted, setMuted] = useState(true);
  const [playing, setPlaying] = useState(true);
  const [volume, setVolume] = useState(1);

  // Arrastre de ventana completa: guarda el delta desde donde arrancó, no la posición
  // absoluta — si no, la ventana "saltaría" para que su borde izquierdo quede bajo el cursor
  // en vez de mantener el punto donde el usuario la agarró. Mismo criterio para el recuadro
  // de la mini-barra (viewportDragOriginRef).
  const windowDragOriginRef = useRef<{ clientX: number; trimStartMs: number } | null>(null);
  const viewportDragOriginRef = useRef<{ clientX: number; viewStartMs: number } | null>(null);
  // Selección nueva desde cero: el ancla queda fija (en ms) en el punto donde se apretó;
  // selectStartClientRef guarda las coordenadas de pantalla de ESE momento para poder medir
  // si el puntero se movió más que TAP_THRESHOLD_PX y así distinguir un tap real de un drag.
  const selectAnchorRef = useRef<number | null>(null);
  const selectStartClientRef = useRef<{ x: number; y: number } | null>(null);
  const selectMovedRef = useRef(false);

  // Se avisa al padre cada vez que cambia el rango — ClipEditPage lo necesita para el
  // POST /finalize final, y para el toggle de silenciar/reemplazar audio.
  useEffect(() => {
    if (trimmer.ready) onChange(trimmer.trimStartMs, trimmer.trimEndMs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [trimmer.ready, trimmer.trimStartMs, trimmer.trimEndMs]);

  // Loop de reproducción acotado a [trimStart, trimEnd] — mismo criterio visual que la
  // referencia (Coub). Respeta el toggle de play/pausa (playing): al pausar, no se reinicia
  // el currentTime al llegar a trimEnd — el usuario puede haber pausado justo ahí a propósito
  // para mirar un frame puntual, reiniciarlo de golpe sería confuso.
  useEffect(() => {
    const video = previewRef.current;
    if (!video || !trimmer.ready) return undefined;

    video.currentTime = trimmer.trimStartMs / 1000;
    let rafId: number;
    const tick = () => {
      if (playing && video.currentTime * 1000 >= trimmer.trimEndMs) {
        video.currentTime = trimmer.trimStartMs / 1000;
      }
      rafId = requestAnimationFrame(tick);
    };
    if (playing) {
      video.play().catch(() => {
        // Autoplay bloqueado (política del navegador) — no es crítico, el usuario puede tocar
        // play manualmente; el recorte en sí no depende de que la vista previa esté sonando.
      });
    } else {
      video.pause();
    }
    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [trimmer.ready, trimmer.trimStartMs, trimmer.trimEndMs, playing]);

  // Volumen aparte del mute: silenciar (muted) sigue siendo el apagado total de golpe: el
  // control de volumen es para ajustar el nivel sin llegar a eso.
  useEffect(() => {
    if (previewRef.current) previewRef.current.volume = volume;
  }, [volume]);

  // Reinicia la vista previa del video a su propio trimStart cada vez que restartSignal cambia
  // (el usuario movió la POSICIÓN del fragmento de audio importado, ver AudioTrimmer) — para
  // que al escuchar el resultado, video y audio arranquen sincronizados desde el principio en
  // vez de quedar desfasados a mitad del loop de cada uno.
  useEffect(() => {
    if (restartSignal === undefined || !trimmer.ready || !previewRef.current) return;
    previewRef.current.currentTime = trimmer.trimStartMs / 1000;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [restartSignal]);

  // Se avisa al padre igual que con el rango de recorte (arriba) — lo necesita para mandarlo
  // en POST /finalize cuando corresponda mezclar audio original + reemplazo.
  useEffect(() => {
    onVolumeChange(volume);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [volume]);

  function msFromPointer(event: ReactPointerEvent): number {
    const track = trackRef.current;
    const viewSpan = trimmer.viewEndMs - trimmer.viewStartMs;
    if (!track || !viewSpan) return trimmer.viewStartMs;
    const rect = track.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    return trimmer.viewStartMs + ratio * viewSpan;
  }

  function msFromMinimapPointer(event: ReactPointerEvent): number {
    const minimap = minimapRef.current;
    if (!minimap || !trimmer.durationMs) return 0;
    const rect = minimap.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    return ratio * trimmer.durationMs;
  }

  function handlePointerMove(event: ReactPointerEvent) {
    if (!dragging) return;

    if (dragging === 'select') {
      const origin = selectStartClientRef.current;
      if (origin) {
        const distance = Math.hypot(event.clientX - origin.x, event.clientY - origin.y);
        if (distance > TAP_THRESHOLD_PX) selectMovedRef.current = true;
      }
      if (selectAnchorRef.current !== null) {
        trimmer.setRangeFromAnchor(selectAnchorRef.current, msFromPointer(event));
      }
      return;
    }

    if (dragging === 'window') {
      const track = trackRef.current;
      const origin = windowDragOriginRef.current;
      const viewSpan = trimmer.viewEndMs - trimmer.viewStartMs;
      if (!track || !origin || !viewSpan) return;
      const rect = track.getBoundingClientRect();
      const deltaMs = ((event.clientX - origin.clientX) / rect.width) * viewSpan;
      trimmer.setWindowStartMs(origin.trimStartMs + deltaMs);
      return;
    }

    const ms = msFromPointer(event);
    if (dragging === 'start') trimmer.setTrimStartMs(ms);
    else trimmer.setTrimEndMs(ms);
  }

  function startDrag(handle: 'start' | 'end') {
    return (event: ReactPointerEvent<HTMLDivElement>) => {
      event.stopPropagation(); // no disparar también la selección-desde-cero del track
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

  // Tocar/arrastrar cualquier parte vacía del filmstrip arranca una selección nueva desde ese
  // punto — imprescindible en un video mucho más largo que los 20s seleccionables, donde la
  // ventana ocupa una porción mínima del track. Los handles/la selección llaman a
  // stopPropagation, así que esto solo dispara al tocar el fondo.
  function startSelect(event: ReactPointerEvent<HTMLDivElement>) {
    event.currentTarget.setPointerCapture(event.pointerId);
    selectAnchorRef.current = msFromPointer(event);
    selectStartClientRef.current = { x: event.clientX, y: event.clientY };
    selectMovedRef.current = false;
    setDragging('select');
  }

  function endDrag() {
    // Un tap real (sin arrastre perceptible) sobre el fondo centra una ventana del largo
    // máximo ahí, en vez de dejar la selección de 1s mínima que dejaría un drag de 0px — sería
    // muy fácil terminar con un recorte casi vacío por accidente si no.
    if (dragging === 'select' && !selectMovedRef.current && selectAnchorRef.current !== null) {
      trimmer.setWindowStartMs(selectAnchorRef.current - maxDurationMs / 2);
    }
    selectAnchorRef.current = null;
    selectStartClientRef.current = null;
    setDragging(null);
  }

  // Mini-barra de resumen (todo el video): arrastrar el recuadro de la vista la desplaza sin
  // tocar la selección, para poder recorrer el video entero aun con zoom acercado. Tocar el
  // fondo (fuera del recuadro) salta ahí directo, centrando la vista en ese punto.
  function startViewportDrag(event: ReactPointerEvent<HTMLDivElement>) {
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    viewportDragOriginRef.current = { clientX: event.clientX, viewStartMs: trimmer.viewStartMs };
    setMinimapDragging(true);
  }

  function startMinimapJump(event: ReactPointerEvent<HTMLDivElement>) {
    event.currentTarget.setPointerCapture(event.pointerId);
    const viewWidth = trimmer.viewEndMs - trimmer.viewStartMs;
    const target = msFromMinimapPointer(event) - viewWidth / 2;
    // Clampeado localmente (mismas cuentas que setViewStartMs) para poder usar el mismo valor
    // como origen del arrastre que sigue — leer trimmer.viewStartMs acá daría el valor VIEJO
    // (el estado de React todavía no se actualizó), corriendo el resto del drag por error.
    const clampedStart = Math.max(0, Math.min(target, trimmer.durationMs - viewWidth));
    trimmer.setViewStartMs(clampedStart);
    viewportDragOriginRef.current = { clientX: event.clientX, viewStartMs: clampedStart };
    setMinimapDragging(true);
  }

  function handleMinimapPointerMove(event: ReactPointerEvent) {
    if (!minimapDragging) return;
    const minimap = minimapRef.current;
    const origin = viewportDragOriginRef.current;
    if (!minimap || !origin || !trimmer.durationMs) return;
    const rect = minimap.getBoundingClientRect();
    const deltaMs = ((event.clientX - origin.clientX) / rect.width) * trimmer.durationMs;
    trimmer.setViewStartMs(origin.viewStartMs + deltaMs);
  }

  function endMinimapDrag() {
    viewportDragOriginRef.current = null;
    setMinimapDragging(false);
  }

  if (trimmer.error) {
    return (
      <p className="clips-error" role="alert">
        {trimmer.error}
      </p>
    );
  }

  if (!trimmer.ready) {
    return <p className="clips-loading">Preparando vista previa…</p>;
  }

  const viewSpan = trimmer.viewEndMs - trimmer.viewStartMs || 1;
  const toViewPct = (ms: number) => Math.min(100, Math.max(0, ((ms - trimmer.viewStartMs) / viewSpan) * 100));
  const startPct = toViewPct(trimmer.trimStartMs);
  const endPct = toViewPct(trimmer.trimEndMs);
  const selectionOutsideView = trimmer.trimStartMs < trimmer.viewStartMs || trimmer.trimEndMs > trimmer.viewEndMs;

  const toOverviewPct = (ms: number) => Math.min(100, Math.max(0, (ms / trimmer.durationMs) * 100));
  const overviewSelStartPct = toOverviewPct(trimmer.trimStartMs);
  const overviewSelEndPct = toOverviewPct(trimmer.trimEndMs);
  const overviewViewStartPct = toOverviewPct(trimmer.viewStartMs);
  const overviewViewEndPct = toOverviewPct(trimmer.viewEndMs);

  return (
    <div className="clip-trimmer">
      {sourceTitle && (
        <p className="clip-trimmer-audio-name">
          🎬{' '}
          {sourceUrl ? (
            <a href={sourceUrl} target="_blank" rel="noopener noreferrer" className="clip-trimmer-source-link">
              {sourceTitle}
            </a>
          ) : (
            sourceTitle
          )}
        </p>
      )}

      <div className="clip-trimmer-preview-wrap">
        <video ref={previewRef} src={videoUrl} muted={muted} playsInline className="clip-trimmer-preview" />
        {/* Columna, no fila — para que quede alineada con el play de la barra de selección y
         * el ícono de la fila de volumen, más abajo (todos a la misma altura horizontal). */}
        <div className="clip-trimmer-preview-controls">
          <button
            type="button"
            className="clip-trimmer-play"
            onClick={() => setPlaying((p) => !p)}
            aria-label={playing ? 'Pausar' : 'Reproducir'}
          >
            {playing ? '⏸' : '▶'}
          </button>
          <button
            type="button"
            className="clip-trimmer-mute"
            onClick={() => setMuted((m) => !m)}
            aria-label={muted ? 'Activar sonido' : 'Silenciar'}
          >
            {muted ? '🔇' : '🔊'}
          </button>
        </div>
      </div>

      <p className="clip-trimmer-duration">
        {formatSeconds(trimmer.trimEndMs - trimmer.trimStartMs)} seleccionados (máx. {formatSeconds(maxDurationMs)})
      </p>

      {selectionOutsideView && (
        <p className="clip-trimmer-hint">La selección quedó fuera de la vista — arrastrá el recuadro de abajo hasta ahí.</p>
      )}
      {!selectionOutsideView && (
        <p className="clip-trimmer-hint">
          Tocá o arrastrá el filmstrip para elegir el fragmento, arrastrá la selección para moverla, o los bordes para ajustarla.
        </p>
      )}

      {/* El track de video y la onda del audio original van agrupados con un gap chico propio
       * (.clip-trimmer-video-audio-group), no el gap general de .clip-trimmer — para que la
       * onda quede pegada justo debajo de la línea de selección del video, no separada como
       * el resto de los controles (hint, minimapa). */}
      <div className="clip-trimmer-video-audio-group">
        <div className="clip-trimmer-zoom-controls">
          <span className="clip-trimmer-zoom-icon" aria-hidden="true">🔍</span>
          <button type="button" onClick={trimmer.zoomIn} disabled={trimmer.zoom >= trimmer.maxZoom} aria-label="Acercar zoom">
            +
          </button>
          <button type="button" onClick={trimmer.zoomOut} disabled={trimmer.zoom <= 1} aria-label="Alejar zoom">
            −
          </button>
          <button
            type="button"
            className="clip-trimmer-zoom-fit"
            onClick={trimmer.zoomToSelection}
            aria-label="Ajustar el zoom al fragmento elegido"
            title="Ajustar el zoom al fragmento elegido"
          >
            ⌖
          </button>
        </div>

        <div className="clip-trimmer-fragment-row">
          <button
            type="button"
            className="clip-trimmer-fragment-play"
            onClick={() => setPlaying((p) => !p)}
            aria-label={playing ? 'Pausar' : 'Reproducir'}
            title={playing ? 'Pausar' : 'Reproducir'}
          >
            {playing ? '⏸' : '▶'}
          </button>

          <div
            className="clip-trimmer-track"
            ref={trackRef}
            onPointerDown={startSelect}
            onPointerMove={handlePointerMove}
            onPointerUp={endDrag}
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
            <div
              className="clip-trimmer-selection"
              style={{ left: `${startPct}%`, width: `${Math.max(endPct - startPct, 1)}%` }}
              onPointerDown={startWindowDrag}
            />
            <div className="clip-trimmer-handle" style={{ left: `${startPct}%` }} onPointerDown={startDrag('start')} />
            <div className="clip-trimmer-handle" style={{ left: `${endPct}%` }} onPointerDown={startDrag('end')} />
          </div>
        </div>

        {/* La mini-barra de resumen (todo el video, para recorrer sin perder el zoom) va acá,
         * pegada debajo de la línea de selección del video — no después de la onda de audio. */}
        {trimmer.zoom > 1 && (
          <div
            className="clip-trimmer-minimap"
            ref={minimapRef}
            onPointerDown={startMinimapJump}
            onPointerMove={handleMinimapPointerMove}
            onPointerUp={endMinimapDrag}
          >
            {trimmer.overviewFilmstrip.length > 0 && (
              <div className="clip-trimmer-filmstrip">
                {trimmer.overviewFilmstrip.map((src, i) => (
                  <img key={i} src={src} alt="" draggable={false} />
                ))}
              </div>
            )}
            <div
              className="clip-trimmer-minimap-selection"
              style={{ left: `${overviewSelStartPct}%`, width: `${Math.max(overviewSelEndPct - overviewSelStartPct, 0.5)}%` }}
            />
            <div
              className="clip-trimmer-minimap-viewport"
              style={{ left: `${overviewViewStartPct}%`, width: `${Math.max(overviewViewEndPct - overviewViewStartPct, 2)}%` }}
              onPointerDown={startViewportDrag}
            />
          </div>
        )}

        {originalAudioPeaks && (
          <div className="clip-trimmer-fragment-row">
            {/* Separador invisible del mismo ancho que el botón de play de la fila de arriba —
             * para que la onda arranque y termine exactamente donde arranca y termina el
             * track de video, no más ancha por no tener ese botón al costado. */}
            <div className="clip-trimmer-fragment-spacer" aria-hidden="true" />
            <div className="clip-trimmer-audio-track">
              <div className="audio-trimmer-waveform">
                {Array.from(originalAudioPeaks).map((v, i) => (
                  <div key={i} className="audio-trimmer-bar" style={{ height: `${Math.max(v * 100, 4)}%` }} />
                ))}
              </div>
              <div className="clip-trimmer-dim" style={{ left: 0, width: `${overviewSelStartPct}%` }} />
              <div className="clip-trimmer-dim" style={{ left: `${overviewSelEndPct}%`, width: `${100 - overviewSelEndPct}%` }} />
              <div
                className="clip-trimmer-selection"
                style={{ left: `${overviewSelStartPct}%`, width: `${Math.max(overviewSelEndPct - overviewSelStartPct, 1)}%` }}
              />
              {showAddAudioButton && (
                <button
                  type="button"
                  className="clip-trimmer-add-audio-button"
                  onClick={onAddAudioClick}
                  aria-label="Agregar o reemplazar audio"
                  title="Agregar o reemplazar audio"
                >
                  +
                </button>
              )}
            </div>
          </div>
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
          aria-label="Volumen del video"
        />
      </div>
    </div>
  );
}
