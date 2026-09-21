import { LogoutOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import { history, Link } from '@umijs/max';
import type { RequestOptions } from '@@/plugin-request/types';
import { App as AntApp, Button, ConfigProvider, Result, Typography } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';

import {
  ApiError,
  clearSession,
  getCurrentUser,
  getSession,
  logout,
  PermissionDeniedError,
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
    <Tagline key="tagline">管理员控制台</Tagline>,
    <Button
      icon={<LogoutOutlined />}
      key="logout"
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
      type="text"
    >
      退出登录
    </Button>,
  ],
  avatarProps: {
    icon: <SafetyCertificateOutlined />,
    title: initialState?.currentUser?.displayName || initialState?.currentUser?.email,
  },
  footerRender: () => (
    <Typography.Text className="cqg-shell-footer" type="secondary">
      Codex Quota Guard · 管理端
    </Typography.Text>
  ),
  logo: (
    <span aria-hidden="true" className="cqg-brand-logo">
      CQ
    </span>
  ),
  menuItemRender: (item, dom) => {
    if (!item.path) return dom;
    return <Link to={item.path}>{dom}</Link>;
  },
  menuHeaderRender: (logo, title) => (
    <div className="cqg-menu-header">
      {logo}
      <span>
        <Typography.Text strong>{title}</Typography.Text>
        <Typography.Text type="secondary">管理员控制台</Typography.Text>
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

function Tagline({ children }: { children: ReactNode }) {
  return <span className="cqg-layout-tagline">{children}</span>;
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
