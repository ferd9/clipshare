export type UserRole = 'USER' | 'MODERATOR' | 'ADMIN';

export type UserStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED' | 'BANNED' | 'DELETED';

export interface UserProfile {
  id: string;
  email: string;
  displayName: string;
  role: UserRole;
  status: UserStatus;
  emailVerified: boolean;
  createdAt: string;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface ApiErrorBody {
  error: string;
  message: string;
}
