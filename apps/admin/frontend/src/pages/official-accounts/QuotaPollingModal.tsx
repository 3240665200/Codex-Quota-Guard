import { Alert, Button, Form, InputNumber, Modal, Space, Spin, Switch, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { getErrorMessage } from '../../components/AdminState';
import { getOfficialQuotaPolling, refreshOfficialQuota, saveOfficialQuotaPolling } from '../../services/api';
import type { OfficialAccountResponse, OfficialQuotaPolling } from '../../services/api';

export const quotaStatus: Record<string, string> = {
  SUCCESS: '已从官方获取', PENDING: '等待检测', FETCHING: '正在获取', RETRY_WAIT: '等待重试',
  AUTH_REQUIRED: '需要授权验证', DISABLED: '已停用',
};
export function quotaError(code: string | null) {
  switch (code) {
    case 'OFFICIAL_RATE_LIMITED': return '官方暂时限流，已按冷却时间延后重试。';
    case 'OFFICIAL_AUTH_REQUIRED': return '官方授权已失效，请重新授权登录。';
    case 'OFFICIAL_CHALLENGE_REQUIRED': case 'OFFICIAL_ACCESS_BLOCKED': return '官方要求登录或安全验证，请在授权登录中处理后重新保存授权。';
    case 'OFFICIAL_ACCOUNT_MISMATCH': return '官方账户身份不匹配，请检查授权和所选账户。';
    case 'OFFICIAL_PROXY_REQUIRED': case 'OFFICIAL_PROXY_UNAVAILABLE': return '官方账户代理链路不可用，请检查代理设置。';
    case 'OFFICIAL_RESPONSE_INVALID': return '官方额度格式暂时无法识别，保留上次成功结果。';
    case 'OFFICIAL_ENCRYPTION_UNAVAILABLE': case 'OFFICIAL_CREDENTIALS_UNAVAILABLE': return '官方授权无法解密，请检查后台加密配置并重新授权。';
    default: return code ? '官方额度获取失败，已延后重试并保留上次成功结果。' : null;
  }
}
export default function QuotaPollingModal({ account, onClose, onChange }: {
  account: OfficialAccountResponse; onClose: () => void; onChange: () => void;
}) {
  const [form] = Form.useForm<{ enabled: boolean; minutes: number }>();
  const [config, setConfig] = useState<OfficialQuotaPolling | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    void getOfficialQuotaPolling(account.id).then((value) => {
      if (!active) return;
      setConfig(value); form.setFieldsValue({ enabled: value.enabled, minutes: value.intervalSeconds / 60 });
    }, (reason: unknown) => { if (active) setError(getErrorMessage(reason)); });
    return () => { active = false; };
  }, [account.id, form]);
  const save = async () => {
    if (!config || busy) return;
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    setBusy(true); setError('');
    try {
      const value = await saveOfficialQuotaPolling(account.id, { enabled: values.enabled, intervalSeconds: Math.round(values.minutes * 60), version: config.version });
      setConfig(value); onChange();
    } catch (reason) { setError(getErrorMessage(reason)); }
    finally { setBusy(false); }
  };
  const refresh = async () => {
    if (busy) return;
    setBusy(true); setError('');
    try { setConfig(await refreshOfficialQuota(account.id)); onChange(); }
    catch (reason) { setError(getErrorMessage(reason)); }
    finally { setBusy(false); }
  };
  const cooling = !!config?.nextAttemptAt && Date.parse(config.nextAttemptAt) > Date.now();
  return <Modal open title={`官方额度检测 · ${account.label}`} onCancel={onClose} footer={<Space>
    <Button onClick={onClose}>关闭</Button>
    <Button onClick={() => void refresh()} loading={busy} disabled={!config?.enabled || cooling || config?.status === 'FETCHING'}>立即检测</Button>
    <Button type="primary" onClick={() => void save()} loading={busy} disabled={!config}>保存设置</Button>
  </Space>}>
    <Alert type="info" showIcon title="由后台直接查询官方额度" description="复用该账户已验证的官方授权和代理链路。默认每 5 分钟检测，最短 1 分钟；刷新列表只读取缓存，立即检测也遵守冷却和官方限流。" />
    {error ? <Alert className="mt-4" type="error" showIcon title={error} /> : null}
    {!config && !error ? <Spin /> : config ? <>
      <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
        <Form.Item name="enabled" label="自动检测" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="minutes" label="检测时间间隔（分钟）" rules={[{ required: true, message: '请输入检测间隔' }]} extra="可设置 1～1440 分钟；失败会逐步延长重试间隔，授权失效或安全验证会暂停检测。">
          <InputNumber min={1} max={1440} precision={0} />
        </Form.Item>
      </Form>
      <Typography.Paragraph>状态：{quotaStatus[config.status] ?? '等待检测'}</Typography.Paragraph>
      <Typography.Paragraph>下次允许检测：{config.nextAttemptAt ? new Date(config.nextAttemptAt).toLocaleString('zh-CN') : '完成授权后等待检测'}</Typography.Paragraph>
      {quotaError(config.lastErrorCode) ? <Alert showIcon type="warning" title={quotaError(config.lastErrorCode)} /> : null}
    </> : null}
  </Modal>;
}
