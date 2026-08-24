import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { extractErrorMessage } from '../auth/AuthContext';
import { AudioTrimmer } from './AudioTrimmer';
import { importAudioFromLink, mediaUrl, uploadAudioTrack } from './clipsApi';
import type { AudioTrack } from './types';

const MAX_AUDIO_FRAGMENT_MS = 40_000;

interface AudioPickerProps {
  selected: AudioTrack | null;
  onSelect: (track: AudioTrack | null) => void;
  onRangeChange: (startMs: number, endMs: number) => void;
  /** true = agregar el audio nuevo MEZCLADO con el original; false = que lo reemplace. Vive en
   * ClipEditPage (junto con muteOriginalAudio, del que es el inverso) porque también aplica
   * sin ninguna pista elegida todavía — el checkbox del modal solo lo expone/edita. */
  mixWithOriginal: boolean;
  onMixWithOriginalChange: (mix: boolean) => void;
  /** El "+" que abre este modal vive en ClipTrimmer (sobre la onda del audio original, ver
   * docs/SPEC.md sección 1) — acá solo se controla si el modal está abierto o no. */
  modalOpen: boolean;
  onCloseModal: () => void;
  /** Reenviado tal cual a AudioTrimmer — ver su prop homónima. */
  onVolumeChange: (volume: number) => void;
  /** Reenviados tal cual a AudioTrimmer — ver sus props homónimas. */
  targetLengthMs?: number;
  onPositionChange?: () => void;
}

type Mode = 'menu' | 'link';

/**
 * Silenciar/reemplazar/mezclar el audio original al finalizar un clip. Mismo criterio de
 * riesgo ya aceptado para el video: el link también se descarga server-side vía yt-dlp. Corre
 * síncrono (POST /api/audio/*, sin cola) porque un archivo de audio de a lo sumo 10 minutos
 * procesa en segundos — ver AudioTrackService.
 *
 * El modal (disparado desde el "+" en ClipTrimmer) muestra el checkbox de mezclar/reemplazar
 * y las dos formas de conseguir el audio: "Importar" revela el input de URL ADENTRO del mismo
 * modal; "Subir" dispara el selector nativo de archivos directo (input file oculto), sin un
 * paso intermedio.
 *
 * Una vez elegida la pista, si dura más que el fragmento máximo (40s, igual que el video) se
 * muestra un recorte (AudioTrimmer) para elegir qué parte usar — mismo "sonido" reusado en
 * otro clip puede arrancar en otro punto cada vez, el fragmento es propio de este uso, no de
 * la pista (ver Clip.replacementAudioStartMs en el backend).
 */
export function AudioPicker({
  selected,
  onSelect,
  onRangeChange,
  mixWithOriginal,
  onMixWithOriginalChange,
  modalOpen,
  onCloseModal,
  onVolumeChange,
  targetLengthMs,
  onPositionChange,
}: AudioPickerProps) {
  const [mode, setMode] = useState<Mode>('menu');
  const [linkUrl, setLinkUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Arranca siempre limpio: sin esto, reabrir el modal después de un intento fallido (o de
  // haber entrado a "Importar" y cancelado) lo dejaría en el paso donde se quedó la vez anterior.
  useEffect(() => {
    if (modalOpen) {
      setMode('menu');
      setLinkUrl('');
      setError(null);
    }
  }, [modalOpen]);

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = ''; // permite volver a elegir el mismo archivo si falla
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const track = await uploadAudioTrack(file);
      onSelect(track);
      onCloseModal();
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo subir el audio'));
    } finally {
      setBusy(false);
    }
  }

  async function handleLinkSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const track = await importAudioFromLink(linkUrl.trim());
      onSelect(track);
      onCloseModal();
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo importar el audio'));
    } finally {
      setBusy(false);
    }
  }

  if (selected) {
    return (
      <div className="audio-picker">
        {/* La pista nueva va PRIMERO (justo debajo de la línea del audio original, que está
         * arriba en ClipTrimmer) — el resto (mezclar) va después. Sin reproductor nativo ni el
         * link como "nombre": el propio AudioTrimmer ya deja escuchar el fragmento (con su
         * botón de play/pausa) y muestra el nombre real debajo de la pista. El ícono de quitar
         * queda al costado del track, no en una fila aparte (ver AudioTrimmer). */}
        <AudioTrimmer
          audioUrl={mediaUrl(selected.audioUrl) ?? ''}
          durationMs={selected.durationMs}
          maxDurationMs={MAX_AUDIO_FRAGMENT_MS}
          trackName={selected.title}
          sourceUrl={selected.sourceUrl}
          onChange={onRangeChange}
          onRemove={() => onSelect(null)}
          onVolumeChange={onVolumeChange}
          targetLengthMs={targetLengthMs}
          onPositionChange={onPositionChange}
        />

        <label className="editor-radio">
          <input
            type="checkbox"
            checked={mixWithOriginal}
            onChange={(event) => onMixWithOriginalChange(event.target.checked)}
          />
          Mezclar con el audio original (si no, lo reemplaza)
        </label>
      </div>
    );
  }

  if (!modalOpen) return null;

  return (
    <div className="audio-picker-modal-overlay" onClick={onCloseModal}>
      <div className="audio-picker-modal" onClick={(event) => event.stopPropagation()}>
        <div className="audio-picker-modal-header">
          <h3>Agregar audio</h3>
          <button type="button" className="audio-picker-modal-close" onClick={onCloseModal} aria-label="Cerrar">
            ×
          </button>
        </div>

        <label className="editor-radio">
          <input
            type="checkbox"
            checked={mixWithOriginal}
            onChange={(event) => onMixWithOriginalChange(event.target.checked)}
          />
          Mezclar con el audio original (si no, lo reemplaza)
        </label>

        {mode === 'menu' && (
          <div className="audio-picker-actions">
            <button type="button" onClick={() => fileInputRef.current?.click()} disabled={busy}>
              Subir audio
            </button>
            <button type="button" onClick={() => setMode('link')} disabled={busy}>
              Importar desde un link
            </button>
          </div>
        )}

        {mode === 'link' && (
          <form className="audio-picker-form" onSubmit={(e) => void handleLinkSubmit(e)}>
            <input
              type="url"
              value={linkUrl}
              onChange={(event) => setLinkUrl(event.target.value)}
              placeholder="https://www.youtube.com/watch?v=..."
              required
              disabled={busy}
            />
            <button type="submit" disabled={busy || !linkUrl.trim()}>
              {busy ? 'Importando…' : 'Usar este audio'}
            </button>
            <button type="button" onClick={() => setMode('menu')} disabled={busy}>
              Cancelar
            </button>
          </form>
        )}

        {/* Oculto a propósito: "Subir audio" lo dispara directo (sin un input visible de por
         * medio), para que aparezca el selector nativo del sistema operativo enseguida. */}
        <input
          ref={fileInputRef}
          type="file"
          accept="audio/*,video/*"
          onChange={(e) => void handleFileChange(e)}
          style={{ display: 'none' }}
        />

        {busy && mode === 'menu' && <p className="clips-loading">Subiendo…</p>}
        {error && (
          <p className="clips-error" role="alert">
            {error}
          </p>
        )}
      </div>
    </div>
  );
}
