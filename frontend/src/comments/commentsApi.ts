import { apiClient } from '../api/client';
import type { PageResponse } from '../clips/types';
import type { ReportReason } from '../legal/reportsApi';
import type { AttachmentType, CommentSummary } from './types';

export async function getComments(clipId: string, page = 0, size = 20): Promise<PageResponse<CommentSummary>> {
  const { data } = await apiClient.get<PageResponse<CommentSummary>>(`/api/clips/${clipId}/comments`, {
    params: { page, size },
  });
  return data;
}

export interface AttachmentPayload {
  type: AttachmentType;
  attachmentId?: string;
  referencedClipId?: string;
  linkUrl?: string;
}

export interface CreateCommentPayload {
  body: string;
  turnstileToken?: string;
  parentCommentId?: string;
  attachments?: AttachmentPayload[];
}

export async function createComment(clipId: string, payload: CreateCommentPayload): Promise<CommentSummary> {
  const { data } = await apiClient.post<CommentSummary>(`/api/clips/${clipId}/comments`, payload);
  return data;
}

/** Sube una imagen suelta ANTES de crear el comentario que la va a referenciar (docs/SPEC.md
 * sección 11.9) — solo USER con email verificado, ver CommentAttachmentService en el backend. */
export async function uploadCommentImage(file: File): Promise<{ attachmentId: string }> {
  const formData = new FormData();
  formData.append('file', file);
  const { data } = await apiClient.post<{ attachmentId: string }>('/api/comments/attachments/image', formData);
  return data;
}

export interface ReportCommentPayload {
  reason: ReportReason;
  reporterName?: string;
  reporterEmail: string;
  description?: string;
}

export async function reportComment(commentId: string, payload: ReportCommentPayload): Promise<void> {
  await apiClient.post(`/api/comments/${commentId}/report`, payload);
}

export async function deleteComment(commentId: string): Promise<void> {
  await apiClient.delete(`/api/comments/${commentId}`);
}
