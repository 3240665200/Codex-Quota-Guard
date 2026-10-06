import { ExportOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, Modal, Radio, Result, Space, Spin, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import {
  cancelCodexAuthorization,
  getCodexAuthorization,
  startCodexAuthorization,
} from '../../services/api';
import type { CodexAuthorization, CodexLoginMode } from '../../services/api';
import { officialAuthorizationUrl } from '../../services/codexAuthorizationUrl';

interface Props {
  onClose: () => void;
  onAdded: () => void;
}

const isPending = (value: CodexAuthorization | null) =>
  value?.status === 'PENDING' || value?.status === 'STARTING';

export default function AuthorizationModal({ onClose, onAdded }: Props) {
  const [mode, setMode] = useState<CodexLoginMode>(() =>
    ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname) ? 'BROWSER' : 'DEVICE_CODE');
  const [authorization, setAuthorization] = useState<CodexAuthorization | null>(null);
  const [starting, setStarting] = useState(false);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const mounted = useRef(true);
  const currentId = useRef<string | null>(null);
  const delivered = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (currentId.current) {
        // Navigation/unmount is best effort; the server also enforces expiration independently.
        void cancelCodexAuthorization(currentId.current).catch(() => undefined);
      }
    };
  }, []);

  useEffect(() => {
    if (authorization?.status === 'SUCCEEDED' && !delivered.current) {
      delivered.current = true;
      currentId.current = null;
      onAdded();
    }
  }, [authorization?.status, onAdded]);

  useEffect(() => {
    if (!authorization || !isPending(authorization)) return;
    const id = authorization.id;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const next = await getCodexAuthorization(id, controller.signal);
        if (controller.signal.aborted) return;
        setAuthorization(next);
        setError('');
        if (isPending(next)) timer = setTimeout(() => void poll(), 2000);
        else currentId.current = null;
      } catch (requestError) {
        if (!controller.signal.aborted) setError(getErrorMessage(requestError));
      }
    };
    timer = setTimeout(() => void poll(), 1500);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [authorization?.id, authorization?.status, retry]);

  const start = async ({ displayName }: { displayName: string }) => {
    setStarting(true);
    setError('');
    try {
      const next = await startCodexAuthorization(displayName.trim(), mode);
      if (!mounted.current) {
        if (isPending(next)) await cancelCodexAuthorization(next.id);
        return;
      }
      currentId.current = isPending(next) ? next.id : null;
      setAuthorization(next);
      const url = officialAuthorizationUrl(next);
      if (isPending(next) && url) {
        // Never copy the admin tab's sessionStorage into a same-origin placeholder tab.
        // A manual link remains visible if the browser blocks this async popup.
        window.open(url, '_blank', 'noopener,noreferrer');
      }
    } catch (requestError) {
      if (mounted.current) setError(getErrorMessage(requestError));
    } finally {
      if (mounted.current) setStarting(false);
    }
  };

  const close = async () => {
    setClosing(true);
    try {
      if (currentId.current) await cancelCodexAuthorization(currentId.current);
      currentId.current = null;
      onClose();
    } catch (requestError) {
      setError(getErrorMessage(requestError));
    } finally {
      if (mounted.current) setClosing(false);
    }
  };

  const failed = authorization && !isPending(authorization) && authorization.status !== 'SUCCEEDED';
  const loginUrl = authorization ? officialAuthorizationUrl(authorization) : null;
  return (
    <Modal
      open
      title="授权添加 Codex 账户"
      onCancel={() => void close()}
      footer={[
        <Button key="close" loading={closing} onClick={() => void close()}>
          {isPending(authorization) ? '取消授权' : '关闭'}
        </Button>,
      ]}
    >
      {error && (
        <Alert className="cqg-inline-alert" showIcon type="error" title={error}
          action={isPending(authorization)
            ? <Button size="small" onClick={() => { setError(''); setRetry((value) => value + 1); }}>重新检查</Button>
            : undefined}
        />
      )}
      {!authorization && (
        <>
          <Alert className="cqg-inline-alert" showIcon icon={<SafetyCertificateOutlined />}
            type="info" title="在官方页面登录，JSON 自动加密保存到服务器。" />
          <Form layout="vertical" onFinish={(values) => void start(values)} disabled={starting}>
            <Form.Item name="displayName" label="账户名称"
              rules={[{ required: true, whitespace: true, message: '请输入账户名称' }]}
            >
              <Input maxLength={120} placeholder="例如：开发账户" autoComplete="off" />
            </Form.Item>
            <Form.Item label="登录方式">
              <Radio.Group value={mode} onChange={(event) => setMode(event.target.value)}>
                <Radio.Button value="BROWSER">网页登录</Radio.Button>
                <Radio.Button value="DEVICE_CODE">设备码登录</Radio.Button>
              </Radio.Group>
            </Form.Item>
            <Typography.Paragraph type="secondary">
              {mode === 'BROWSER'
                ? '浏览器须与 Java 后台运行在同一台电脑；远程服务器请选择设备码登录。'
                : '适合远程服务器。需先在 ChatGPT 安全设置中允许设备码登录。'}
            </Typography.Paragraph>
            <Button type="primary" htmlType="submit" loading={starting} icon={<ExportOutlined aria-hidden />}>
              {mode === 'BROWSER' ? '前往官方登录' : '获取授权码并打开官网'}
            </Button>
          </Form>
        </>
      )}
      {isPending(authorization) && authorization && (
        <Space orientation="vertical" size={18} style={{ width: '100%' }}>
          <Typography.Text>请在官方页面登录要添加的账户；如果没有自动打开，请点击下面的按钮。</Typography.Text>
          {loginUrl && (
            <Button href={loginUrl} target="_blank" rel="noopener noreferrer"
              icon={<ExportOutlined aria-hidden />} type="primary">打开 OpenAI 授权页</Button>
          )}
          {!loginUrl && <Alert type="error" showIcon title="授权地址校验失败，请取消后重试。" />}
          {authorization.mode === 'DEVICE_CODE' ? (
            <>
              <Typography.Text>在官方页面输入一次性验证码，完成后回到这里。</Typography.Text>
              <Typography.Title level={2} copyable={{ text: authorization.userCode ?? '' }} style={{ margin: 0 }}>
                {authorization.userCode}
              </Typography.Title>
            </>
          ) : (
            <Alert type="info" showIcon title="登录后自动保存，无需下载或上传 JSON。"
              description="若官网完成登录后 localhost 回调失败，请取消并使用设备码登录。" />
          )}
          <Typography.Text type="secondary">
            {new Date(authorization.expiresAt).toLocaleTimeString('zh-CN')} 到期 · 请勿转发授权链接或验证码
          </Typography.Text>
          <Space><Spin size="small" /><span role="status">等待官方授权，完成后自动添加…</span></Space>
        </Space>
      )}
      {authorization?.status === 'SUCCEEDED' && (
        <Result status="success" title="账户已添加" subTitle={authorization.account?.email} />
      )}
      {failed && (
        <Result status={authorization.status === 'FAILED' ? 'error' : 'info'}
          title={authorization.status === 'EXPIRED' ? '授权已过期'
            : authorization.status === 'CANCELLED' ? '授权已取消' : '授权未完成'}
          subTitle={authorization.message ?? '请重新发起授权。'}
          extra={<Button onClick={() => { setAuthorization(null); setError(''); }}>重新授权</Button>}
        />
      )}
    </Modal>
  );
}
