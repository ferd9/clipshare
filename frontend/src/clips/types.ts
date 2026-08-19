export type ProcessingStatus = 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED';

export type ModerationStatus = 'PENDING' | 'PUBLISHED' | 'REJECTED' | 'TAKEN_DOWN';

export type ClipSourceType = 'OWN_UPLOAD' | 'EXTERNAL_CAPTURE';

export type ClipPlatform = 'YOUTUBE' | 'VIMEO' | 'TWITCH' | 'NONE';

export interface ClipDetail {
  id: string;
  ownerId: string;
  ownerDisplayName: string;
  sourceType: ClipSourceType;
  sourcePlatform: ClipPlatform;
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
