import {
  DesktopOutlined,
  LogoutOutlined,
  MoonOutlined,
  SafetyCertificateFilled,
  SunOutlined,
  UserOutlined,
} from '@ant-design/icons';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import { history, Link } from '@umijs/max';
import type { RequestOptions } from '@@/plugin-request/types';
import {
  App as AntApp,
  Button,
  ConfigProvider,
  Dropdown,
  Result,
  theme as antdTheme,
  Tooltip,
  Typography,
} from 'antd';
import zhCN from 'antd/locale/zh_CN';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

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
const themePreferenceKey = 'cqg-admin-theme-preference';

type ThemePreference = 'light' | 'dark' | 'system';

const ThemePreferenceContext = createContext<{
  preference: ThemePreference;
  setPreference: (preference: ThemePreference) => void;
}>({
  preference: 'system',
  setPreference: () => undefined,
});

function readThemePreference(): ThemePreference {
  if (typeof window === 'undefined') return 'system';
  try {
    const stored = window.localStorage.getItem(themePreferenceKey);
    return stored === 'light' || stored === 'dark' || stored === 'system'
      ? stored
      : 'system';
  } catch {
    return 'system';
  }
}

function readSystemDarkMode(): boolean {
  return typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-color-scheme: dark)').matches === true;
}

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
    <ThemeSwitcher key="theme" />,
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

function ThemeSwitcher() {
  const { preference, setPreference } = useContext(ThemePreferenceContext);
  const labels: Record<ThemePreference, string> = {
    light: '浅色',
    dark: '深色',
    system: '跟随系统',
  };
  const icon = preference === 'dark'
    ? <MoonOutlined />
    : preference === 'light'
      ? <SunOutlined />
      : <DesktopOutlined />;

  return (
    <Dropdown
      menu={{
        selectedKeys: [preference],
        items: [
          { key: 'light', icon: <SunOutlined />, label: '浅色' },
          { key: 'dark', icon: <MoonOutlined />, label: '深色' },
          { key: 'system', icon: <DesktopOutlined />, label: '跟随系统' },
        ],
        onClick: ({ key }) => {
          if (key === 'light' || key === 'dark' || key === 'system') {
            setPreference(key);
          }
        },
      }}
      placement="bottomRight"
      trigger={['click']}
    >
      <Button
        aria-label={`主题模式：${labels[preference]}`}
        className="cqg-theme-button"
        icon={icon}
        title={`主题模式：${labels[preference]}`}
        type="text"
      >
        <span className="cqg-theme-label">主题</span>
      </Button>
    </Dropdown>
  );
}

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
    <AdminThemeProvider>{container}</AdminThemeProvider>
  );
}

function AdminThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>(readThemePreference);
  const [systemDark, setSystemDark] = useState(readSystemDarkMode);
  const isDark = preference === 'dark' || (preference === 'system' && systemDark);

  const setPreference = useCallback((nextPreference: ThemePreference) => {
    setPreferenceState(nextPreference);
    try {
      window.localStorage.setItem(themePreferenceKey, nextPreference);
    } catch {
      // Keep the current session usable when browser storage is unavailable.
    }
  }, []);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const syncSystemTheme = () => setSystemDark(media.matches);
    syncSystemTheme();
    media.addEventListener('change', syncSystemTheme);
    return () => media.removeEventListener('change', syncSystemTheme);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.cqgTheme = isDark ? 'dark' : 'light';
    document.documentElement.style.colorScheme = isDark ? 'dark' : 'light';
  }, [isDark]);

  const contextValue = useMemo(
    () => ({ preference, setPreference }),
    [preference, setPreference],
  );

  return (
    <ThemePreferenceContext.Provider value={contextValue}>
      <ConfigProvider
        locale={zhCN}
        theme={{
          algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
          token: {
            borderRadius: 12,
            colorPrimary: isDark ? '#72b8b0' : '#4a9d9a',
            colorSuccess: isDark ? '#8db8a7' : '#6b8e8e',
            colorWarning: isDark ? '#dfb86e' : '#b78234',
            colorError: isDark ? '#df9282' : '#c17767',
            colorInfo: isDark ? '#82b9b3' : '#6b8e8e',
            colorBgLayout: isDark ? '#171b18' : '#faf8f5',
            colorBgContainer: isDark ? '#222723' : '#ffffff',
            colorText: isDark ? '#e7e9e4' : '#373b38',
            colorTextSecondary: isDark ? '#a2aba2' : '#747a74',
            colorBorder: isDark ? '#39413b' : '#e9e4dc',
            colorBorderSecondary: isDark ? '#303832' : '#f0ede7',
            controlHeight: 40,
            boxShadowSecondary: isDark
              ? '0 16px 40px rgb(0 0 0 / 28%)'
              : '0 16px 40px rgb(61 66 57 / 8%)',
            fontFamily:
              'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif',
          },
        }}
      >
        <AntApp>{children}</AntApp>
      </ConfigProvider>
    </ThemePreferenceContext.Provider>
  );
}
