import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp, ConfigProvider } from 'antd';
import { beforeEach, expect, it, vi } from 'vitest';
import UsageReviewPage from './index';
import routes from '../../../config/routes';
import { listUsers } from '../../services/api';
import { listReviews, reviewUsage, confirmBillingMode, reconcilePrices, listReviewJobs, previewReviewJob, startReviewJob, type ReviewItem, type ReviewBulkJob } from '../../services/usageReviewApi';

vi.mock('../../services/api', () => ({ listUsers: vi.fn() }));
vi.mock('../../services/usageReviewApi', () => ({ listReviews: vi.fn(), reviewUsage: vi.fn(), confirmBillingMode: vi.fn(), reconcilePrices: vi.fn(), listReviewJobs: vi.fn(), previewReviewJob: vi.fn(), startReviewJob: vi.fn() }));
vi.mock('./billing-editor', () => ({ BillingEditor: ({ id }: { id: string }) => <section aria-label="完整计费核对表单">{id}</section> }));
const job: ReviewBulkJob = { id: 'job-1', requestedBy: 'admin-1', action: 'ARCHIVE', status: 'DRAFT', createdAt: '2026-10-26T14:00:00Z',
  totalCount: 125, excludedCount: 0, processedCount: 0, succeededCount: 0, skippedCount: 0, failedCount: 0, lastErrorCode: null };
const item: ReviewItem = {
  id: 'a'.repeat(64), userId: 'user-1', displayName: '待核对用户', email: 'review@example.com', deviceId: 'device-1',
  teamId: null, officialAccountId: null, occurredAt: '2026-09-01T00:00:00Z', receivedAt: '2026-10-26T00:00:00Z',
  reason: 'TOO_OLD', status: 'PENDING', reviewedAt: null, reviewedBy: null, reviewNote: null,
  event: { eventId: 'raw-1', occurredAt: '2026-09-01T00:00:00Z', totalTokens: 1234, source: 'TOKEN_USAGE_RECORD',
    model: 'fixture-model', reasoningEffort: 'max', serviceTier: null, inputTokens: 1000, cachedInputTokens: null,
    cacheWriteInputTokens: null, outputTokens: 234, reasoningOutputTokens: null },
};
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(listUsers).mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
  vi.mocked(listReviews).mockResolvedValue({ items: [item], page: 0, size: 20, totalElements: 1, totalPages: 1 });
  vi.mocked(reviewUsage).mockResolvedValue({ updated: 1 });
  vi.mocked(listReviewJobs).mockResolvedValue([]);
  vi.mocked(previewReviewJob).mockResolvedValue(job);
  vi.mocked(startReviewJob).mockResolvedValue({ ...job, status: 'COMPLETED', processedCount: 125, succeededCount: 125 });
});
it('freezes all matching pages before confirming a single background action', async () => {
  vi.mocked(listReviews).mockResolvedValue({ items: [item], page: 0, size: 20, totalElements: 125, totalPages: 7 });
  view(); await screen.findByText('待核对用户');
  fireEvent.click(screen.getByRole('button', { name: '一键归档全部匹配' }));
  await screen.findByText('已固定 125 条记录，包含所有匹配分页');
  expect(startReviewJob).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '确认并后台处理' }));
  await waitFor(() => expect(startReviewJob).toHaveBeenCalledWith('job-1', '按所选用户和时间范围批量归档，不计费'));
  expect(reviewUsage).not.toHaveBeenCalled();
});
it('selects a time range across pages instead of fetching only the visible rows', async () => {
  view(); await screen.findByText('待核对用户');
  fireEvent.change(screen.getByLabelText('核对开始时间'), { target: { value: '2026-09-01T00:00:00' } });
  fireEvent.change(screen.getByLabelText('核对结束时间'), { target: { value: '2026-09-30T23:59:59' } });
  fireEvent.click(screen.getByRole('button', { name: '按时间选择全部匹配' }));
  const expected = { from: new Date('2026-09-01T00:00:00').toISOString(), to: new Date('2026-10-01T00:00:00').toISOString() };
  await waitFor(() => expect(listReviews).toHaveBeenLastCalledWith(expect.objectContaining(expected), false));
  await screen.findByText('已跨页选择当前筛选的全部 1 条待核对记录');
  fireEvent.click(screen.getByRole('button', { name: '一键入账全部匹配' }));
  await waitFor(() => expect(previewReviewJob).toHaveBeenCalledWith('IMPORT', expect.objectContaining(expected)));
});
function view() { render(<ConfigProvider theme={{ token: { motion: false } }}><AntApp><UsageReviewPage /></AntApp></ConfigProvider>); }
it('has an administrator menu and displays raw review records without automatic billing', async () => {
  expect(routes).toContainEqual(expect.objectContaining({ path: '/usage-review', name: '用量核对', access: 'canAdmin' }));
  view();
  expect(await screen.findByText('待核对用户')).toBeVisible();
  expect(screen.getByText('超过 30 天')).toBeVisible();
  expect(reviewUsage).not.toHaveBeenCalled();
  expect(confirmBillingMode).not.toHaveBeenCalled();
  expect(reconcilePrices).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '查看详情' }));
  await waitFor(() => expect(screen.getByText('未记录，不自动假设普通模式')).toBeVisible(), { timeout: 5000 });
});
it('requires selection, explicit confirmation and a review note before batch archival', async () => {
  view(); await screen.findByText('待核对用户');
  expect(screen.getByRole('button', { name: '批量归档（不计费）' })).toBeDisabled();
  fireEvent.click(screen.getAllByRole('checkbox')[0]);
  fireEvent.click(screen.getByRole('button', { name: '批量归档（不计费）' }));
  expect(reviewUsage).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '确认处理' }));
  await screen.findByText('请填写核对依据或归档原因');
  expect(reviewUsage).not.toHaveBeenCalled();
  fireEvent.change(screen.getByRole('textbox', { name: '核对依据 / 处理备注' }), { target: { value: '确认历史测试记录，不计费' } });
  fireEvent.click(screen.getByRole('button', { name: '确认处理' }));
  await waitFor(() => expect(reviewUsage).toHaveBeenCalledWith([item.id], 'ARCHIVE', '确认历史测试记录，不计费'));
});
it('opens the full billing editor for a pending bill with missing model information', async () => {
  vi.mocked(listReviews).mockImplementation(async (_, billing) => ({ items: billing ? [{ id: item.id, userId: item.userId,
    displayName: item.displayName, email: item.email, occurredAt: item.occurredAt, model: null, totalTokens: 1234,
    costStatus: 'MISSING_PRICE', modeStatus: 'STANDARD' }] : [item], page: 0, size: 20, totalElements: 1, totalPages: 1 }));
  view();await screen.findByText('待核对用户');
  fireEvent.click(screen.getByRole('tab', { name: '计费信息待核算' }));
  fireEvent.click(await screen.findByRole('button', { name: '补全计费信息' }));
  expect(screen.getByRole('region', { name: '完整计费核对表单' })).toHaveTextContent(item.id);
  fireEvent.click(screen.getByRole('tab', { name: '人工核对记录' }));
  await waitFor(() => expect(listReviews).toHaveBeenLastCalledWith(expect.anything(), true, true));
});
