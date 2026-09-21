import { LogoutOutlined, SafetyCertificateFilled, UserOutlined } from '@ant-design/icons';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import { history, Link } from '@umijs/max';
import type { RequestOptions } from '@@/plugin-request/types';
import { App as AntApp, Button, ConfigProvider, Result, Tooltip, Typography } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useEffect, type ReactNode } from 'react';

import {
  ApiError,
  clearSession,
  getCurrentUser,
  getSession,
  logout,
  PermissionDeniedError,
  SESSION_EXPIRED_EVENT,
} from './services/api';
import type { UserResponse } from './services/api';
import './style.css';

const loginPath = '/user/login';

export interface InitialState {
  currentUser?: UserResponse;
  fetchUserInfo?: () => Promise<UserResponse | undefined>;
  notice?: string;
}

function messageForAuthError(error: unknown): string {
  if (error instanceof PermissionDeniedError || (error instanceof ApiError && error.status === 403)) {
    return '当前账号没有管理员权限，请使用管理员账号登录。';
  }
  if (error instanceof ApiError && error.status === 401) {
    return '登录已过期，请重新登录。';
  }
  return '暂时无法校验登录状态，请重新登录。';
}

/** Official Ant Design Pro v6-style initial state and current-user bootstrap. */
export async function getInitialState(): Promise<InitialState> {
  const fetchUserInfo = async (): Promise<UserResponse | undefined> => {
    if (!getSession()) return undefined;

    try {
      const user = await getCurrentUser();
      if (user.role !== 'ADMIN') {
        clearSession();
        throw new PermissionDeniedError();
      }
      return user;
    } catch (error) {
      clearSession();
      throw error;
    }
  };

  const hasSession = Boolean(getSession());
  if (!hasSession && history.location.pathname !== loginPath) {
    return { fetchUserInfo, notice: '请先登录管理员账号。' };
  }

  if (!hasSession) return { fetchUserInfo };

  try {
    const currentUser = await fetchUserInfo();
    return { currentUser, fetchUserInfo };
  } catch (error) {
    return { fetchUserInfo, notice: messageForAuthError(error) };
  }
}

export const layout: RunTimeLayoutConfig = ({ initialState, setInitialState }) => ({
  actionsRender: () => [
    <Tooltip key="logout" title="退出登录">
      <Button
        aria-label="退出登录"
        className="cqg-logout-button"
        icon={<LogoutOutlined />}
        onClick={() => {
          void logout().finally(() => {
            setInitialState((previous) => ({
              ...previous,
              currentUser: undefined,
              notice: '',
            }));
            history.replace(loginPath);
          });
        }}
        shape="circle"
        type="text"
      />
    </Tooltip>,
  ],
  avatarProps: {
    className: 'cqg-admin-avatar',
    icon: <UserOutlined />,
    title: initialState?.currentUser ? '管理员' : undefined,
  },
  childrenRender: (children) => (
    <SessionExpiryBoundary
      onExpired={() => {
        const { pathname, search } = history.location;
        setInitialState((previous) => ({
          ...previous,
          currentUser: undefined,
          notice: '登录已过期，请重新登录。',
        }));
        if (pathname !== loginPath) {
          history.replace(
            `${loginPath}?redirect=${encodeURIComponent(pathname + search)}`,
          );
        }
      }}
    >
      {children}
    </SessionExpiryBoundary>
  ),
  footerRender: () => (
    <Typography.Text className="cqg-shell-footer" type="secondary">
      Codex Quota Guard · 管理端
    </Typography.Text>
  ),
  logo: (
    <span aria-hidden="true" className="cqg-brand-logo">
      <SafetyCertificateFilled />
    </span>
  ),
  menuItemRender: (item, dom) => {
    if (!item.path) return dom;
    return <Link to={item.path}>{dom}</Link>;
  },
  menuHeaderRender: (logo) => (
    <div className="cqg-menu-header">
      {logo}
      <span>
        <Typography.Text strong>Quota Guard</Typography.Text>
        <Typography.Text type="secondary">管理中心</Typography.Text>
      </span>
    </div>
  ),
  onPageChange: () => {
    const { location } = history;
    if (!initialState?.currentUser && location.pathname !== loginPath) {
      history.replace(`${loginPath}?redirect=${encodeURIComponent(location.pathname + location.search)}`);
    }
  },
  title: 'Codex Quota Guard',
  unAccessible: (
    <Result
      extra={<Link to={loginPath}>返回登录</Link>}
      status="403"
      subTitle="当前账号没有管理员权限。"
      title="无权限访问"
    />
  ),
});

/** Keep the official request runtime hook available for future Umi requests. */
export const request: RequestConfig = {
  requestInterceptors: [
    (requestConfig: RequestOptions) => {
      const session = getSession();
      if (session?.accessToken) {
        requestConfig.headers = {
          ...requestConfig.headers,
          Authorization: `Bearer ${session.accessToken}`,
        };
      }
      return requestConfig;
    },
  ],
};

function SessionExpiryBoundary({
  children,
  onExpired,
}: {
  children: ReactNode;
  onExpired: () => void;
}) {
  useEffect(() => {
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, [onExpired]);

  return children;
}

export function rootContainer(container: ReactNode): ReactNode {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          borderRadius: 10,
          colorPrimary: '#2563eb',
          fontFamily:
            'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif',
        },
      }}
    >
      <AntApp>{container}</AntApp>
    </ConfigProvider>
  );
}
