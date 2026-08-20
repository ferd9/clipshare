export type CommentAuthorType = 'USER' | 'GUEST';

export type AttachmentType = 'IMAGE' | 'CLIP_REFERENCE' | 'LINK';

export interface AttachmentSummary {
  id: string;
  type: AttachmentType;
  imageUrl: string | null;
  referencedClipId: string | null;
  linkUrl: string | null;
  linkDomain: string | null;
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
