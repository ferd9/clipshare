export type CommentAuthorType = 'USER' | 'GUEST';

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
}
