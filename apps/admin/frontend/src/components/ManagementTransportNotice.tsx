import { Alert } from 'antd';

export default function ManagementTransportNotice({ supported, protocol = typeof window === 'undefined' ? '' : window.location.protocol }: {
  supported: boolean; protocol?: string;
}) {
  if (!supported) return <Alert type="warning" showIcon
    title="当前页面协议不受支持，请使用 HTTP 或 HTTPS 打开管理后台。" />;
  if (protocol !== 'http:') return null;
  return <Alert type="warning" showIcon title="当前使用 HTTP 明文连接"
    description="登录密码、官方授权、代理凭据及管理操作可能被窃听或篡改。建议切换到 HTTPS，或仅在可信网络中使用。" />;
}
