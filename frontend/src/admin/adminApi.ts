import { apiClient } from '../api/client';
import type { PageResponse } from '../clips/types';

export type AdminReportReason = 'COPYRIGHT_DMCA' | 'CSAM' | 'HARASSMENT' | 'OTHER';
export type AdminReportStatus = 'OPEN' | 'UNDER_REVIEW' | 'ACTIONED' | 'DISMISSED';
export type ReportAction = 'CONFIRMED' | 'DISMISSED';

export interface AdminReportSummary {
  id: string;
  clipId: string;
  reason: AdminReportReason;
  reporterName: string | null;
  reporterEmail: string;
  description: string | null;
  status: AdminReportStatus;
  createdAt: string;
}

export async function getPendingReports(page = 0, size = 20): Promise<PageResponse<AdminReportSummary>> {
  const { data } = await apiClient.get<PageResponse<AdminReportSummary>>('/api/admin/reports', {
    params: { page, size },
  });
  return data;
}

export async function resolveReport(
  reportId: string,
  action: ReportAction,
): Promise<{ id: string; status: AdminReportStatus }> {
  const { data } = await apiClient.post(`/api/admin/reports/${reportId}/action`, { action });
  return data;
}
