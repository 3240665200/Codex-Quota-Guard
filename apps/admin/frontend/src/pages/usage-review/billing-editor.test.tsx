import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp, ConfigProvider } from 'antd';
import { beforeEach, expect, it, vi } from 'vitest';
import { BillingEditor } from './billing-editor';
import { listUsageModelPrices } from '../../services/api';
import { previewBillingReview, readBillingDetail, saveBillingReview, type BillingDetail } from '../../services/billingReviewApi';

vi.mock('../../services/api', () => ({ listUsageModelPrices: vi.fn() }));
vi.mock('../../services/billingReviewApi', () => ({ readBillingDetail: vi.fn(), previewBillingReview: vi.fn(), saveBillingReview: vi.fn() }));
const detail: BillingDetail = {
  id: 'a'.repeat(64), userId: 'user-1', teamId: 'team-1', officialAccountId: 'account-1',
  occurredAt: '2026-10-30T09:08:16Z', receivedAt: '2026-10-30T09:08:18Z', source: 'TOKEN_USAGE_RECORD',
  original: { model: null, reasoningEffort: null, serviceTier: 'default', totalTokens: 1500000, inputTokens: 1000000,
    cachedInputTokens: 200000, cacheWriteInputTokens: 100000, outputTokens: 500000, reasoningOutputTokens: 200000 },
  effective: { model: null, reasoningEffort: null, serviceTier: 'standard', totalTokens: 1500000, inputTokens: 1000000,
    cachedInputTokens: 200000, cacheWriteInputTokens: 100000, outputTokens: 500000, reasoningOutputTokens: 200000 },
  revision: 0, fingerprint: 'b'.repeat(64), editable: true, pricingMode: 'CATALOG', customPrice: null,
  catalogPrice: null, appliedPrice: null, multiplier: '1', costStatus: 'MISSING_PRICE', costUsd: null, modeStatus: 'STANDARD', history: [],
};
const catalog = { model: 'review-model', inputPrice: 2, outputPrice: 10, cacheReadPrice: 0.2, cacheWritePrice: 3,
  fastMultiplier: 1.5, currency: 'USD' as const, updatedAt: '2026-10-30T00:00:00Z', priceSource: 'MANUAL' as const,
  sourceReference: null, sourceCheckedAt: null };
const calculation = { previewToken: 'c'.repeat(64), costStatus: 'PRICED', costUsd: '6.74', multiplier: '1',
  appliedPrice: catalog, missingFields: [] };
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(readBillingDetail).mockResolvedValue(detail);
  vi.mocked(listUsageModelPrices).mockResolvedValue([catalog]);
  vi.mocked(previewBillingReview).mockResolvedValue(calculation);
  vi.mocked(saveBillingReview).mockResolvedValue({ detail: { ...detail, editable: false, costStatus: 'PRICED', costUsd: '6.74',
    appliedPrice: catalog, revision: 1, effective: { ...detail.effective, model: 'review-model' } }, calculation });
});
function view() {
  const saved=vi.fn();
  render(<ConfigProvider theme={{ token: { motion: false } }}><AntApp><BillingEditor id={detail.id} onClose={vi.fn()} onSaved={saved} /></AntApp></ConfigProvider>);
  return saved;
}
async function fillKnownModel() {
  await screen.findByRole('combobox', { name: '模型' });
  fireEvent.change(screen.getByRole('combobox', { name: '模型' }), { target: { value: 'review-model' } });
  fireEvent.change(screen.getByRole('textbox', { name: '核对依据 / 修改原因' }), { target: { value: '依据设备原始日志补全模型' } });
}
it('edits every billing field and requires a server preview before posting a correction', async () => {
  const saved=view();await fillKnownModel();
  for (const label of ['总 Token','输入 Token（含缓存）','缓存读 Token','缓存写 Token','输出 Token（含思考）','思考 Token'])
    await waitFor(() => expect(screen.getByRole('spinbutton', { name: label })).toBeVisible());
  expect(screen.getByRole('combobox', { name: '思考等级' })).toBeVisible();
  expect(screen.getByRole('combobox', { name: '速度模式' })).toBeVisible();
  const confirm=screen.getByRole('button', { name: '确认保存并核算' });
  expect(confirm).toBeDisabled();expect(saveBillingReview).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '预览核算' }));
  await screen.findByText('核算预览：$6.74 USD · 本次倍率 ×1');
  expect(previewBillingReview).toHaveBeenCalledWith(detail.id, expect.objectContaining({
    revision: 0, fingerprint: detail.fingerprint, pricingMode: 'CATALOG', customPrice: null,
    facts: expect.objectContaining({ model: 'review-model', inputTokens: 1000000, cachedInputTokens: 200000, outputTokens: 500000 }),
  }));
  expect(saveBillingReview).not.toHaveBeenCalled();
  fireEvent.click(confirm);
  await waitFor(() => expect(saveBillingReview).toHaveBeenCalledWith(detail.id, expect.objectContaining({ previewToken: calculation.previewToken })));
  await screen.findByText('费用已入账，计费快照只读');
  expect(saved).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole('button', { name: '确认保存并核算' })).not.toBeInTheDocument();
});
it('invalidates the approved preview when evidence or another form field changes', async () => {
  view();await fillKnownModel();
  fireEvent.click(screen.getByRole('button', { name: '预览核算' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '确认保存并核算' })).toBeEnabled());
  fireEvent.change(screen.getByRole('textbox', { name: '核对依据 / 修改原因' }), { target: { value: '更正后的日志依据' } });
  expect(screen.getByRole('button', { name: '确认保存并核算' })).toBeDisabled();
  expect(screen.queryByText('核算预览：$6.74 USD · 本次倍率 ×1')).not.toBeInTheDocument();
  expect(saveBillingReview).not.toHaveBeenCalled();
});
it('supports per-record prices and does not send global catalog metadata as editable prices', async () => {
  view();await fillKnownModel();
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '定价方式' }));
  fireEvent.click(await screen.findByRole('option', { name: '仅本条记录使用自定义单价' }));
  fireEvent.click(await screen.findByRole('button', { name: '复制所选模型单价' }));
  for (const label of ['普通输入单价（USD / 百万 Token）','输出单价（含思考）（USD / 百万 Token）','缓存读单价（USD / 百万 Token）','缓存写单价（USD / 百万 Token）','快速模式计费倍率'])
    expect(screen.getByRole('spinbutton', { name: label })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '预览核算' }));
  await waitFor(() => expect(previewBillingReview).toHaveBeenCalled());
  const body=vi.mocked(previewBillingReview).mock.calls[0][1];
  expect(body.pricingMode).toBe('CUSTOM');
  expect(body.customPrice).toEqual({ inputPrice: 2, outputPrice: 10, cacheReadPrice: 0.2, cacheWritePrice: 3, fastMultiplier: 1.5 });
  expect(saveBillingReview).not.toHaveBeenCalled();
});
it('keeps the raw report and billed snapshots available after global prices change', async () => {
  vi.mocked(readBillingDetail).mockResolvedValue({ ...detail, editable: false, appliedPrice: catalog, costStatus: 'PRICED', costUsd: '6.74',
    effective: { ...detail.effective, model: 'review-model' } });
  vi.mocked(listUsageModelPrices).mockResolvedValue([{ ...catalog, inputPrice: 999 }]);
  view();await screen.findByText('费用已入账，计费快照只读');
  expect(screen.queryByRole('combobox', { name: '模型' })).not.toBeInTheDocument();
  expect(screen.queryByText('999')).not.toBeInTheDocument();
  fireEvent.click(screen.getByText('原始上报信息（只读保留）'));
  await waitFor(() => expect(screen.getAllByText('未记录').length).toBeGreaterThan(0));
  expect(previewBillingReview).not.toHaveBeenCalled();expect(saveBillingReview).not.toHaveBeenCalled();
});
it('restores a saved partial correction with its per-record prices', async () => {
  vi.mocked(readBillingDetail).mockResolvedValue({ ...detail, pricingMode: 'CUSTOM', customPrice: catalog,
    effective: { ...detail.effective, model: 'review-model' }, revision: 1 });
  view();
  const multiplier=await screen.findByRole('spinbutton', { name: '快速模式计费倍率' }, { timeout: 5000 });
  await waitFor(() => expect(Number((multiplier as HTMLInputElement).value)).toBe(1.5));
  expect(screen.getByRole('button', { name: '确认保存并核算' })).toBeDisabled();
});
