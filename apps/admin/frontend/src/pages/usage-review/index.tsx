import { ReloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, App, Button, Card, Descriptions, Form, Input, Modal, Select, Space, Table, Tabs, Tag, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { getErrorMessage } from '../../components/AdminState';
import { listUsers, type UserResponse } from '../../services/api';
import { confirmBillingMode, listReviews, reconcilePrices, reviewUsage, listReviewJobs, previewReviewJob, startReviewJob, type ReviewBulkJob, type PendingBill, type ReviewItem } from '../../services/usageReviewApi';
import { activeJob, BulkJobs } from './bulk-jobs';
import { reviewDatePreset, reviewDateQuery, type ReviewDates } from './review-dates';

const reasons: Record<string, string> = { TOO_OLD: '超过 30 天', FUTURE_TIME: '时间超前', CLIENT_DEFERRED: '设备延后提交' };
const states: Record<string, string> = { PENDING: '待核对', ARCHIVED: '已归档 · 不计费', IMPORTED: '已按原始时间入账' };
const costs: Record<string, string> = { MISSING_PRICE: '缺少模型价格', MISSING_BREAKDOWN: '缺少 Token 分项', INVALID_BREAKDOWN: 'Token 分项不一致', MISSING_SERVICE_TIER: '待确认速度模式' };
const date = (at: string | null) => at ? new Date(at).toLocaleString('zh-CN') : '—';
const count = (n: number | null) => n === null ? '未记录' : n.toLocaleString('zh-CN');
type Row = ReviewItem | PendingBill;
type Action = 'ARCHIVE' | 'IMPORT' | 'standard' | 'fast';
export default function UsageReviewPage() {
  const { message } = App.useApp();
  const [tab, setTab] = useState('review');
  const [status, setStatus] = useState('PENDING');
  const [userId, setUserId] = useState<string>();
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [rows, setRows] = useState<Row[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<React.Key[]>([]);
  const [allMatching, setAllMatching] = useState(false);
  const [dates, setDates] = useState<ReviewDates>({ start: '', end: '' });
  const [timeQuery, setTimeQuery] = useState<{ from?: string; to?: string }>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<ReviewItem>();
  const [action, setAction] = useState<Action>();
  const [actionIds, setActionIds] = useState<string[]>([]);
  const [draftJob, setDraftJob] = useState<ReviewBulkJob>();
  const [bulkAction, setBulkAction] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState('');
  const [jobs, setJobs] = useState<ReviewBulkJob[]>([]);
  const [jobsError, setJobsError] = useState('');
  const [form] = Form.useForm<{ note: string }>();
  const generation = useRef(0);
  const userGeneration = useRef(0);
  const actionGeneration = useRef(0);
  const jobsRef = useRef(jobs); jobsRef.current = jobs;
  const billing = tab === 'billing';
  const load = useCallback(async () => {
    const version = ++generation.current;
    setLoading(true); setError(''); setSelected([]);
    try {
      const result = await listReviews({ page, size, status, userId, ...timeQuery }, billing);
      if (generation.current === version) { setRows(result.items); setTotal(result.totalElements); }
    } catch (failure) { if (generation.current === version) { setRows([]); setError(getErrorMessage(failure)); } }
    finally { if (generation.current === version) setLoading(false); }
  }, [page, size, status, userId, billing, timeQuery]);
  useEffect(() => { void load(); return () => { generation.current++; }; }, [load]);
  const searchUsers = useCallback(async (q: string) => {
    const version = ++userGeneration.current;
    try { const found = await listUsers({ q, size: 50 }); if (version === userGeneration.current) setUsers(found.items); }
    catch (failure) { void message.error(getErrorMessage(failure)); }
  }, [message]);
  useEffect(() => { void searchUsers(''); }, [searchUsers]);
  const loadRef = useRef(load); loadRef.current = load;
  const refreshJobs = useCallback(async () => {
    try {
      const next = await listReviewJobs();
      if (jobsRef.current.some((old) => activeJob(old) && next.some((job) => job.id === old.id && !activeJob(job)))) void loadRef.current();
      setJobs(next); setJobsError('');
    } catch (failure) { setJobsError(getErrorMessage(failure)); }
  }, []);
  useEffect(() => { void refreshJobs(); }, [refreshJobs]);
  const running = jobs.some(activeJob);
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => void refreshJobs(), 3000);
    return () => window.clearInterval(timer);
  }, [running, refreshJobs]);
  function selectDates(next: ReviewDates) {
    try {
      const query = reviewDateQuery(next);
      setDates(next); setTimeQuery(query); setPage(0); setSelected([]);
      if (!billing) { setStatus('PENDING'); setAllMatching(true); }
    } catch (failure) { void message.error(getErrorMessage(failure)); }
  }
  async function openAction(next: Action, bulk = false) {
    const version = ++actionGeneration.current;
    form.resetFields(); setAction(next); setActionIds(selected.map(String));
    setBulkAction(bulk); setDraftJob(undefined); setPreviewError('');
    if (!bulk || next !== 'ARCHIVE' && next !== 'IMPORT') { setPreviewing(false); return; }
    setPreviewing(true);
    try {
      const job = await previewReviewJob(next, { userId, ...timeQuery });
      if (version !== actionGeneration.current) return;
      setDraftJob(job);
      form.setFieldsValue({ note: next === 'ARCHIVE' ? '按所选用户和时间范围批量归档，不计费' : '核对后按原始消耗时间批量入账' });
    } catch (failure) { if (version === actionGeneration.current) setPreviewError(getErrorMessage(failure)); }
    finally { if (version === actionGeneration.current) setPreviewing(false); }
  }
  async function submit() {
    if (!action) return;
    const values = action === 'ARCHIVE' || action === 'IMPORT' ? await form.validateFields().catch(() => undefined) : { note: '' };
    if (!values) return;
    setSaving(true);
    try {
      if (bulkAction) {
        if (!draftJob) return;
        const job = await startReviewJob(draftJob.id, values.note.trim());
        setJobs((previous) => [job, ...previous.filter((old) => old.id !== job.id)]);
        void message.success(`已启动后台任务，共 ${job.totalCount.toLocaleString()} 条记录`);
      } else {
        const result = action === 'ARCHIVE' || action === 'IMPORT'
          ? await reviewUsage(actionIds, action, values.note.trim()) : await confirmBillingMode(actionIds, action);
        void message.success(`已处理 ${result.updated} 条记录`);
      }
      setAction(undefined); setAllMatching(false); await load();
    } catch (failure) { void message.error(getErrorMessage(failure)); }
    finally { setSaving(false); }
  }
  async function retryPrices() {
    setSaving(true);
    try { const result = await reconcilePrices(); void message.success(`本次重新核算 ${result.updated} 条记录`); await load(); }
    catch (failure) { void message.error(getErrorMessage(failure)); }
    finally { setSaving(false); }
  }
  const selectedCanConfirm = selected.length > 0 && rows.filter((row) => selected.includes(row.id))
    .every((row) => 'modeStatus' in row && row.modeStatus === 'UNRECORDED');
  return <PageContainer title="用量核对" subTitle="处理异常上传和缺少计费信息的记录">
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Alert type="info" showIcon title="正常用量自动入账，无需逐条人工核对。" description="时间异常的记录由新版统计程序分批提交到待审区，收件不计费。管理员可批量归档，或确认后按原始消耗时间入账；缺少价格、分项或速度模式的记录仍会保持待核算。" />
      <Card>
        <Tabs activeKey={tab} onChange={(next) => { setTab(next); setPage(0); setSelected([]); setAllMatching(false); }} items={[
          { key: 'review', label: '异常上传待审' }, { key: 'billing', label: '计费信息待核算' },
        ]} />
        <Space wrap style={{ marginBottom: 16 }}>
          <Select aria-label="筛选用户" style={{ width: 240 }} allowClear showSearch filterOption={false}
            placeholder="搜索用户姓名或邮箱" value={userId} onSearch={(q) => void searchUsers(q)}
            onChange={(id) => { setUserId(id); setPage(0); setSelected([]); setAllMatching(false); }} options={users.map((u) => ({ value: u.id, label: `${u.displayName} · ${u.email}` }))} />
          {!billing && <Select aria-label="核对状态" style={{ width: 180 }} value={status} onChange={(s) => { setStatus(s); setPage(0); setSelected([]); setAllMatching(false); }}
            options={[{ value: 'ALL', label: '全部状态' }, ...Object.entries(states).map(([value, label]) => ({ value, label }))]} />}
          <Button icon={<ReloadOutlined />} loading={loading} disabled={saving} onClick={() => void load()}>刷新核对列表</Button>
        </Space>
        <div className="cqg-review-date-filter">
          <label>原始消耗时间 · 从<Input aria-label="核对开始时间" type="datetime-local" step="1" value={dates.start} onChange={(event) => setDates((old) => ({ ...old, start: event.target.value }))} /></label>
          <label>到（包含该秒）<Input aria-label="核对结束时间" type="datetime-local" step="1" value={dates.end} onChange={(event) => setDates((old) => ({ ...old, end: event.target.value }))} /></label>
          <Button disabled={loading || saving} onClick={() => selectDates(dates)}>{billing ? '按时间筛选' : '按时间选择全部匹配'}</Button>
        </div>
        <Space wrap style={{ marginBottom: 16 }}>
          <Typography.Text type="secondary">快捷选择（本机时区）：</Typography.Text>
          {([['today', '今天'], ['yesterday', '昨天'], ['week', '近 7 天'], ['month', '近 30 天'], ['older', '30 天前'], ['all', '全部时间']] as const).map(([key, label]) =>
            <Button key={key} size="small" disabled={saving} onClick={() => selectDates(reviewDatePreset(key))}>{label}</Button>)}
        </Space>
        {error && <Alert type="error" showIcon title={error} style={{ marginBottom: 16 }} />}
        {allMatching && !billing && <Alert type="info" showIcon style={{ marginBottom: 12 }} title={`已跨页选择当前筛选的全部 ${total.toLocaleString()} 条待核对记录`} description="点击归档或入账时会固定本次记录范围，并在确认后由后台处理。" />}
        <Space wrap style={{ display: 'flex', marginBottom: 16 }}>
          <Typography.Text>共 {total.toLocaleString()} 条 · {allMatching ? `跨页已选 ${total.toLocaleString()} 条` : `本页已选 ${selected.length} 条`}</Typography.Text>
          {billing ? <>
            <Button disabled={!selectedCanConfirm || saving || loading} onClick={() => openAction('standard')}>确认普通模式 ×1</Button>
            <Button disabled={!selectedCanConfirm || saving || loading} onClick={() => openAction('fast')}>确认快速模式 ×1.5</Button>
            <Button loading={saving} disabled={loading} onClick={() => void retryPrices()}>按现有价格重新核算</Button>
          </> : <>
            <Button disabled={status !== 'PENDING' || total === 0 || saving || loading || previewing} onClick={() => void openAction('ARCHIVE', true)}>一键归档全部匹配</Button>
            <Button type="primary" disabled={status !== 'PENDING' || total === 0 || saving || loading || previewing} onClick={() => void openAction('IMPORT', true)}>一键入账全部匹配</Button>
            <Button disabled={!selected.length || saving || loading || allMatching} onClick={() => void openAction('ARCHIVE')}>批量归档（不计费）</Button>
            <Button disabled={!selected.length || saving || loading || allMatching} onClick={() => void openAction('IMPORT')}>按原始时间入账</Button>
            <Button disabled={status !== 'PENDING' || !total || loading || saving} onClick={() => { setAllMatching(true); setSelected([]); }}>跨页全选</Button>
            <Button disabled={!selected.length && !allMatching || saving} onClick={() => { setSelected([]); setAllMatching(false); }}>取消选择</Button>
          </>}
        </Space>
        <Table<Row> rowKey="id" dataSource={rows} loading={loading} scroll={{ x: 1050 }}
          rowSelection={{ selectedRowKeys: allMatching ? rows.filter((row) => 'status' in row && row.status === 'PENDING').map((row) => row.id) : selected,
            onChange: (keys) => { setAllMatching(false); setSelected(keys); }, preserveSelectedRowKeys: false,
            getCheckboxProps: (row) => ({ disabled: saving || ('status' in row && row.status !== 'PENDING') }) }}
          pagination={{ current: page + 1, pageSize: size, total, showSizeChanger: true, pageSizeOptions: [20, 50, 100],
            onChange: (next, nextSize) => { setPage(nextSize === size ? next - 1 : 0); setSize(nextSize); setSelected([]); } }}
          columns={[
            { title: '用户', width: 210, render: (_, row) => <Space orientation="vertical" size={0}><b>{row.displayName}</b><Typography.Text type="secondary">{row.email}</Typography.Text></Space> },
            { title: '原始消耗时间', dataIndex: 'occurredAt', width: 190, render: date },
            { title: '模型 / Token', width: 180, render: (_, row) => { const e = 'event' in row ? row.event : row; return <Space orientation="vertical" size={0}><span>{e.model ?? '未记录模型'}</span><span>{count(e.totalTokens)} Token</span></Space>; } },
            { title: billing ? '待核算原因' : '核对原因', width: 180, render: (_, row) => 'reason' in row ? <Tag color="warning">{reasons[row.reason]}</Tag> : <Space orientation="vertical" size={0}><Tag color="warning">{costs[row.costStatus] ?? row.costStatus}</Tag>{row.modeStatus === 'UNRECORDED' && <Typography.Text type="warning">速度模式未记录</Typography.Text>}</Space> },
            { title: billing ? '说明' : '状态 / 收件时间', width: 230, render: (_, row) => 'status' in row ? <Space orientation="vertical" size={0}><Tag>{states[row.status]}</Tag><span>{date(row.receivedAt)}</span></Space> : <Typography.Text type="secondary">仅确认有依据的模式；分项缺失时不自动推算费用。</Typography.Text> },
            { title: '操作', width: 95, render: (_, row) => 'event' in row && <Button type="link" onClick={() => setDetail(row)}>查看详情</Button> },
          ]} />
      </Card>
      <BulkJobs jobs={jobs} error={jobsError} refresh={() => void refreshJobs()} />
    </Space>
    <Modal title={action === 'ARCHIVE' ? '归档所选记录' : action === 'IMPORT' ? '确认按原始时间入账' : '确认速度模式'}
      open={!!action} confirmLoading={saving} okText={bulkAction ? '确认并后台处理' : '确认处理'} cancelText="取消" closable={!saving} mask={{ closable: !saving }}
      okButtonProps={{ disabled: previewing || bulkAction && !draftJob }}
      onCancel={() => { if (!saving) { actionGeneration.current++; setAction(undefined); setPreviewing(false); } }} onOk={() => void submit()}>
      {previewing && <Alert type="info" showIcon title="正在固定跨页记录范围…" style={{ marginBottom: 12 }} />}
      {previewError && <Alert type="error" showIcon title={previewError} style={{ marginBottom: 12 }} />}
      {draftJob && <Alert type="info" showIcon title={`已固定 ${draftJob.totalCount.toLocaleString()} 条记录，包含所有匹配分页`} style={{ marginBottom: 12 }}
        description={`处理开始后由服务器继续执行，不受网页关闭影响。新上传记录不在本次范围内。${draftJob.excludedCount ? `已排除 ${draftJob.excludedCount.toLocaleString()} 条未来时间记录。` : ''}`} />}
      <Alert type="warning" showIcon style={{ marginBottom: 16 }} title={bulkAction ? '请确认本次跨页操作' : `即将处理 ${actionIds.length} 条记录`} description={
        action === 'ARCHIVE' ? '原始记录与核对备注继续保留，不产生计费。后续重复上传不会自动入账。'
          : action === 'IMPORT' ? '保留原始消耗时间，归属使用收件时保存的用户与团队配置。历史记录可能不在当前额度窗口内；缺少模型、价格、分项或模式的记录进入“计费信息待核算”。未来时间记录不能入账。'
            : `请仅在有日志或设备操作依据时确认${action === 'fast' ? '快速模式（按价格表快速倍率，默认 ×1.5）' : '普通模式（×1）'}。已入账记录不会被改写。`} />
      {action === 'ARCHIVE' || action === 'IMPORT' ? <Form form={form} layout="vertical"><Form.Item label="核对依据 / 处理备注" name="note" rules={[{ required: true, whitespace: true, message: '请填写核对依据或归档原因' }, { max: 500 }]}><Input.TextArea maxLength={500} rows={3} /></Form.Item></Form> : null}
    </Modal>
    <Modal title="用量核对详情" open={!!detail} onCancel={() => setDetail(undefined)} footer={null} width={680}>
      {detail && <Descriptions column={1} size="small" bordered items={[
        { key: 'u', label: '用户', children: `${detail.displayName} · ${detail.email}` },
        { key: 'device', label: '设备标识', children: detail.deviceId },
        { key: 'occurred', label: '原始消耗时间', children: date(detail.occurredAt) },
        { key: 'received', label: '后台收件时间', children: date(detail.receivedAt) },
        { key: 'reason', label: '核对原因', children: reasons[detail.reason] },
        { key: 'model', label: '模型', children: detail.event.model ?? '未记录' },
        { key: 'tier', label: '速度模式', children: detail.event.serviceTier ?? '未记录，不自动假设普通模式' },
        { key: 'source', label: '来源', children: detail.event.source },
        ...([['inputTokens', '输入（含缓存）'], ['cachedInputTokens', '缓存读'], ['cacheWriteInputTokens', '缓存写'], ['outputTokens', '输出（含思考）'], ['reasoningOutputTokens', '思考']] as const).map(([key, label]) => ({ key, label, children: count(detail.event[key]) })),
        { key: 'status', label: '处理状态', children: states[detail.status] },
        { key: 'reviewed', label: '处理时间', children: date(detail.reviewedAt) },
        { key: 'note', label: '处理备注', children: detail.reviewNote ?? '—' },
      ]} />}
    </Modal>
  </PageContainer>;
}
