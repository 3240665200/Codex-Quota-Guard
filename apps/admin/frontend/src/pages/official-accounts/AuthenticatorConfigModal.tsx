import { Alert, Button, Form, Input, Modal, Popconfirm, Space, Spin, Switch, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { getErrorMessage } from '../../components/AdminState';
import { deleteOfficialAuthenticator, getOfficialAuthenticator, saveOfficialAuthenticator } from '../../services/api';
import type { OfficialAccountResponse, OfficialAuthenticator } from '../../services/api';

export default function AuthenticatorConfigModal({ account, onClose }: { account: OfficialAccountResponse; onClose: () => void }) {
  const [form] = Form.useForm<{ enabled: boolean; secret: string }>();
  const [config, setConfig] = useState<OfficialAuthenticator | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    void getOfficialAuthenticator(account.id).then((value) => {
      if (active) { setConfig(value); form.setFieldsValue({ enabled: value.enabled || !value.configured, secret: '' }); }
    }, (reason: unknown) => { if (active) setError(getErrorMessage(reason)); });
    return () => { active = false; form.resetFields(); };
  }, [account.id, form]);
  const close = () => { form.resetFields(); onClose(); };
  const save = async () => {
    if (!config || busy) return;
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    setBusy(true); setError('');
    try {
      const value = await saveOfficialAuthenticator(account.id, { enabled: values.enabled, secret: values.secret?.trim() || undefined, version: config.version });
      setConfig(value);
    } catch (reason) { setError(getErrorMessage(reason)); }
    finally { form.setFieldValue('secret', ''); setBusy(false); }
  };
  const remove = async () => {
    setBusy(true); setError('');
    try { await deleteOfficialAuthenticator(account.id); form.resetFields(); close(); }
    catch (reason) { setError(getErrorMessage(reason)); }
    finally { setBusy(false); }
  };
  return <Modal open title={`Google Authenticator · ${account.label}`} onCancel={close} destroyOnHidden footer={<Space>
    {config?.configured ? <Popconfirm title="移除此账户的验证器配置？" onConfirm={() => void remove()}><Button danger disabled={busy}>移除配置</Button></Popconfirm> : null}
    <Button onClick={close}>关闭</Button><Button type="primary" loading={busy} disabled={!config?.encryptionReady} onClick={() => void save()}>保存</Button>
  </Space>}>
    <Alert showIcon type="info" title="兼容标准 TOTP 一次性验证码" description="请配置此官方账户启用双重验证时的原始密钥，或二维码中的 otpauth://totp 链接。无法从手机上的六位验证码恢复密钥；不支持 Google Authenticator 导出用的迁移二维码。" />
    {error ? <Alert type="error" showIcon title={error} style={{ marginTop: 16 }} /> : null}
    {!config && !error ? <Spin /> : config ? <>
      {!config.encryptionReady ? <Alert type="warning" showIcon title="后台需配置 OFFICIAL_SESSION_ENCRYPTION_KEY 后才能保存验证器密钥。" style={{ marginTop: 16 }} /> : null}
      <Typography.Paragraph style={{ marginTop: 16 }}>{config.configured ? `已配置：${config.algorithm} · ${config.digits} 位 · ${config.periodSeconds} 秒` : '尚未配置验证器'}</Typography.Paragraph>
      <Form form={form} layout="vertical" autoComplete="off">
        <Form.Item name="enabled" label="允许所属团队成员获取验证码" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="secret" label="TOTP 密钥或 otpauth://totp 链接" rules={[{ required: !config.configured, message: '请输入对应账户的验证器密钥' }]}
          extra="默认 SHA1、6 位、30 秒。密钥加密保存、不会回显；已配置时留空保留原密钥。保存或关闭后清空输入。">
          <Input.Password autoComplete="new-password" maxLength={4096} visibilityToggle={false} placeholder={config.configured ? '留空保留，输入则替换' : 'Base32 密钥或 otpauth://totp/…'} />
        </Form.Item>
      </Form>
    </> : null}
  </Modal>;
}
