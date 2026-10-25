import { ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, App, Button, Card, Col, Empty, Form, InputNumber, Row, Space, Switch, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { getErrorMessage } from '../../components/AdminState';
import { getRestrictionManagement, saveRestrictionSettings } from '../../services/restrictionsApi';
import type { RestrictionManagement, RestrictionSettings } from '../../services/restrictionsApi';

const date = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '尚未检查';
const statuses: Record<string, string> = {
  NO_TARGETS: '没有可下线目标', EXECUTING: '执行中', ACCEPTED: '官方已接受请求', PARTIAL: '部分已接受',
  FAILED: '执行失败', UNKNOWN: '结果待核对', SKIPPED: '已延后', INVALIDATED: '已取消', RESOLVED_UNKNOWN: '已人工核对',
};
const errors: Record<string, string> = {
  OFFICIAL_LOGOUT_BUSY: '官方账户正在登录、绑定或处理下线任务，稍后再检查。',
  OFFICIAL_SESSION_LOGOUT_BUSY: '该账户有待完成或待核对的下线任务。',
  OFFICIAL_LOGOUT_OUTCOME_UNKNOWN: '官方执行结果不明确，请到会话管理核对；后台不会重复发送。',
  OFFICIAL_LOGOUT_CONTEXT_CHANGED: '规则、额度、绑定或设备状态已变化，本次未继续执行。',
  OFFICIAL_RATE_LIMITED: '官方接口限流，后台已延后检查。',
  OFFICIAL_LOGOUT_CONNECTION_UNAVAILABLE: '官方账户连接不可用，请在账户管理中核对授权。',
  RESTRICTION_CHECK_FAILED: '本次检查失败，后台会稍后重新检查。',
  RESTRICTION_LOGOUT_FAILED: '下线请求未完成，已进入冷却期。',
  RESTRICTION_NO_ELIGIBLE_SESSION: '命中额度条件，但官方目标会话已缺失、受保护或状态不明确，稍后再核对。',
};
const errorText = (code: string | null) => code ? errors[code] ?? '检查暂未完成，请到官方账户核对连接及会话状态。' : '—';

export default function RestrictionsPage() {
  const { message } = App.useApp();
  const [form] = Form.useForm<RestrictionSettings>();
  const [data, setData] = useState<RestrictionManagement | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const version = useRef(0);
  const cleanupEnabled = Form.useWatch('unboundCleanupEnabled', form);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const next = await getRestrictionManagement();
      setData(next); version.current = next.policy.version;
      form.setFieldsValue(next.policy); setError('');
    } catch (failure) { setError(getErrorMessage(failure, '限制管理加载失败，请重试。')); }
    finally { setLoading(false); }
  }, [form]);
  useEffect(() => { void load(); }, [load]);
  const save = async () => {
    let values: RestrictionSettings;
    try { values = await form.validateFields(); } catch { return; }
    setSaving(true);
    try {
      const next = await saveRestrictionSettings({
        personalQuotaLogoutEnabled: values.personalQuotaLogoutEnabled,
        unboundCleanupEnabled: values.unboundCleanupEnabled,
        unboundCheckIntervalMinutes: values.unboundCheckIntervalMinutes,
        version: version.current,
      });
      setData(next); version.current = next.policy.version; form.setFieldsValue(next.policy); setError('');
      message.success('限制设置已保存，服务器将按规则自动检查。');
    } catch (failure) { setError(getErrorMessage(failure, '限制设置保存失败，请刷新后重试。')); }
    finally { setSaving(false); }
  };
  return <PageContainer title="限制管理" content="配置官方会话的自动下线规则，检查由服务器持续执行。"
    extra={<Button aria-label="刷新设置与状态" icon={<ReloadOutlined />} loading={loading} disabled={saving} onClick={() => void load()}>刷新设置与状态</Button>}>
    <Space orientation="vertical" size={20} style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon title={error} />}
      <Form form={form} layout="vertical" disabled={loading || saving || !data}>
        <Row gutter={[20, 20]}>
          <Col xs={24} lg={12}><Card title={<Space><SafetyCertificateOutlined />个人额度限制</Space>} loading={loading && !data}>
            <Form.Item name="personalQuotaLogoutEnabled" label="个人额度用尽后下线绑定会话" valuePropName="checked">
              <Switch aria-label="个人额度用尽后下线绑定会话" checkedChildren="已启用" unCheckedChildren="已关闭" />
            </Form.Item>
            <Typography.Paragraph type="secondary">服务器每 {data?.policy.quotaCheckIntervalSeconds ?? 30} 秒检查个人 5 小时和 7 天额度，达到任一已设置上限后，对该用户精确绑定的官方会话发起下线请求。留空即无限制，填写 0 表示额度用尽。</Typography.Paragraph>
            <Typography.Text type="secondary">当前官方管理会话和身份状态不明确的目标会保留。</Typography.Text>
          </Card></Col>
          <Col xs={24} lg={12}><Card title="未绑定会话清理" loading={loading && !data}>
            <Form.Item name="unboundCleanupEnabled" label="定时下线未绑定的官方会话" valuePropName="checked">
              <Switch aria-label="定时下线未绑定的官方会话" checkedChildren="已启用" unCheckedChildren="已关闭" />
            </Form.Item>
            <Form.Item name="unboundCheckIntervalMinutes" label="检查间隔（分钟）" rules={[{ required: true, type: 'number', min: 30, max: 1440, message: '请输入 30～1440 分钟的整数。' }]} extra="默认 90 分钟；启用后按此间隔检查。">
              <InputNumber aria-label="未绑定会话检查间隔" min={30} max={1440} precision={0} step={15} disabled={!cleanupEnabled || loading || saving || !data} style={{ width: '100%' }} />
            </Form.Item>
            <Typography.Text type="secondary">保留官方标记的当前设备、受信任设备、有效绑定和状态不明确的会话。官方账户正处于登录或绑定流程时，检查会延后。</Typography.Text>
          </Card></Col>
        </Row>
        <Button type="primary" loading={saving} disabled={loading || !data} onClick={() => void save()} style={{ marginTop: 20 }}>保存限制设置</Button>
      </Form>
      <Card title="账户检查状态">
        <Table rowKey="officialAccountId" loading={loading} dataSource={data?.accounts ?? []} pagination={false} scroll={{ x: 900 }}
          locale={{ emptyText: <Empty description="暂无已配置官方连接的账户" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} columns={[
            { title: '官方账户', dataIndex: 'accountLabel', width: 150 },
            { title: '个人额度检查', width: 225, render: (_, row) => <Space orientation="vertical" size={2}><span>最近：{date(row.quotaCheckedAt)}</span><Typography.Text type="secondary">下次：{data?.policy.personalQuotaLogoutEnabled ? date(row.nextQuotaAt) : '未启用'}</Typography.Text></Space> },
            { title: '未绑定会话检查', width: 225, render: (_, row) => <Space orientation="vertical" size={2}><span>最近：{date(row.unboundCheckedAt)}</span><Typography.Text type="secondary">下次：{data?.policy.unboundCleanupEnabled ? date(row.nextUnboundAt) : '未启用'}</Typography.Text></Space> },
            { title: '执行状态', width: 300, render: (_, row) => <Space orientation="vertical" size={2}>{row.operationStatus && <Tag color={row.operationStatus === 'UNKNOWN' ? 'warning' : 'default'}>{statuses[row.operationStatus] ?? '待核对'}</Tag>}<Typography.Text type={row.lastErrorCode ? 'warning' : 'secondary'}>{errorText(row.lastErrorCode)}</Typography.Text></Space> },
          ]} />
      </Card>
      <Card title="最近执行记录" extra={<Typography.Text type="secondary">最近 20 条</Typography.Text>}>
        <Table rowKey="id" dataSource={data?.executions ?? []} pagination={false} scroll={{ x: 950 }}
          locale={{ emptyText: <Empty description="暂无自动下线记录" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} columns={[
            { title: '检查时间', dataIndex: 'checkedAt', render: date, width: 190 },
            { title: '官方账户', dataIndex: 'accountLabel', width: 140 },
            { title: '触发规则', dataIndex: 'rule', render: (rule: string) => rule === 'PERSONAL_QUOTA' ? '个人额度用尽' : '未绑定会话清理', width: 145 },
            { title: '结果', render: (_, row) => <Space orientation="vertical" size={2}><Tag color={row.status === 'ACCEPTED' ? 'success' : row.status === 'UNKNOWN' || row.status === 'FAILED' ? 'warning' : 'default'}>{statuses[row.status] ?? '待核对'}</Tag><span>命中 {row.targetedCount} · 已接受 {row.acceptedCount} · 排除 {row.excludedCount}</span>{row.unknownCount > 0 && <Typography.Text type="warning">{row.unknownCount} 个结果待核对</Typography.Text>}</Space>, width: 240 },
            { title: '说明', dataIndex: 'errorCode', render: errorText },
          ]} />
      </Card>
    </Space>
  </PageContainer>;
}
