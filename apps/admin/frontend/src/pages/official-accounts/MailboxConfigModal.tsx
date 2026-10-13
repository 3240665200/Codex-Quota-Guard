import { Alert, App as AntApp, Button, Col, Form, Input, InputNumber, Modal,
  Popconfirm, Row, Select, Space, Spin, Switch, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import { deleteOfficialAccountMailbox, getOfficialAccountMailbox, listMailboxProviders,
  saveOfficialAccountMailbox, testOfficialAccountMailbox } from '../../services/api';
import type { MailboxAuthMode, MailboxConnectionTest, MailboxProvider,
  OfficialAccountMailbox, OfficialAccountMailboxInput, OfficialAccountResponse } from '../../services/api';

const authLabels: Record<MailboxAuthMode, string> = {
  PASSWORD: '邮箱密码 / 授权码', GOOGLE_OAUTH2: 'Google OAuth 2.0',
  MICROSOFT_OAUTH2: 'Microsoft OAuth 2.0',
};
const secretNames = ['password', 'oauthClientSecret', 'oauthRefreshToken'] as const;

type FormValues = OfficialAccountMailboxInput & { secondSenderEmail: string };

function valuesFor(config: OfficialAccountMailbox, providers: MailboxProvider[]): FormValues {
  const provider = providers.find((item) => item.id === config.provider)
    ?? providers.find((item) => item.id === 'CUSTOM') ?? providers[0];
  const senders = (config.senderEmail ?? '').split(/[,;，；\r\n]+/).map((value) => value.trim()).filter(Boolean);
  return {
    enabled: config.configured && config.enabled,
    provider: config.provider ?? provider?.id ?? '', host: config.host ?? provider?.host ?? '',
    port: config.port ?? provider?.port ?? 993, username: config.username ?? '',
    recipientEmail: config.recipientEmail ?? '', folder: config.folder ?? 'INBOX',
    senderEmail: senders[0] ?? '', secondSenderEmail: senders.slice(1).join(','),
    authMode: config.authMode ?? provider?.authModes[0] ?? 'PASSWORD',
    oauthClientId: config.oauthClientId ?? '', oauthTenant: config.oauthTenant ?? '',
    password: '', oauthClientSecret: '', oauthRefreshToken: '',
  };
}

export default function MailboxConfigModal({ account, onClose }: {
  account: OfficialAccountResponse; onClose: () => void;
}) {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<FormValues>();
  const [providers, setProviders] = useState<MailboxProvider[]>([]);
  const [config, setConfig] = useState<OfficialAccountMailbox | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState('');
  const [testResult, setTestResult] = useState<MailboxConnectionTest | null>(null);
  const active = useRef(true);
  const selectedProvider = Form.useWatch('provider', form);
  const selectedAuth = Form.useWatch('authMode', form);
  const enabled = Form.useWatch('enabled', form);
  const provider = providers.find((item) => item.id === selectedProvider);
  const busy = loading || saving || deleting;

  const validateSender = async (_rule: unknown, value?: string) => {
    const address = value?.trim() ?? '';
    if (address && /[\s*?<>()\[\]\\",;，；\x00-\x1f\x7f]/.test(address)) {
      throw new Error('请输入完整发件邮箱，每个输入框只填一个地址');
    }
  };
  const validateSenderLength = async () => {
    const addresses = [form.getFieldValue('senderEmail'), form.getFieldValue('secondSenderEmail')]
      .map((item: string | undefined) => item?.trim() ?? '').filter(Boolean);
    if (addresses.join(',').length > 320) throw new Error('发件地址总长度不能超过 320 个字符');
  };

  const clearSecrets = () => form.setFieldsValue({ password: '', oauthClientSecret: '', oauthRefreshToken: '' });
  const close = () => {
    active.current = false;
    clearSecrets();
    form.resetFields();
    onClose();
  };

  useEffect(() => {
    active.current = true;
    let cancelled = false;
    void Promise.all([listMailboxProviders(), getOfficialAccountMailbox(account.id)])
      .then(([presets, saved]) => {
        if (cancelled || !active.current) return;
        setProviders(presets);
        setConfig(saved);
        form.setFieldsValue(valuesFor(saved, presets));
      }).catch((reason) => {
        if (!cancelled && active.current) setError(getErrorMessage(reason));
      }).finally(() => {
        if (!cancelled && active.current) setLoading(false);
      });
    return () => {
      cancelled = true;
      active.current = false;
      form.resetFields();
    };
  }, [account.id, form]);

  const save = async () => {
    if (!config || (enabled && !config.encryptionReady)) return;
    let values: FormValues;
    try { values = await form.validateFields(); } catch { return; }
    const body: OfficialAccountMailboxInput = {
      enabled: values.enabled, provider: values.provider, host: values.host.trim(), port: values.port,
      username: values.username.trim(), recipientEmail: values.recipientEmail.trim(),
      folder: values.folder.trim(),
      senderEmail: [values.senderEmail, values.secondSenderEmail].map((value) => value?.trim() ?? '').filter(Boolean).join(','),
      authMode: values.authMode,
      oauthClientId: values.authMode === 'PASSWORD' ? '' : values.oauthClientId.trim(),
      oauthTenant: values.authMode === 'MICROSOFT_OAUTH2' ? values.oauthTenant.trim() : '',
    };
    const relevantSecrets = values.authMode === 'PASSWORD' ? ['password'] as const
      : ['oauthClientSecret', 'oauthRefreshToken'] as const;
    for (const name of relevantSecrets) {
      if (values[name]) body[name] = values[name];
    }
    setSaving(true);
    setError('');
    setTestResult(null);
    try {
      const saved = await saveOfficialAccountMailbox(account.id, body);
      if (!active.current) return;
      setConfig(saved);
      form.setFieldsValue(valuesFor(saved, providers));
      message.success('邮箱配置已保存');
    } catch (reason) {
      if (active.current) setError(getErrorMessage(reason));
    } finally {
      // The request and Form store must not retain typed secrets after submission.
      for (const name of secretNames) delete values[name];
      for (const name of secretNames) delete body[name];
      if (active.current) { clearSecrets(); setSaving(false); }
    }
  };

  const testConnection = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const result = await testOfficialAccountMailbox(account.id);
      if (active.current) setTestResult(result);
    } catch (reason) {
      if (active.current) setTestResult({ success: false, message: getErrorMessage(reason) });
    } finally {
      if (active.current) setTesting(false);
    }
  };

  const remove = async () => {
    setDeleting(true);
    try {
      await deleteOfficialAccountMailbox(account.id);
      if (active.current) { message.success('邮箱配置已删除'); close(); }
    } catch (reason) {
      if (active.current) setError(getErrorMessage(reason));
    } finally {
      if (active.current) setDeleting(false);
    }
  };

  const sameAuthorization = config?.configured && config.provider === selectedProvider
    && config.authMode === selectedAuth;
  const secretHelp = (saved: boolean | undefined) => sameAuthorization && saved
    ? '已加密保存；留空保持现有授权。' : '请输入本邮箱的授权信息；服务端会加密保存。';

  return <Modal open title={`邮箱配置 · ${account.label}`} width={720} onCancel={close}
    destroyOnHidden styles={{ body: { maxHeight: '70vh', overflowY: 'auto' } }}
    footer={<Space wrap>
      {config?.configured ? <Popconfirm title="删除此账户的邮箱配置？"
        description="删除后不再代收登录码，不影响官方账户登记。"
        okText="确认删除" cancelText="取消" onConfirm={() => void remove()}>
        <Button danger loading={deleting} disabled={saving || testing}>删除邮箱配置</Button>
      </Popconfirm> : null}
      <Button disabled={!config?.configured || busy} loading={testing} onClick={() => void testConnection()}>测试已保存配置</Button>
      <Button onClick={close}>取消</Button>
      <Button type="primary" loading={saving} disabled={busy || testing || !config || (enabled && !config.encryptionReady)}
        onClick={() => void save()}>保存</Button>
    </Space>}>
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Alert type="info" showIcon title="连接管理员自有邮箱代收登录码"
        description="邮箱授权由后台加密保存；浏览器不保存邮箱密钥。这里不托管官方账户的会话凭证，也不自动登录官方账户。" />
      {error ? <Alert type="error" showIcon title="邮箱配置操作失败" description={error} /> : null}
      {config && !config.encryptionReady ? <Alert type="warning" showIcon
        title="服务端缺少邮箱加密密钥，暂时不能启用并保存邮箱配置。" /> : null}
      {testResult ? <Alert showIcon type={testResult.success ? 'success' : 'error'} title={testResult.message} /> : null}
      <Spin spinning={loading}>
        <Form form={form} layout="vertical" preserve={false} disabled={busy}>
          <Form.Item name="enabled" label="启用代收登录码" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="provider" label="邮箱服务商" rules={[{ required: true, message: '请选择邮箱服务商' }]} extra={provider?.help}>
            <Select virtual={false} options={providers.map((item) => ({ value: item.id, label: item.label }))}
              onChange={(id) => {
                const preset = providers.find((item) => item.id === id);
                clearSecrets();
                setTestResult(null);
                form.setFieldsValue({ host: preset?.host ?? '', port: preset?.port ?? 993,
                  authMode: preset?.authModes[0] ?? 'PASSWORD', oauthClientId: '', oauthTenant: '' });
              }} />
          </Form.Item>
          <Row gutter={16}>
            <Col span={18}><Form.Item name="host" label="IMAP 主机" rules={[{ required: true, message: '请输入 IMAP 主机' }]}>
              <Input maxLength={253} disabled={selectedProvider !== 'CUSTOM'} />
            </Form.Item></Col>
            <Col span={6}><Form.Item name="port" label="TLS 端口" rules={[{ required: true, message: '请输入 TLS 端口' }]}>
              <InputNumber min={1} max={65535} precision={0} style={{ width: '100%' }} />
            </Form.Item></Col>
          </Row>
          <Typography.Paragraph type="secondary">使用 IMAP over TLS 加密连接，常用端口为 993。</Typography.Paragraph>
          <Form.Item name="username" label="邮箱登录用户名" rules={[{ required: true, whitespace: true, message: '请输入邮箱登录用户名' }]}>
            <Input maxLength={320} autoComplete="off" />
          </Form.Item>
          <Row gutter={16}>
            <Col span={16}><Form.Item name="recipientEmail" label="收件邮箱" rules={[
              { required: true, message: '请输入收件邮箱' }, { type: 'email', message: '请输入有效的收件邮箱' }]}>
              <Input maxLength={320} />
            </Form.Item></Col>
            <Col span={8}><Form.Item name="folder" label="邮件文件夹" rules={[{ required: true, whitespace: true, message: '请输入邮件文件夹' }]}>
              <Input maxLength={120} />
            </Form.Item></Col>
          </Row>
          <Typography.Paragraph type="secondary">
            填写邮件详情中的完整发件邮箱；两个地址同时生效，任意一个精确匹配即可，不支持域名或通配符。
          </Typography.Paragraph>
          <Row gutter={16}>
            <Col xs={24} sm={12}><Form.Item name="senderEmail" label="发件地址 1" dependencies={['secondSenderEmail']} validateFirst
              rules={[{ required: true, whitespace: true, message: '请输入真实发件邮箱' },
                { type: 'email', transform: (value: string) => value?.trim(), message: '请输入完整发件邮箱' },
                { validator: validateSender }, { validator: validateSenderLength }]}>
              <Input maxLength={320} placeholder="邮件详情中的完整发件地址" />
            </Form.Item></Col>
            <Col xs={24} sm={12}><Form.Item name="secondSenderEmail" label="发件地址 2" validateFirst
              extra="可选；没有第二个地址时留空。"
              rules={[{ type: 'email', transform: (value: string) => value?.trim(), message: '请输入完整发件邮箱' },
                { validator: validateSender }]}>
              <Input maxLength={320} placeholder="另一个已确认的发件地址（可选）" />
            </Form.Item></Col>
          </Row>
          <Form.Item name="authMode" label="认证方式" rules={[{ required: true, message: '请选择认证方式' }]}>
            <Select options={(provider?.authModes ?? []).map((mode) => ({ value: mode, label: authLabels[mode] }))}
              onChange={() => { clearSecrets(); setTestResult(null); }} />
          </Form.Item>
          {selectedAuth === 'PASSWORD' ? <Form.Item name="password" label="邮箱密码 / 授权码"
            extra={secretHelp(config?.hasPassword)} rules={[{ required: !(sameAuthorization && config?.hasPassword), message: '请输入邮箱密码或授权码' }]}>
            <Input.Password autoComplete="new-password" maxLength={4096} />
          </Form.Item> : <>
            <Alert type="info" showIcon title="使用管理员自己的 OAuth 应用"
              description="请在对应服务商创建并授权自己的 OAuth 应用，填写 Client ID、Client Secret（如应用需要）和已获取的 Refresh Token；此页面不会发起授权或代建 OAuth 应用。" />
            <Form.Item name="oauthClientId" label="OAuth Client ID" rules={[{ required: true, whitespace: true, message: '请输入 OAuth Client ID' }]}>
              <Input autoComplete="off" maxLength={320} />
            </Form.Item>
            {selectedAuth === 'MICROSOFT_OAUTH2' ? <Form.Item name="oauthTenant" label="Microsoft Tenant"
              extra="填写组织的 Tenant ID 或服务商允许的租户名称。" rules={[{ required: true, whitespace: true, message: '请输入 Microsoft Tenant' }]}>
              <Input maxLength={120} placeholder="例如：租户 ID 或 common" />
            </Form.Item> : null}
            <Form.Item name="oauthClientSecret" label="OAuth Client Secret" extra={secretHelp(config?.hasClientSecret)}>
              <Input.Password autoComplete="new-password" maxLength={4096} />
            </Form.Item>
            <Form.Item name="oauthRefreshToken" label="OAuth Refresh Token" extra={secretHelp(config?.hasRefreshToken)}
              rules={[{ required: !(sameAuthorization && config?.hasRefreshToken), message: '请输入 OAuth Refresh Token' }]}>
              <Input.Password autoComplete="new-password" maxLength={16384} />
            </Form.Item>
          </>}
        </Form>
      </Spin>
    </Space>
  </Modal>;
}
