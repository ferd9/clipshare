export type ProcessingStatus = 'QUEUED' | 'PROCESSING' | 'AWAITING_EDIT' | 'READY' | 'FAILED';

export type ModerationStatus = 'PENDING' | 'PUBLISHED' | 'REJECTED' | 'TAKEN_DOWN';

export type ClipSourceType = 'OWN_UPLOAD' | 'EXTERNAL_CAPTURE';

/** Las 10 más populares que soportamos para importar por link — ver SUPPORTED_PLATFORMS en
 * platformDetection.ts, la fuente única de la que sale esta lista (agregar una plataforma
 * nueva empieza ahí). */
export type ClipPlatform =
  | 'YOUTUBE'
  | 'TIKTOK'
  | 'INSTAGRAM'
  | 'FACEBOOK'
  | 'TWITTER'
  | 'TWITCH'
  | 'VIMEO'
  | 'REDDIT'
  | 'DAILYMOTION'
  | 'SOUNDCLOUD'
  | 'NONE';

export interface ClipDetail {
  id: string;
  ownerId: string;
  ownerDisplayName: string;
  sourceType: ClipSourceType;
  sourcePlatform: ClipPlatform;
  processingStatus: ProcessingStatus;
  moderationStatus: ModerationStatus;
  processingError: string | null;
  title: string | null;
  /** Título obtenido por yt-dlp del video ORIGINAL (solo EXTERNAL_CAPTURE) — no confundir
   * con `title` de arriba, que es el que el propio usuario eligió para el clip. */
  sourceTitle: string | null;
  /** Link original (solo EXTERNAL_CAPTURE) de donde se importó el video — null si es OWN_UPLOAD. */
  sourceUrl: string | null;
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

export interface AudioTrack {
  id: string;
  title: string | null;
  durationMs: number;
  audioUrl: string;
  /** Link original si se importó desde un enlace (yt-dlp) — null si se subió un archivo. */
  sourceUrl: string | null;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
