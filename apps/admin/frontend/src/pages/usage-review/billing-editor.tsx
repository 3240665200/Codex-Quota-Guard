import { Alert, App, AutoComplete, Button, Col, Collapse, Descriptions, Form, Input, InputNumber, Modal, Row, Select, Space, Spin, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { getErrorMessage } from '../../components/AdminState';
import { listUsageModelPrices, type UsageModelPrice } from '../../services/api';
import { previewBillingReview, readBillingDetail, saveBillingReview, type BillingDetail, type BillingFacts, type BillingPreview, type BillingPrices, type BillingReviewInput } from '../../services/billingReviewApi';

const tokenFields = [
  ['totalTokens', '总 Token'], ['inputTokens', '输入 Token（含缓存）'], ['cachedInputTokens', '缓存读 Token'],
  ['cacheWriteInputTokens', '缓存写 Token'], ['outputTokens', '输出 Token（含思考）'], ['reasoningOutputTokens', '思考 Token'],
] as const;
const priceFields = [['inputPrice', '普通输入单价'], ['outputPrice', '输出单价（含思考）'], ['cacheReadPrice', '缓存读单价'], ['cacheWritePrice', '缓存写单价']] as const;
const missingLabels: Record<string, string> = { model: '模型', price: '模型单价', inputTokens: '输入 Token', cachedInputTokens: '缓存读 Token', cacheWriteInputTokens: '缓存写 Token', outputTokens: '输出 Token', serviceTier: '速度模式' };
const value = (text: unknown) => text === null || text === undefined || text === '' ? '未记录' : String(text);
const tierLabel = (tier: string | null) => tier === 'fast' ? '快速模式' : tier === 'standard' ? '普通模式' : tier && tier !== 'unknown' ? tier : '未确认';
function factsItems(facts: BillingFacts) {
  return [
    { key: 'model', label: '模型', children: value(facts.model) },
    { key: 'effort', label: '思考等级', children: value(facts.reasoningEffort) },
    { key: 'tier', label: '速度模式', children: tierLabel(facts.serviceTier) },
    ...tokenFields.map(([key,label]) => ({ key, label, children: value(facts[key]) })),
  ];
}
interface Draft extends BillingFacts { pricingMode: 'CATALOG' | 'CUSTOM'; customPrice?: BillingPrices; note: string }

export function BillingEditor({ id, onClose, onSaved }: { id: string; onClose: () => void; onSaved: () => void }) {
  const { message } = App.useApp();
  const [form] = Form.useForm<Draft>();
  const [detail, setDetail] = useState<BillingDetail>();
  const [prices, setPrices] = useState<UsageModelPrice[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'preview' | 'save'>();
  const [error, setError] = useState('');
  const [priceError, setPriceError] = useState('');
  const [preview, setPreview] = useState<BillingPreview>();
  const [reload, setReload] = useState(0);
  const lifetime = useRef(0);
  const draftVersion = useRef(0);
  const previewInput = useRef<BillingReviewInput | undefined>(undefined);
  const model = Form.useWatch('model', { form, preserve: true });
  const pricingMode = Form.useWatch('pricingMode', { form, preserve: true });
  const selectedPrice = prices.find((price) => price.model === model);
  const displayedCatalogPrice = preview?.appliedPrice ?? selectedPrice;

  useEffect(() => {
    const current = ++lifetime.current;
    setLoading(true); setError(''); setDetail(undefined); setPreview(undefined); previewInput.current = undefined;
    void Promise.allSettled([readBillingDetail(id), listUsageModelPrices()]).then(([record, catalog]) => {
      if (current !== lifetime.current) return;
      if (record.status === 'fulfilled') {
        setDetail(record.value);
      } else setError(getErrorMessage(record.reason));
      if (catalog.status === 'fulfilled') { setPrices(catalog.value); setPriceError(''); }
      else setPriceError('全局价格表暂时无法读取；可重新打开重试，或为本条记录填写自定义单价。');
      setLoading(false);
    });
    return () => { lifetime.current++; };
  }, [id, form, reload]);

  useEffect(() => {
    if (detail?.editable) form.setFieldsValue({ ...detail.effective, pricingMode: detail.pricingMode,
      customPrice: detail.customPrice ?? undefined, note: '' });
  }, [detail, form]);

  function changed() { draftVersion.current++; setPreview(undefined); previewInput.current = undefined; }
  async function calculate() {
    if (!detail?.editable || busy) return;
    let draft: Draft;
    try { draft = await form.validateFields(); } catch { return; }
    const current = lifetime.current, version = draftVersion.current;
    const facts: BillingFacts = { model: draft.model?.trim() || null, reasoningEffort: draft.reasoningEffort || null,
      serviceTier: draft.serviceTier || 'unknown', totalTokens: draft.totalTokens,
      inputTokens: draft.inputTokens ?? null, cachedInputTokens: draft.cachedInputTokens ?? null,
      cacheWriteInputTokens: draft.cacheWriteInputTokens ?? null, outputTokens: draft.outputTokens ?? null,
      reasoningOutputTokens: draft.reasoningOutputTokens ?? null };
    const body: BillingReviewInput = { revision: detail.revision, fingerprint: detail.fingerprint, facts,
      pricingMode: draft.pricingMode, customPrice: draft.pricingMode === 'CUSTOM' ? draft.customPrice! : null, note: draft.note.trim() };
    setBusy('preview'); setError('');
    try {
      const next = await previewBillingReview(id, body);
      if (current !== lifetime.current || version !== draftVersion.current) return;
      previewInput.current = body; setPreview(next);
    } catch (failure) { if (current === lifetime.current) setError(getErrorMessage(failure)); }
    finally { if (current === lifetime.current) setBusy(undefined); }
  }
  async function save() {
    if (!detail?.editable || !preview || !previewInput.current || busy) return;
    const current = lifetime.current;
    setBusy('save'); setError('');
    try {
      const saved = await saveBillingReview(id, { ...previewInput.current, previewToken: preview.previewToken });
      if (current !== lifetime.current) return;
      setDetail(saved.detail); setPreview(undefined); previewInput.current = undefined;
      void message.success(saved.detail.costStatus === 'PRICED' ? '核对信息已保存并入账，相关 USD 额度已更新' : '已保存核对信息；其余缺失项补全后才能入账');
      onSaved();
    } catch (failure) { if (current === lifetime.current) setError(getErrorMessage(failure)); }
    finally { if (current === lifetime.current) setBusy(undefined); }
  }
  const readonly = detail && !detail.editable;
  return <Modal title={readonly ? '计费核对详情（已入账）' : '补全计费信息'} open onCancel={() => { if (busy !== 'save') onClose(); }}
    closable={busy !== 'save'} mask={{ closable: busy !== 'save' }} width={900}
    style={{ top: 24 }} styles={{ body: { maxHeight: 'calc(100dvh - 220px)', overflowY: 'auto', paddingRight: 8 } }}
    footer={<Space wrap>
      {preview && <Typography.Text strong type={preview.costUsd === null ? 'warning' : undefined}>{preview.costUsd === null ? '仍待核算' : `预览费用 $${preview.costUsd} USD`}</Typography.Text>}
      <Button disabled={busy === 'save'} onClick={onClose}>{readonly ? '关闭' : '取消'}</Button>
      {detail?.editable && <>
        <Button loading={busy === 'preview'} disabled={!!busy} onClick={() => void calculate()}>预览核算</Button>
        <Button type="primary" loading={busy === 'save'} disabled={!preview || !!busy} onClick={() => void save()}>确认保存并核算</Button>
      </>}
    </Space>}>
    {loading ? <Spin description="正在读取完整计费信息"><div style={{ minHeight: 160 }} /></Spin> : <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon title={error} action={<Button size="small" disabled={!!busy} onClick={() => { changed(); form.resetFields(); setReload((n) => n + 1); }}>重新读取并清空草稿</Button>} />}
      {detail && <>
        <Descriptions size="small" column={2} items={[
          { key: 'occurred', label: '原始消耗时间', children: new Date(detail.occurredAt).toLocaleString('zh-CN') },
          { key: 'status', label: '计费状态', children: <Tag color={readonly ? 'success' : 'warning'}>{readonly ? '已核算' : '待核算'}</Tag> },
          { key: 'cost', label: '已核算费用', children: detail.costUsd === null ? '待核算' : `$${detail.costUsd} USD` },
          { key: 'revision', label: '核对版本', children: detail.revision },
        ]} />
        <Collapse items={[{ key: 'original', label: '原始上报信息（只读保留）', children: <Descriptions size="small" column={2} bordered items={factsItems(detail.original)} /> }]} />
        {readonly ? <>
          <Alert type="success" showIcon title="费用已入账，计费快照只读" description="此处显示核对后的记录和入账单价，不会因后续价格表调整而改写旧账。" />
          <Descriptions title="核对后信息" column={2} size="small" bordered items={factsItems(detail.effective)} />
          {detail.appliedPrice && <Descriptions title="入账单价快照（USD / 百万 Token）" column={2} size="small" bordered items={[
            ...priceFields.map(([key,label]) => ({ key, label, children: value(detail.appliedPrice?.[key]) })),
            { key: 'multiplier', label: '本次计费倍率', children: `×${detail.multiplier}` },
          ]} />}
        </> : <>
          <Alert type="info" showIcon title="按证据补全本条记录；留空代表仍未确认" description="输入已包含缓存，输出已包含思考；缓存与思考不会重复叠加。自定义单价仅用于本条记录，保存后会同步核算个人、团队及官方账户的 USD 额度。" />
          <Form form={form} layout="vertical" onValuesChange={changed} disabled={!!busy}>
            <Row gutter={16}>
              <Col xs={24} sm={12}><Form.Item name="model" label="模型" rules={[{ pattern: /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}$/, message: '请输入有效的模型标识（最多 96 字符）' }]}>
                <AutoComplete allowClear options={prices.map((price) => ({ value: price.model }))} filterOption={(input, option) => String(option?.value).toLowerCase().includes(input.toLowerCase())} placeholder="选择或输入真实模型" />
              </Form.Item></Col>
              <Col xs={24} sm={12}><Form.Item name="reasoningEffort" label="思考等级"><Select allowClear placeholder="未记录" options={['none','minimal','low','medium','high','xhigh','max','ultra'].map((item) => ({ value: item, label: item }))} /></Form.Item></Col>
              <Col xs={24} sm={12}><Form.Item name="serviceTier" label="速度模式"><Select options={[{ value: 'unknown', label: '未确认' }, { value: 'standard', label: '普通模式 ×1' }, { value: 'fast', label: '快速模式（按所用单价的倍率）' }]} /></Form.Item></Col>
            </Row>
            <Typography.Title level={5}>Token 分项</Typography.Title>
            <Row gutter={16}>{tokenFields.map(([key,label]) => <Col xs={24} sm={12} key={key}>
              <Form.Item name={key} label={label} rules={[...(key === 'totalTokens' ? [{ required: true, message: '请填写总 Token' }] : []),
                { validator: (_, n: unknown) => n === null || n === undefined || typeof n === 'number' && Number.isInteger(n) && n >= 0 && n <= 100_000_000 ? Promise.resolve() : Promise.reject(new Error('请输入 0 到 100,000,000 的整数')) }]}>
                <InputNumber style={{ width: '100%' }} min={0} max={100_000_000} step={1} placeholder="未记录；确定为零时填写 0" />
              </Form.Item>
            </Col>)}</Row>
            <Button size="small" style={{ marginBottom: 16 }} onClick={() => {
              const input = form.getFieldValue('inputTokens'), output = form.getFieldValue('outputTokens');
              if (typeof input !== 'number' || typeof output !== 'number' || input + output > 100_000_000) { void message.error('请先填写有效的输入与输出 Token'); return; }
              form.setFieldValue('totalTokens', input + output); changed();
            }}>按输入 + 输出计算总 Token</Button>
            <Typography.Title level={5}>价格与倍率</Typography.Title>
            {priceError && <Alert type="warning" showIcon title={priceError} style={{ marginBottom: 12 }} />}
            <Form.Item name="pricingMode" label="定价方式"><Select virtual={false} options={[{ value: 'CATALOG', label: '使用所选模型的现有价格表' }, { value: 'CUSTOM', label: '仅本条记录使用自定义单价' }]} /></Form.Item>
            {pricingMode === 'CUSTOM' ? <>
              <Button size="small" disabled={!selectedPrice || !!busy} style={{ marginBottom: 12 }} onClick={() => {
                if (!selectedPrice) return;
                form.setFieldValue('customPrice', { inputPrice: selectedPrice.inputPrice, outputPrice: selectedPrice.outputPrice,
                  cacheReadPrice: selectedPrice.cacheReadPrice, cacheWritePrice: selectedPrice.cacheWritePrice, fastMultiplier: selectedPrice.fastMultiplier ?? 1.5 }); changed();
              }}>复制所选模型单价</Button>
              <Row gutter={16}>{[...priceFields, ['fastMultiplier','快速模式计费倍率'] as const].map(([key,label]) => <Col xs={24} sm={12} key={key}>
                <Form.Item name={['customPrice',key]} label={key === 'fastMultiplier' ? label : `${label}（USD / 百万 Token）`} rules={[{ required: true, message: '请填写已确认的价格或倍率；免费单价填 0' },
                  { validator: (_, n: unknown) => n !== null && n !== undefined && /^(?:0|[1-9]\d*)(?:\.\d{1,6})?$/.test(String(n)) && Number(n) >= (key === 'fastMultiplier' ? 1 : 0) && Number(n) <= (key === 'fastMultiplier' ? 100 : 1_000_000)
                    ? Promise.resolve() : Promise.reject(new Error('单价最多 6 位小数；倍率为 1–100，单价为 0–1,000,000')) }]}>
                  <InputNumber stringMode min={key === 'fastMultiplier' ? '1' : '0'} max={key === 'fastMultiplier' ? '100' : '1000000'} style={{ width: '100%' }} step="0.01" />
                </Form.Item>
              </Col>)}</Row>
            </> : displayedCatalogPrice ? <Descriptions column={2} size="small" bordered items={[
              ...priceFields.map(([key,label]) => ({ key, label: `${label}（USD / M）`, children: value(displayedCatalogPrice[key]) })),
              { key: 'fast', label: '快速模式倍率', children: `×${displayedCatalogPrice.fastMultiplier ?? 1.5}` },
            ]} /> : <Alert type="warning" showIcon title="当前模型尚无单价" description="请填写本条自定义单价，或先在“用量统计”的模型定价中设置全局单价。缺少价格的记录仍会保留为待核算。" />}
            <Form.Item name="note" label="核对依据 / 修改原因" style={{ marginTop: 16 }} rules={[{ required: true, whitespace: true, message: '请填写核对依据' }, { max: 500 }]}><Input.TextArea maxLength={500} rows={3} /></Form.Item>
          </Form>
        </>}
        {preview && <Alert type={preview.costUsd === null ? 'warning' : 'success'} showIcon title={preview.costUsd === null ? '仍有信息待补全，保存不会计费' : `核算预览：$${preview.costUsd} USD · 本次倍率 ×${preview.multiplier}`}
          description={preview.missingFields.length ? `尚缺：${preview.missingFields.map((field) => missingLabels[field] ?? field).join('、')}。` : '仅在点击“确认保存并核算”后入账；修改任何字段都需要重新预览。'} />}
        {detail.history.length > 0 && <Collapse items={[{ key: 'audit', label: `核对操作日志（最近 ${detail.history.length} 次）`, children: <Space orientation="vertical" size={16} style={{ width: '100%' }}>
          {detail.history.map((audit) => <div key={audit.id}>
            <Typography.Text strong>第 {audit.revision} 次 · {audit.administratorName ?? audit.reviewedBy ?? '已删除管理员'} · {new Date(audit.reviewedAt).toLocaleString('zh-CN')}</Typography.Text>
            <p>{audit.note}</p>
            <Descriptions column={2} size="small" items={[
              ...factsItems(audit.after.effective).map((field, index) => ({ ...field, children: <>{factsItems(audit.before.effective)[index].children} → {field.children}</> })),
              { key: 'cost', label: '核算费用（USD）', children: `${value(audit.before.billing.costUsd)} → ${value(audit.after.billing.costUsd)}` },
              ...priceFields.map(([key,label]) => ({ key, label, children: `${value(audit.before.priceSnapshot?.[key])} → ${value(audit.after.priceSnapshot?.[key])}` })),
            ]} />
          </div>)}
        </Space> }]} />}
      </>}
    </Space>}
  </Modal>;
}
