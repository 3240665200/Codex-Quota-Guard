import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  MoreOutlined,
  PlusOutlined,
  PoweroffOutlined,
  SearchOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  App as AntApp,
  Avatar,
  Button,
  Dropdown,
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
import {
  createUser,
  deleteUser,
  forceLogoutUser,
  getSession,
  listUsers,
  updateUser,
} from '../../services/api';
import type {
  UserCreate,
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

interface UserFormValues {
  displayName: string;
  email: string;
  password?: string;
  role: UserRole;
  status: UserStatus;
}

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
  const { message, modal } = AntApp.useApp();
  const [form] = Form.useForm<UserFormValues>();
  const [data, setData] = useState<UserListResponse | null>(null);
  const [draftFilters, setDraftFilters] = useState(EMPTY_FILTERS);
  const [editingUser, setEditingUser] = useState<UserResponse | null>(null);
  const [error, setError] = useState('');
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [formOpen, setFormOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [mutatingId, setMutatingId] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [saving, setSaving] = useState(false);
  const currentUserId = getSession()?.user.id;

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

  const openCreate = useCallback(() => {
    setEditingUser(null);
    form.resetFields();
    form.setFieldsValue({ role: 'USER', status: 'ACTIVE' });
    setFormOpen(true);
  }, [form]);

  const openEdit = useCallback(
    (user: UserResponse) => {
      setEditingUser(user);
      form.setFieldsValue({
        displayName: user.displayName,
        email: user.email,
        password: undefined,
        role: user.role,
        status: user.status,
      });
      setFormOpen(true);
    },
    [form],
  );

  const saveUser = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editingUser) {
        const update: UserUpdate = {
          displayName: values.displayName,
          email: values.email,
          role: values.role,
          status: values.status,
        };
        if (values.password) update.password = values.password;
        await updateUser(editingUser.id, update);
        message.success('用户已更新');
      } else {
        await createUser(values as UserCreate);
        message.success('用户已创建');
      }
      setFormOpen(false);
      form.resetFields();
      await load();
    } catch (requestError) {
      if (requestError && typeof requestError === 'object' && 'errorFields' in requestError) {
        return;
      }
      message.error(getErrorMessage(requestError));
    } finally {
      setSaving(false);
    }
  };

  const confirmUpdate = useCallback(
    (user: UserResponse, changes: UserUpdate, description: string) => {
      setMutatingId(user.id);
      modal.confirm({
        cancelText: '取消',
        content: description,
        okText: '确认修改',
        onCancel: () => setMutatingId(null),
        onOk: async () => {
          try {
            await updateUser(user.id, changes);
            message.success('用户已更新');
            await load();
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setMutatingId(null);
          }
        },
        title: '确认修改？',
      });
    },
    [load, message, modal],
  );

  const confirmForceLogout = useCallback(
    (user: UserResponse) => {
      setMutatingId(user.id);
      modal.confirm({
        cancelText: '取消',
        content: `这会立即使 ${user.email} 的全部登录失效。`,
        okText: '强制下线',
        onCancel: () => setMutatingId(null),
        onOk: async () => {
          try {
            await forceLogoutUser(user.id);
            message.success('该用户已强制下线');
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setMutatingId(null);
          }
        },
        title: '强制下线用户？',
      });
    },
    [message, modal],
  );

  const confirmDelete = useCallback(
    (user: UserResponse) => {
      setMutatingId(user.id);
      modal.confirm({
        cancelText: '取消',
        content: `删除后无法恢复：${user.email}`,
        okButtonProps: { danger: true },
        okText: '删除',
        onCancel: () => setMutatingId(null),
        onOk: async () => {
          try {
            await deleteUser(user.id);
            message.success('用户已删除');
            await load();
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setMutatingId(null);
          }
        },
        title: '删除用户？',
      });
    },
    [load, message, modal],
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
            disabled={mutatingId === user.id}
            onChange={(nextRole) => {
              if (nextRole !== role) {
                confirmUpdate(
                  user,
                  { role: nextRole },
                  `将 ${user.email} 的角色修改为 ${nextRole}？`,
                );
              }
            }}
            options={[
              { label: '用户', value: 'USER' },
              { label: '管理员', value: 'ADMIN' },
            ]}
            value={role}
          />
        ),
        title: '角色',
        width: 140,
      },
      {
        dataIndex: 'status',
        render: (status: UserStatus, user) => {
          const active = status === 'ACTIVE';
          return (
            <Space size={6}>
              <Tag
                color={active ? 'success' : 'default'}
                icon={active ? <CheckCircleOutlined /> : <StopOutlined />}
              >
                {active ? '启用' : '禁用'}
              </Tag>
              <Button
                aria-label={`${active ? '禁用' : '启用'} ${user.email}`}
                disabled={mutatingId === user.id}
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
        width: 170,
      },
      {
        dataIndex: 'createdAt',
        render: (value: string) => formatDate(value),
        title: '创建时间',
        width: 180,
      },
      {
        fixed: 'right',
        render: (_, user) => {
          const self = user.id === currentUserId;
          return (
            <Dropdown
              menu={{
                items: [
                  {
                    icon: <EditOutlined />,
                    key: 'edit',
                    label: '编辑',
                    onClick: () => openEdit(user),
                  },
                  {
                    disabled: self,
                    icon: <PoweroffOutlined />,
                    key: 'offline',
                    label: '强制下线',
                    onClick: () => confirmForceLogout(user),
                  },
                  { type: 'divider' },
                  {
                    danger: true,
                    disabled: self,
                    icon: <DeleteOutlined />,
                    key: 'delete',
                    label: '删除',
                    onClick: () => confirmDelete(user),
                  },
                ],
              }}
              trigger={['click']}
            >
              <Button
                aria-label={`管理 ${user.email}`}
                icon={<MoreOutlined />}
                loading={mutatingId === user.id}
                type="text"
              />
            </Dropdown>
          );
        },
        title: '操作',
        width: 80,
      },
    ],
    [
      confirmDelete,
      confirmForceLogout,
      confirmUpdate,
      currentUserId,
      mutatingId,
      openEdit,
      token.colorPrimary,
      token.colorPrimaryBg,
    ],
  );

  return (
    <PageContainer
      content="管理用户与登录会话。"
      extra={[
        <Button icon={<PlusOutlined />} key="create" onClick={openCreate} type="primary">
          新增用户
        </Button>,
      ]}
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
              onChange={(event) =>
                setDraftFilters((current) => ({ ...current, q: event.target.value }))
              }
              onPressEnter={() => applyFilters()}
              placeholder="邮箱或姓名"
              value={draftFilters.q}
            />
          </Form.Item>
          <Form.Item label="状态">
            <Select<UserStatus | ''>
              allowClear
              aria-label="按状态筛选"
              onChange={(value) =>
                setDraftFilters((current) => ({ ...current, status: value ?? '' }))
              }
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
              onChange={(value) =>
                setDraftFilters((current) => ({ ...current, role: value ?? '' }))
              }
              options={[
                { label: '用户', value: 'USER' },
                { label: '管理员', value: 'ADMIN' },
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
            title="用户列表加载失败"
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
            emptyText: (
              <Empty description="暂无用户" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ),
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
          scroll={{ x: 900 }}
        />
      </ProCard>

      <Modal
        forceRender
        okButtonProps={{ loading: saving }}
        okText={editingUser ? '保存' : '创建'}
        onCancel={() => {
          setFormOpen(false);
          form.resetFields();
        }}
        onOk={() => void saveUser()}
        open={formOpen}
        title={editingUser ? '编辑用户' : '新增用户'}
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            label="邮箱"
            name="email"
            rules={[
              { message: '请输入邮箱', required: true },
              { message: '邮箱格式不正确', type: 'email' },
            ]}
          >
            <Input autoComplete="off" maxLength={320} />
          </Form.Item>
          <Form.Item
            label="姓名"
            name="displayName"
            rules={[{ message: '请输入姓名', required: true }]}
          >
            <Input maxLength={120} />
          </Form.Item>
          <Form.Item
            extra={editingUser ? '留空则保持原密码' : undefined}
            label="密码"
            name="password"
            rules={[
              { message: '请输入密码', required: !editingUser },
              { message: '密码至少 12 位', min: 12 },
              { max: 128, message: '密码不能超过 128 位' },
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Space align="start" size={16}>
            <Form.Item
              label="角色"
              name="role"
              rules={[{ message: '请选择角色', required: true }]}
            >
              <Select
                options={[
                  { label: '用户', value: 'USER' },
                  { label: '管理员', value: 'ADMIN' },
                ]}
                style={{ width: 160 }}
              />
            </Form.Item>
            <Form.Item
              label="状态"
              name="status"
              rules={[{ message: '请选择状态', required: true }]}
            >
              <Select
                options={[
                  { label: '启用', value: 'ACTIVE' },
                  { label: '禁用', value: 'DISABLED' },
                ]}
                style={{ width: 160 }}
              />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </PageContainer>
  );
}
