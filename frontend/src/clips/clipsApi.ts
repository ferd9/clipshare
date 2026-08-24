import { apiClient } from '../api/client';
import type { AudioTrack, ClipDetail, ClipPlatform, ClipUploadResult, PageResponse } from './types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/** Los DTOs del backend devuelven rutas relativas (ej. "/media/clips/{id}/final.mp4"); acá
 * se resuelven contra la API, que corre en otro origen que el frontend en dev (Vite). Sirve
 * tanto para /media/clips/** (publicado) como /media/audio/** (pistas de reemplazo) —
 * ambas son estáticas y públicas, a diferencia de /api/clips/{id}/editable (ver abajo). */
export function mediaUrl(path: string | null): string | undefined {
  return path ? `${API_BASE_URL}${path}` : undefined;
}

export async function uploadClip(file: File, onProgress?: (percent: number) => void): Promise<ClipUploadResult> {
  const formData = new FormData();
  formData.append('file', file);

  const { data } = await apiClient.post<ClipUploadResult>('/api/clips/upload', formData, {
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    },
  });
  return data;
}

/** Reemplaza al viejo uploadFromCapture (grabación de pantalla, retirado por calidad —
 * ver docs/SPEC.md): descarga server-side vía yt-dlp, no se manda ningún archivo acá. */
export async function importFromLink(sourceUrl: string, sourcePlatform: ClipPlatform): Promise<ClipUploadResult> {
  const { data } = await apiClient.post<ClipUploadResult>('/api/clips/import', { sourceUrl, sourcePlatform });
  return data;
}

export async function getFeed(page = 0, size = 20): Promise<PageResponse<ClipDetail>> {
  const { data } = await apiClient.get<PageResponse<ClipDetail>>('/api/clips/feed', { params: { page, size } });
  return data;
}

export async function getClip(id: string): Promise<ClipDetail> {
  const { data } = await apiClient.get<ClipDetail>(`/api/clips/${id}`);
  return data;
}

/** El archivo "editable" (fase AWAITING_EDIT) no es público como /media/clips/** — solo el
 * dueño puede verlo, así que hace falta pasar por apiClient (con el Bearer token) en vez de
 * un <video src> directo, y armar un blob URL local con lo que devuelve. */
export async function getEditableBlobUrl(clipId: string): Promise<string> {
  const { data } = await apiClient.get(`/api/clips/${clipId}/editable`, { responseType: 'blob' });
  return URL.createObjectURL(data as Blob);
}

export interface FinalizeClipParams {
  trimStartMs: number;
  trimEndMs: number;
  muteOriginalAudio: boolean;
  replacementAudioTrackId?: string;
  /** Fragmento elegido del AUDIO de reemplazo (independiente del recorte del video de arriba,
   * también acotado a 20s) — sin efecto si no hay replacementAudioTrackId. */
  replacementAudioStartMs?: number;
  replacementAudioEndMs?: number;
  /** Opcional — nunca bloquea publicar. */
  title?: string;
  /** Nivel (0-1) elegido en el editor para cada pista — solo tiene efecto real cuando se
   * mezclan las dos (ver FfmpegProcessor.finalizeClip); sin reemplazo o con reemplazo puro
   * también se aplican, así el volumen elegido en la vista previa se refleja en el clip final. */
  originalAudioVolume?: number;
  replacementAudioVolume?: number;
}

export async function finalizeClip(clipId: string, params: FinalizeClipParams): Promise<ClipUploadResult> {
  const { data } = await apiClient.post<ClipUploadResult>(`/api/clips/${clipId}/finalize`, params);
  return data;
}

// ---- Pistas de audio de reemplazo (docs/SPEC.md sección 1) ----

export async function uploadAudioTrack(file: File): Promise<AudioTrack> {
  const formData = new FormData();
  formData.append('file', file);
  const { data } = await apiClient.post<AudioTrack>('/api/audio/upload', formData);
  return data;
}

export async function importAudioFromLink(sourceUrl: string): Promise<AudioTrack> {
  const { data } = await apiClient.post<AudioTrack>('/api/audio/import-link', { sourceUrl });
  return data;
}
