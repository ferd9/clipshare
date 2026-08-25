import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { extractErrorMessage } from '../auth/AuthContext';
import { AudioPicker } from './AudioPicker';
import { ClipTrimmer } from './ClipTrimmer';
import { finalizeClip, getClip, getEditableBlobUrl } from './clipsApi';
import type { AudioTrack, ClipDetail, SurpriseHistoryEntry } from './types';
import './clips.css';

const MAX_CLIP_DURATION_MS = 40_000;
const POLL_INTERVAL_MS = 1500;

/**
 * Reemplaza al viejo ClipEditor.tsx (grabación de pantalla, retirado por calidad — ver
 * docs/SPEC.md). Común a OWN_UPLOAD y EXTERNAL_CAPTURE: ambos aterrizan acá después de crear
 * el clip (POST /upload o /import), mientras el worker todavía está en la fase STAGE
 * (descarga si corresponde + normalización) — se hace polling de GET /api/clips/{id} hasta
 * que pasa a AWAITING_EDIT, recién ahí se puede pedir el archivo editable y mostrar el
 * recorte + silenciar/reemplazar audio.
 */
export function ClipEditPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [clip, setClip] = useState<ClipDetail | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [videoError, setVideoError] = useState<string | null>(null);

  const [title, setTitle] = useState('');
  const [trimStartMs, setTrimStartMs] = useState(0);
  const [trimEndMs, setTrimEndMs] = useState(0);
  const [muteOriginalAudio, setMuteOriginalAudio] = useState(false);
  const [replacementAudio, setReplacementAudio] = useState<AudioTrack | null>(null);
  const [replacementAudioStartMs, setReplacementAudioStartMs] = useState(0);
  const [replacementAudioEndMs, setReplacementAudioEndMs] = useState(0);
  const [audioModalOpen, setAudioModalOpen] = useState(false);
  // Nivel elegido en cada control deslizante de volumen (ClipTrimmer/AudioTrimmer) — solo
  // importa de verdad al MEZCLAR audio original + reemplazo, ver FfmpegProcessor.finalizeClip.
  const [originalAudioVolume, setOriginalAudioVolume] = useState(1);
  const [replacementAudioVolume, setReplacementAudioVolume] = useState(1);
  // Se incrementa cada vez que el usuario mueve la POSICIÓN del fragmento de audio importado —
  // ClipTrimmer lo escucha para reiniciar la vista previa del video a su propio trimStart (ver
  // AudioTrimmer.onPositionChange / ClipTrimmer.restartSignal).
  const [videoRestartSignal, setVideoRestartSignal] = useState(0);
  // Dirección inversa de la de arriba: se incrementa cada vez que el usuario cambia la
  // posición o la longitud del fragmento de VIDEO — AudioTrimmer lo escucha para reiniciar su
  // propia reproducción (ver ClipTrimmer.onPositionChange / AudioTrimmer.restartSignal).
  const [audioRestartSignal, setAudioRestartSignal] = useState(0);
  // Se incrementa cada vez que el usuario usa "Sorprendeme" en el video — AudioTrimmer lo
  // escucha para reacomodar el inicio del audio importado a un punto al azar (ver
  // ClipTrimmer.onSurpriseMe / AudioTrimmer.randomizeSignal).
  const [audioRandomizeSignal, setAudioRandomizeSignal] = useState(0);
  // Historial de sorteos de "Sorprendeme" (ver ClipTrimmer.surpriseHistory) — cada tiro agrega
  // una entrada acá, para poder volver a uno anterior si gustó más que el actual. Cuando hay
  // audio importado, el rango de video y el inicio de audio se sortean por separado (cada uno
  // en su propio componente) pero para UN mismo click — pendingSurpriseIdRef guarda a qué
  // entrada todavía le falta el dato de audio, que llega un instante después vía onRandomize.
  const [surpriseHistory, setSurpriseHistory] = useState<SurpriseHistoryEntry[]>([]);
  const pendingSurpriseIdRef = useRef<number | null>(null);
  const nextSurpriseIdRef = useRef(1);
  // "Restaurar": reaplica el rango exacto de una entrada del historial elegida por el usuario,
  // sin sortear nada nuevo (ver ClipTrimmer.restoreRange/restoreSignal y
  // AudioTrimmer.restoreStartMs/restoreSignal).
  const [restoreVideoRange, setRestoreVideoRange] = useState<{ startMs: number; endMs: number } | null>(null);
  const [restoreVideoSignal, setRestoreVideoSignal] = useState(0);
  const [restoreAudioStartMs, setRestoreAudioStartMs] = useState<number | undefined>(undefined);
  const [restoreAudioSignal, setRestoreAudioSignal] = useState(0);

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const videoUrlRef = useRef<string | null>(null);

  // Polling: se detiene solo cuando el clip llega a AWAITING_EDIT o FAILED — QUEUED/PROCESSING
  // significa que el worker todavía está descargando/normalizando (puede tardar bastante para
  // un video de hasta 10 minutos por link).
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;

    async function poll() {
      try {
        const data = await getClip(id!);
        if (cancelled) return;
        setClip(data);
        if (data.processingStatus === 'QUEUED' || data.processingStatus === 'PROCESSING') {
          timer = setTimeout(poll, POLL_INTERVAL_MS);
        }
      } catch (err) {
        if (!cancelled) setPollError(extractErrorMessage(err, 'No se pudo consultar el estado del clip'));
      }
    }
    void poll();

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [id]);

  // Una vez AWAITING_EDIT, se pide el archivo editable una sola vez (requiere auth, por eso
  // no es un <video src> directo — ver getEditableBlobUrl).
  useEffect(() => {
    if (!id || clip?.processingStatus !== 'AWAITING_EDIT' || videoUrlRef.current) return;
    let cancelled = false;
    (async () => {
      try {
        const url = await getEditableBlobUrl(id);
        if (cancelled) return;
        videoUrlRef.current = url;
        setVideoUrl(url);
      } catch (err) {
        if (!cancelled) setVideoError(extractErrorMessage(err, 'No se pudo cargar el video'));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, clip?.processingStatus]);

  useEffect(() => {
    return () => {
      if (videoUrlRef.current) URL.revokeObjectURL(videoUrlRef.current);
    };
  }, []);

  // El video ya sorteó y aplicó su propio rango (ver ClipTrimmer.handleSurpriseMe) — acá solo
  // se registra en el historial y, si hay audio importado, se le pide a AudioTrimmer que
  // también sortee su posición (la entrada queda "pendiente" hasta que handleAudioRandomize
  // complete el dato de audio un instante después).
  function handleSurpriseMe(range: { startMs: number; endMs: number }) {
    const id = nextSurpriseIdRef.current++;
    pendingSurpriseIdRef.current = replacementAudio ? id : null;
    setSurpriseHistory((history) => [...history, { id, videoStartMs: range.startMs, videoEndMs: range.endMs }]);
    if (replacementAudio) setAudioRandomizeSignal((n) => n + 1);
  }

  // Completa, con la posición de audio recién sorteada, la entrada del historial que
  // handleSurpriseMe dejó pendiente (ver pendingSurpriseIdRef arriba).
  function handleAudioRandomize(startMs: number) {
    const id = pendingSurpriseIdRef.current;
    if (id === null) return;
    pendingSurpriseIdRef.current = null;
    setSurpriseHistory((history) => history.map((entry) => (entry.id === id ? { ...entry, audioStartMs: startMs } : entry)));
  }

  // El usuario eligió un sorteo anterior del historial — reaplica ese rango exacto (sin
  // sortear nada nuevo) al video y, si esa entrada tenía audio, también a la posición del audio.
  function handleSelectSurprise(entry: SurpriseHistoryEntry) {
    setRestoreVideoRange({ startMs: entry.videoStartMs, endMs: entry.videoEndMs });
    setRestoreVideoSignal((n) => n + 1);
    if (entry.audioStartMs !== undefined) {
      setRestoreAudioStartMs(entry.audioStartMs);
      setRestoreAudioSignal((n) => n + 1);
    }
  }

  async function handlePublish() {
    if (!id) return;
    setSubmitting(true);
    setSubmitError(null);
    // Solo se manda el fragmento de audio si el picker realmente mostró un recorte (audio más
    // largo que el máximo) — un audio corto se usa entero desde el principio, sin nada que
    // validar del lado del servidor.
    const audioNeedsTrim = replacementAudio !== null && replacementAudio.durationMs > MAX_CLIP_DURATION_MS;
    try {
      await finalizeClip(id, {
        trimStartMs: Math.round(trimStartMs),
        trimEndMs: Math.round(trimEndMs),
        muteOriginalAudio,
        replacementAudioTrackId: replacementAudio?.id,
        replacementAudioStartMs: audioNeedsTrim ? Math.round(replacementAudioStartMs) : undefined,
        replacementAudioEndMs: audioNeedsTrim ? Math.round(replacementAudioEndMs) : undefined,
        title: title.trim() || undefined,
        originalAudioVolume,
        replacementAudioVolume,
      });
      setDone(true);
    } catch (err) {
      setSubmitError(extractErrorMessage(err, 'No se pudo publicar el clip'));
    } finally {
      setSubmitting(false);
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

  if (pollError) {
    return (
      <div className="upload-page">
        <p className="clips-error" role="alert">
          {pollError}
        </p>
      </div>
    );
  }

  if (!clip) {
    return <p className="clips-loading">Cargando…</p>;
  }

  if (clip.processingStatus === 'FAILED') {
    return (
      <div className="upload-page">
        <p className="clips-error" role="alert">
          {clip.processingError ?? 'No se pudo procesar el clip.'}
        </p>
        <button type="button" onClick={() => navigate('/')}>
          Ir al feed
        </button>
      </div>
    );
  }

  if (clip.processingStatus === 'QUEUED' || clip.processingStatus === 'PROCESSING') {
    return (
      <div className="upload-page">
        <p className="clips-loading">
          {clip.sourceType === 'EXTERNAL_CAPTURE' ? 'Descargando y preparando el video…' : 'Preparando el video…'}
        </p>
      </div>
    );
  }

  return (
    <div className="editor-page">
      {videoError && (
        <p className="clips-error" role="alert">
          {videoError}
        </p>
      )}

      {videoUrl && (
        <ClipTrimmer
          videoUrl={videoUrl}
          maxDurationMs={MAX_CLIP_DURATION_MS}
          onChange={(start, end) => {
            setTrimStartMs(start);
            setTrimEndMs(end);
          }}
          showAddAudioButton={!replacementAudio}
          onAddAudioClick={() => setAudioModalOpen(true)}
          sourceTitle={clip.sourceTitle}
          sourceUrl={clip.sourceUrl}
          onVolumeChange={setOriginalAudioVolume}
          restartSignal={videoRestartSignal}
          onPositionChange={() => setAudioRestartSignal((n) => n + 1)}
          onSurpriseMe={handleSurpriseMe}
          surpriseHistory={surpriseHistory}
          onSelectSurprise={handleSelectSurprise}
          restoreRange={restoreVideoRange}
          restoreSignal={restoreVideoSignal}
        />
      )}

      <div className="editor-controls">
        {/* El "+" que abre este picker vive sobre la onda del audio original, dentro de
         * ClipTrimmer (arriba) — acá solo se maneja el modal y, una vez elegida una pista, su
         * propia UI (reproductor/recorte/mezclar). */}
        <AudioPicker
          selected={replacementAudio}
          mixWithOriginal={!muteOriginalAudio}
          onMixWithOriginalChange={(mix) => setMuteOriginalAudio(!mix)}
          modalOpen={audioModalOpen}
          onCloseModal={() => setAudioModalOpen(false)}
          onSelect={(track) => {
            setReplacementAudio(track);
            setReplacementAudioStartMs(0);
            setReplacementAudioEndMs(track ? Math.min(track.durationMs, MAX_CLIP_DURATION_MS) : 0);
          }}
          onRangeChange={(start, end) => {
            setReplacementAudioStartMs(start);
            setReplacementAudioEndMs(end);
          }}
          onVolumeChange={setReplacementAudioVolume}
          targetLengthMs={trimEndMs - trimStartMs}
          onPositionChange={() => setVideoRestartSignal((n) => n + 1)}
          restartSignal={audioRestartSignal}
          randomizeSignal={audioRandomizeSignal}
          onRandomize={handleAudioRandomize}
          restoreStartMs={restoreAudioStartMs}
          restoreSignal={restoreAudioSignal}
        />

        {!replacementAudio && (
          <label className="editor-mute-toggle">
            <input
              type="checkbox"
              checked={muteOriginalAudio}
              onChange={(event) => setMuteOriginalAudio(event.target.checked)}
            />
            Silenciar el audio original
          </label>
        )}

        <label className="editor-title-label">
          Título (opcional)
          <input
            type="text"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Sin título"
            maxLength={150}
          />
        </label>

        {submitError && (
          <p className="clips-error" role="alert">
            {submitError}
          </p>
        )}

        <button type="button" onClick={() => void handlePublish()} disabled={submitting || !videoUrl}>
          {submitting ? 'Publicando…' : 'Publicar clip'}
        </button>
      </div>
    </div>
  );
}
