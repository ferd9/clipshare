import { apiClient } from '../api/client';
import type { ClipDetail, ClipUploadResult, PageResponse } from './types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/** Los DTOs del backend devuelven rutas relativas (ej. "/media/clips/{id}/final.mp4"); acá
 * se resuelven contra la API, que corre en otro origen que el frontend en dev (Vite). */
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

export async function getFeed(page = 0, size = 20): Promise<PageResponse<ClipDetail>> {
  const { data } = await apiClient.get<PageResponse<ClipDetail>>('/api/clips/feed', { params: { page, size } });
  return data;
}

export async function getClip(id: string): Promise<ClipDetail> {
  const { data } = await apiClient.get<ClipDetail>(`/api/clips/${id}`);
  return data;
}
