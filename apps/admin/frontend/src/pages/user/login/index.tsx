import { LockOutlined, MailOutlined, SafetyCertificateFilled } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { Alert, App as AntApp } from 'antd';
import { history, useModel } from '@umijs/max';
import { useEffect, useState } from 'react';

import type { InitialState } from '../../../app';
import { getErrorMessage } from '../../../components/AdminState';
import { login, PermissionDeniedError } from '../../../services/api';
import { getSafeRedirectUrl } from '../../../services/redirect';

export default function LoginPage() {
  const { message } = AntApp.useApp();
  const { initialState, setInitialState } = useModel('@@initialState') as {
    initialState?: InitialState;
    setInitialState: (
      updater: (state: InitialState | undefined) => InitialState,
    ) => void;
  };
  const [error, setError] = useState(initialState?.notice ?? '');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setError(initialState?.notice ?? '');
    if (initialState?.currentUser) {
      const redirect = new URLSearchParams(window.location.search).get('redirect');
      history.replace(getSafeRedirectUrl(redirect));
    }
  }, [initialState?.currentUser, initialState?.notice]);

  return (
    <main className="cqg-login-shell">
      <div className="cqg-login-decoration" aria-hidden="true" />
      <LoginForm
        className="cqg-login-card"
        logo={
          <div aria-hidden="true" className="cqg-login-logo">
            <SafetyCertificateFilled />
          </div>
        }
        onFinish={async (values) => {
          setLoading(true);
          setError('');
          try {
            const response = await login(
              String((values as { email?: string }).email ?? ''),
              String((values as { password?: string }).password ?? ''),
            );
            setInitialState((previous) => ({
              ...previous,
              currentUser: response.user,
              notice: '',
            }));
            message.success('登录成功');
            const redirect = new URLSearchParams(window.location.search).get('redirect');
            history.replace(getSafeRedirectUrl(redirect));
            return true;
          } catch (loginError) {
            setError(
              loginError instanceof PermissionDeniedError
                ? '当前账号没有管理员权限，请使用管理员账号登录。'
                : getErrorMessage(loginError, '登录失败，请检查账号和密码。'),
            );
            return false;
          } finally {
            setLoading(false);
          }
        }}
        submitter={{
          resetButtonProps: { style: { display: 'none' } },
          searchConfig: { submitText: '登录' },
          submitButtonProps: { block: true, loading },
        }}
        subTitle="管理员控制台"
        title="Codex Quota Guard"
      >
        {error ? (
          <Alert
            aria-live="polite"
            className="cqg-login-alert"
            description={error}
            showIcon
            type="error"
          />
        ) : null}
        <ProFormText
          fieldProps={{
            autoComplete: 'username',
            prefix: <MailOutlined />,
            size: 'large',
          }}
          label="邮箱"
          name="email"
          placeholder="请输入管理员邮箱"
          rules={[
            { message: '请输入邮箱', required: true },
            { message: '请输入有效的邮箱地址', type: 'email' },
          ]}
        />
        <ProFormText.Password
          fieldProps={{
            autoComplete: 'current-password',
            prefix: <LockOutlined />,
            size: 'large',
          }}
          label="密码"
          name="password"
          placeholder="请输入密码"
          rules={[{ message: '请输入密码', required: true }]}
        />
      </LoginForm>
      <span className="cqg-login-footer ant-typography ant-typography-secondary">
        仅限管理员访问 · 会话仅保存在当前浏览器标签页
      </span>
    </main>
  );
}
