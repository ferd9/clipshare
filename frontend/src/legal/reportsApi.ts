import { apiClient } from '../api/client';

export type ReportReason = 'COPYRIGHT_DMCA' | 'CSAM' | 'HARASSMENT' | 'OTHER';

export interface CreateReportPayload {
  clipId: string;
  reason: ReportReason;
  reporterName?: string;
  reporterEmail: string;
  reporterAddress?: string;
  description?: string;
  goodFaithStatement?: boolean;
  accuracyStatement?: boolean;
  signature?: string;
}

export interface ReportResult {
  id: string;
  status: string;
}

export async function createReport(payload: CreateReportPayload): Promise<ReportResult> {
  const { data } = await apiClient.post<ReportResult>('/api/reports', payload);
  return data;
}

export interface CounterNoticePayload {
  statement: string;
  consentToJurisdiction: boolean;
  signature: string;
}

export interface CounterNoticeResult {
  id: string;
  restoreEligibleAt: string | null;
}

export async function submitCounterNotice(
  reportId: string,
  payload: CounterNoticePayload,
): Promise<CounterNoticeResult> {
  const { data } = await apiClient.post<CounterNoticeResult>(`/api/reports/${reportId}/counter-notice`, payload);
  return data;
}
