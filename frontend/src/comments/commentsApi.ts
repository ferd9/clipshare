import { apiClient } from '../api/client';
import type { PageResponse } from '../clips/types';
import type { ReportReason } from '../legal/reportsApi';
import type { CommentSummary } from './types';

export async function getComments(clipId: string, page = 0, size = 20): Promise<PageResponse<CommentSummary>> {
  const { data } = await apiClient.get<PageResponse<CommentSummary>>(`/api/clips/${clipId}/comments`, {
    params: { page, size },
  });
  return data;
}

export interface CreateCommentPayload {
  body: string;
  turnstileToken?: string;
  parentCommentId?: string;
}

export async function createComment(clipId: string, payload: CreateCommentPayload): Promise<CommentSummary> {
  const { data } = await apiClient.post<CommentSummary>(`/api/clips/${clipId}/comments`, payload);
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
