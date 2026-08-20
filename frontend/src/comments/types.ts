export type CommentAuthorType = 'USER' | 'GUEST';

export type AttachmentType = 'IMAGE' | 'CLIP_REFERENCE' | 'LINK';

export type EmbedPlatform = 'YOUTUBE' | 'VIMEO' | 'TWITCH' | 'TIKTOK' | 'FACEBOOK' | 'INSTAGRAM';

export interface AttachmentSummary {
  id: string;
  type: AttachmentType;
  imageUrl: string | null;
  referencedClipId: string | null;
  linkUrl: string | null;
  linkDomain: string | null;
  embedPlatform: EmbedPlatform | null;
  embedExternalId: string | null;
  embedTitle: string | null;
  embedThumbnailUrl: string | null;
  embeddable: boolean;
}

export interface CommentSummary {
  id: string;
  clipId: string;
  parentCommentId: string | null;
  authorType: CommentAuthorType;
  authorDisplayName: string;
  body: string;
  likeCount: number;
  createdAt: string;
  canDelete: boolean;
  attachments: AttachmentSummary[];
}
