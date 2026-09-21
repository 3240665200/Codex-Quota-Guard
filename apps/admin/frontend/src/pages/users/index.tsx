import {
  CheckCircleOutlined,
  SearchOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { ProCard, PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  App as AntApp,
  Avatar,
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  theme,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import { listUsers, updateUser } from '../../services/api';
import type {
  UserListParams,
  UserListResponse,
  UserResponse,
  UserRole,
  UserStatus,
  UserUpdate,
} from '../../services/api';

const PAGE_SIZE = 20;
const EMPTY_FILTERS: Required<Pick<UserListParams, 'q' | 'status' | 'role'>> = {
  q: '',
  role: '',
  status: '',
};

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

export default function UsersPage() {
  const { token } = theme.useToken();
  const { message } = AntApp.useApp();
  const [data, setData] = useState<UserListResponse | null>(null);
  const [draftFilters, setDraftFilters] = useState(EMPTY_FILTERS);
  const [error, setError] = useState('');
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [updatingId, setUpdatingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setData(await listUsers({ ...filters, page, size: PAGE_SIZE }));
    } catch (requestError) {
      setError(getErrorMessage(requestError));
    } finally {
      setLoading(false);
    }
  }, [filters, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const applyFilters = (values = draftFilters) => {
    setPage(0);
    setFilters({
      q: values.q.trim(),
      role: values.role,
      status: values.status,
    });
  };

  const confirmUpdate = useCallback(
    (user: UserResponse, changes: UserUpdate, description: string) => {
      setUpdatingId(user.id);
      Modal.confirm({
        cancelText: '取消',
        content: description,
        okText: '确认修改',
        onCancel: () => setUpdatingId(null),
        onOk: async () => {
          try {
            await updateUser(user.id, changes);
            message.success('用户信息已更新');
            await load();
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setUpdatingId(null);
          }
        },
        title: '确认修改用户信息？',
      });
    },
    [load, message],
  );

  const columns = useMemo<TableColumnsType<UserResponse>>(
    () => [
      {
        dataIndex: 'email',
        render: (_, user) => (
          <Space size={10}>
            <Avatar
              icon={<UserOutlined />}
              style={{ backgroundColor: token.colorPrimaryBg, color: token.colorPrimary }}
            />
            <span className="cqg-user-cell">
              <Typography.Text strong>{user.displayName || '未命名用户'}</Typography.Text>
              <Typography.Text type="secondary">{user.email}</Typography.Text>
            </span>
          </Space>
        ),
        title: '用户',
      },
      {
        dataIndex: 'role',
        render: (role: UserRole, user) => (
          <Select<UserRole>
            aria-label={`修改 ${user.email} 的角色`}
            disabled={updatingId === user.id}
            onChange={(nextRole) => {
              if (nextRole !== role) {
                confirmUpdate(user, { role: nextRole }, `将 ${user.email} 的角色修改为 ${nextRole}？`);
              }
            }}
            options={[
              { label: 'USER', value: 'USER' },
              { label: 'ADMIN', value: 'ADMIN' },
            ]}
            value={role}
          />
        ),
        title: '角色',
        width: 150,
      },
      {
        dataIndex: 'status',
        render: (status: UserStatus, user) => {
          const active = status === 'ACTIVE';
          return (
            <Space size={8}>
              <Tag
                color={active ? 'success' : 'default'}
                icon={active ? <CheckCircleOutlined /> : <StopOutlined />}
              >
                {active ? '启用' : '禁用'}
              </Tag>
              <Button
                aria-label={`${active ? '禁用' : '启用'} ${user.email}`}
                disabled={updatingId === user.id}
                loading={updatingId === user.id}
                onClick={() =>
                  confirmUpdate(
                    user,
                    { status: active ? 'DISABLED' : 'ACTIVE' },
                    `确定${active ? '禁用' : '启用'} ${user.email} 吗？`,
                  )
                }
                size="small"
                type="link"
              >
                {active ? '禁用' : '启用'}
              </Button>
            </Space>
          );
        },
        title: '状态',
        width: 190,
      },
      {
        dataIndex: 'createdAt',
        render: (value: string) => formatDate(value),
        title: '创建时间',
        width: 190,
      },
    ],
    [confirmUpdate, token.colorPrimary, token.colorPrimaryBg, updatingId],
  );

  return (
    <PageContainer
      content="搜索、筛选并维护 Codex Quota Guard 的用户账号。"
      title="用户管理"
    >
      <ProCard className="cqg-users-card">
        <Form
          aria-label="用户筛选"
          className="cqg-filter-form"
          layout="inline"
          onFinish={() => applyFilters()}
        >
          <Form.Item label="搜索">
            <Input
              allowClear
              aria-label="搜索用户"
              onChange={(event) => setDraftFilters((current) => ({ ...current, q: event.target.value }))}
              onPressEnter={() => applyFilters()}
              placeholder="邮箱或姓名"
              value={draftFilters.q}
            />
          </Form.Item>
          <Form.Item label="状态">
            <Select<UserStatus | ''>
              allowClear
              aria-label="按状态筛选"
              onChange={(value) => setDraftFilters((current) => ({ ...current, status: value ?? '' }))}
              options={[
                { label: '启用', value: 'ACTIVE' },
                { label: '禁用', value: 'DISABLED' },
              ]}
              placeholder="全部状态"
              value={draftFilters.status || undefined}
            />
          </Form.Item>
          <Form.Item label="角色">
            <Select<UserRole | ''>
              allowClear
              aria-label="按角色筛选"
              onChange={(value) => setDraftFilters((current) => ({ ...current, role: value ?? '' }))}
              options={[
                { label: 'USER', value: 'USER' },
                { label: 'ADMIN', value: 'ADMIN' },
              ]}
              placeholder="全部角色"
              value={draftFilters.role || undefined}
            />
          </Form.Item>
          <Space>
            <Button htmlType="submit" icon={<SearchOutlined />} type="primary">
              查询
            </Button>
            <Button
              onClick={() => {
                setDraftFilters(EMPTY_FILTERS);
                applyFilters(EMPTY_FILTERS);
              }}
            >
              重置
            </Button>
          </Space>
        </Form>
        {error ? (
          <Alert
            action={<Button onClick={() => void load()}>重试</Button>}
            className="cqg-inline-alert"
            closable
            message="用户列表加载失败"
            onClose={() => setError('')}
            showIcon
            type="error"
          />
        ) : null}
        <Table<UserResponse>
          className="cqg-users-table"
          columns={columns}
          dataSource={data?.items ?? []}
          loading={loading}
          locale={{
            emptyText: <Empty description="暂无符合条件的用户" image={Empty.PRESENTED_IMAGE_SIMPLE} />,
          }}
          pagination={
            data
              ? {
                  current: data.page + 1,
                  onChange: (nextPage) => setPage(nextPage - 1),
                  pageSize: data.size || PAGE_SIZE,
                  showSizeChanger: false,
                  total: data.totalElements,
                }
              : false
          }
          rowKey="id"
          scroll={{ x: 760 }}
        />
      </ProCard>
    </PageContainer>
  );
}
