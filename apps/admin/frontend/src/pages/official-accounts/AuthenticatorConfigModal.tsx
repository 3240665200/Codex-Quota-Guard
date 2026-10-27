import { Alert, Button, Form, Input, Modal, Popconfirm, Progress, Space, Spin, Switch, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { getErrorMessage } from '../../components/AdminState';
import { deleteOfficialAuthenticator, getOfficialAuthenticator, previewOfficialAuthenticator, saveOfficialAuthenticator } from '../../services/api';
import type { OfficialAccountResponse, OfficialAuthenticator, OfficialAuthenticatorCode } from '../../services/api';
import { importAuthenticator } from '../../utils/authenticator-import';
import { readAuthenticatorQr } from '../../utils/authenticator-qr';

export default function AuthenticatorConfigModal({ account, onClose }: { account: OfficialAccountResponse; onClose: () => void }) {
  const [form] = Form.useForm<{ enabled: boolean; secret: string }>();
  const [config, setConfig] = useState<OfficialAuthenticator | null>(null);
  const [pending, setPending] = useState<'save' | 'preview' | 'remove' | null>(null);
  const [importing, setImporting] = useState(false);
  const busy = pending !== null || importing;
  const [error, setError] = useState('');
  const [importedLabel, setImportedLabel] = useState('');
  const [code, setCode] = useState<OfficialAuthenticatorCode | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const live = useRef(false);
  const renewal = useRef<ReturnType<typeof setTimeout> | null>(null);
  const previewAgain = useRef<() => Promise<void>>(async () => {});
  const previewInFlight = useRef(false);
  const lastRequest = useRef(0);
  const roundtrip = useRef(0);
  const expires = useRef(0);
  const epoch = useRef(0);
  const visibilityEpoch = useRef(0);
  const fileInput = useRef<HTMLInputElement>(null);
  const draft = Form.useWatch('secret', form);
  const stopPreview = () => {
    visibilityEpoch.current += 1;
    live.current = false; setAutoRefresh(false); setCode(null);
    if (renewal.current !== null) { clearTimeout(renewal.current); renewal.current = null; }
  };
  useEffect(() => {
    epoch.current += 1;
    live.current = false; setAutoRefresh(false);
    setConfig(null); setError(''); setCode(null); setImportedLabel(''); setPending(null); setImporting(false);
    let active = true;
    void getOfficialAuthenticator(account.id).then((value) => {
      if (active) { setConfig(value); form.setFieldsValue({ enabled: value.enabled || !value.configured, secret: '' }); }
    }, (reason: unknown) => { if (active) setError(getErrorMessage(reason)); });
    return () => { active = false; epoch.current += 1; live.current = false;
      if (renewal.current !== null) clearTimeout(renewal.current); renewal.current = null; form.resetFields(); };
  }, [account.id, form]);
  useEffect(() => {
    const clear = () => { visibilityEpoch.current += 1; stopPreview(); };
    const visibility = () => { if (document.hidden) clear(); };
    window.addEventListener('blur', clear);
    document.addEventListener('visibilitychange', visibility);
    return () => { window.removeEventListener('blur', clear); document.removeEventListener('visibilitychange', visibility); };
  }, []);
  useEffect(() => {
    if (!code) return;
    const clear = () => setCode(null);
    const tick = () => {
      const seconds = Math.max(0, Math.ceil((expires.current - performance.now()) / 1000));
      setRemaining(seconds);
      if (!seconds) {
        clear();
        if (live.current && !document.hidden && renewal.current === null) {
          // Clear the expired code first. Allow for network delay and the one-second server cooldown.
          renewal.current = setTimeout(() => {
            renewal.current = null;
            if (live.current && !document.hidden) void previewAgain.current();
          }, Math.max(100, roundtrip.current + 50, 1000 - (performance.now() - lastRequest.current)));
        }
      }
    };
    tick();
    const timer = window.setInterval(tick, 250);
    return () => { window.clearInterval(timer); };
  }, [code]);
  const close = () => { epoch.current += 1; stopPreview(); setImportedLabel(''); form.resetFields(); onClose(); };
  const importImage = async (file: File) => {
    if (busy) return;
    const operation = epoch.current;
    stopPreview(); setImporting(true); setError(''); setImportedLabel('');
    try {
      const imported = importAuthenticator(await readAuthenticatorQr(file));
      if (operation !== epoch.current) return;
      form.setFieldValue('secret', imported.uri);
      setImportedLabel(imported.label);
    } catch (reason) { if (operation === epoch.current) setError(getErrorMessage(reason)); }
    finally { if (operation === epoch.current) setImporting(false); }
  };
  const save = async () => {
    if (!config || busy) return;
    const operation = epoch.current;
    const values = await form.validateFields().catch(() => null);
    if (!values || operation !== epoch.current) return;
    stopPreview(); setPending('save'); setError('');
    try {
      let secret = values.secret?.trim();
      if (secret?.startsWith('otpauth-migration:')) secret = importAuthenticator(secret).uri;
      const value = await saveOfficialAuthenticator(account.id, { enabled: values.enabled, secret: secret || undefined, version: config.version });
      if (operation === epoch.current) { setConfig(value); setImportedLabel(''); }
    } catch (reason) { if (operation === epoch.current) setError(getErrorMessage(reason)); }
    finally { if (operation === epoch.current) { form.setFieldValue('secret', ''); setPending(null); } }
  };
  const preview = async () => {
    if (busy || previewInFlight.current || !config?.configured || draft?.trim() || document.hidden) return;
    const operation = epoch.current, visibility = visibilityEpoch.current, started = performance.now();
    previewInFlight.current = true; lastRequest.current = started; live.current = true; setAutoRefresh(true);
    setPending('preview'); setError(''); setCode(null);
    try {
      const value = await previewOfficialAuthenticator(account.id);
      if (operation !== epoch.current || visibility !== visibilityEpoch.current || document.hidden) return;
      roundtrip.current = performance.now() - started;
      const lifetime = Date.parse(value.expiresAt) - Date.parse(value.serverTime) - roundtrip.current;
      if (value.officialAccountId !== account.id || !/^\d{6}(\d{2})?$/.test(value.code)
        || !Number.isInteger(value.periodSeconds) || value.periodSeconds < 15 || value.periodSeconds > 120
        || !Number.isFinite(lifetime) || lifetime <= 0 || lifetime > value.periodSeconds * 1000) {
        throw new Error('验证码已过期或返回格式不完整，请重新获取。');
      }
      expires.current = performance.now() + lifetime;
      setRemaining(Math.ceil(lifetime / 1000)); setCode(value);
    } catch (reason) { if (operation === epoch.current) { stopPreview(); setError(getErrorMessage(reason)); } }
    finally { previewInFlight.current = false; if (operation === epoch.current) setPending(null); }
  };
  previewAgain.current = preview;
  const remove = async () => {
    const operation = epoch.current;
    stopPreview(); setPending('remove'); setError('');
    try { await deleteOfficialAuthenticator(account.id); if (operation === epoch.current) close(); }
    catch (reason) { if (operation === epoch.current) setError(getErrorMessage(reason)); }
    finally { if (operation === epoch.current) setPending(null); }
  };
  return <Modal open title={`Google Authenticator · ${account.label}`} onCancel={close} destroyOnHidden footer={<Space>
    {config?.configured ? <Popconfirm title="移除此账户的验证器配置？" onConfirm={() => void remove()}><Button danger disabled={busy}>移除配置</Button></Popconfirm> : null}
    <Button onClick={close}>关闭</Button><Button type="primary" loading={pending === 'save'} disabled={!config?.encryptionReady || busy} onClick={() => void save()}>保存</Button>
  </Space>}>
    <Alert showIcon type="info" title="与手机 Google Authenticator 生成相同的验证码" description="手机已绑定？进入 Google Authenticator 的“转移账号 → 导出账号”，只勾选此 GPT 账户，再导入导出的二维码图片。无需重新绑定；也可填写原始密钥或迁移链接。" />
    {error ? <Alert type="error" showIcon title={error} style={{ marginTop: 16 }} /> : null}
    {!config && !error ? <Spin /> : config ? <>
      {!config.encryptionReady ? <Alert type="warning" showIcon title="后台需配置 OFFICIAL_SESSION_ENCRYPTION_KEY 后才能保存验证器密钥。" style={{ marginTop: 16 }} /> : null}
      <Typography.Paragraph style={{ marginTop: 16 }}>{config.configured ? `已配置：${config.algorithm} · ${config.digits} 位 · ${config.periodSeconds} 秒` : '尚未配置验证器'}</Typography.Paragraph>
      <Button loading={importing} disabled={busy || !config.encryptionReady} onClick={() => fileInput.current?.click()}>导入二维码图片</Button>
      <input ref={fileInput} type="file" hidden aria-label="验证器二维码图片" accept="image/png,image/jpeg,image/webp" onChange={(event) => {
        const file = event.target.files?.[0]; event.target.value = ''; if (file) void importImage(file);
      }} />
      <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>图片只在本浏览器识别，点击保存后才提交密钥。如手机不能截屏，可用另一台设备拍摄清晰二维码。</Typography.Paragraph>
      {importedLabel ? <Alert type="success" showIcon title={`已识别：${importedLabel}`} description="请确认这是当前官方账户的验证器，然后点击保存。" style={{ marginBottom: 16 }} /> : null}
      <Form form={form} layout="vertical" autoComplete="off" disabled={busy} onValuesChange={() => { visibilityEpoch.current += 1; stopPreview(); setImportedLabel(''); }}>
        <Form.Item name="enabled" label="允许所属团队成员获取验证码" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="secret" label="TOTP 密钥或 otpauth://totp 链接" rules={[{ required: !config.configured, message: '请输入对应账户的验证器密钥' }]}
          extra="默认 SHA1、6 位、30 秒。密钥加密保存、不会回显；已配置时留空保留原密钥。保存或关闭后清空输入。">
          <Input.Password autoComplete="new-password" maxLength={8192} visibilityToggle={false} placeholder={config.configured ? '留空保留，输入则替换' : 'Base32 密钥、otpauth:// 或 otpauth-migration:// 链接'} />
        </Form.Item>
      </Form>
      <Button disabled={!config.configured || !config.encryptionReady || busy || Boolean(draft?.trim())} loading={pending === 'preview'} onClick={() => void preview()}>获取当前验证码</Button>
      {autoRefresh ? <Button style={{ marginLeft: 8 }} onClick={stopPreview}>停止自动更新</Button> : null}
      <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>使用导入的同一 TOTP 密钥和服务器时间生成真实验证码，不调用 Google 云端取码接口。显示期间到期自动换码；离开窗口后隐藏并停止更新。</Typography.Paragraph>
      {code?.officialAccountId === account.id ? <div role="status" style={{ marginTop: 12 }}>
        <Typography.Text code aria-label="当前一次性验证码" style={{ fontSize: 28, letterSpacing: 4 }}>{code.code}</Typography.Text>
        <Typography.Text style={{ marginLeft: 12 }}>有效期 {remaining} 秒</Typography.Text>
        <Progress percent={Math.min(100, remaining / code.periodSeconds * 100)} showInfo={false} size="small" aria-label="验证码剩余有效期" />
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>本轮到期：{new Date(code.expiresAt).toLocaleString('zh-CN', { hour12: false })}（本地时间显示，依据服务器时间倒计时）</Typography.Paragraph>
      </div> : null}
    </> : null}
  </Modal>;
}
