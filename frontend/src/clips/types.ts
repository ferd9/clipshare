export type ProcessingStatus = 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED';

export type ModerationStatus = 'PENDING' | 'PUBLISHED' | 'REJECTED' | 'TAKEN_DOWN';

export interface ClipDetail {
  id: string;
  ownerId: string;
  ownerDisplayName: string;
  processingStatus: ProcessingStatus;
  moderationStatus: ModerationStatus;
  durationMs: number | null;
  width: number | null;
  height: number | null;
  viewCount: number;
  likeCount: number;
  createdAt: string;
  publishedAt: string | null;
  videoUrl: string | null;
  thumbnailUrl: string | null;
}

export interface ClipUploadResult {
  id: string;
  processingStatus: ProcessingStatus;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
