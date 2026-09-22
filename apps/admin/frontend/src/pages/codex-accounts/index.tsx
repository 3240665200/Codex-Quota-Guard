import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  KeyOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
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
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import type { TableColumnsType, UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import {
  createCodexAccount,
  deleteCodexAccount,
  listCodexAccounts,
  updateCodexAccount,
} from '../../services/api';
import type {
  CodexAccount,
  CodexAccountListResponse,
  CodexAccountUpdate,
  UserStatus,
} from '../../services/api';

const MAX_CREDENTIAL_BYTES = 1024 * 1024;

interface AccountFormValues {
  displayName: string;
  email: string;
  status: UserStatus;
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN');
}

async function readCredential(file: File): Promise<Record<string, unknown>> {
  const parsed = JSON.parse(await file.text()) as unknown;
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('凭据必须是 JSON 对象');
  }
  return parsed as Record<string, unknown>;
}

export default function CodexAccountsPage() {
  const { message, modal } = AntApp.useApp();
  const [form] = Form.useForm<AccountFormValues>();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [credentialFile, setCredentialFile] = useState<File | null>(null);
  const [data, setData] = useState<CodexAccountListResponse | null>(null);
  const [editing, setEditing] = useState<CodexAccount | null>(null);
  const [error, setError] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState<UserStatus | ''>('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setData(await listCodexAccounts({ page, q: query, size: 20, status }));
    } catch (requestError) {
      setError(getErrorMessage(requestError));
    } finally {
      setLoading(false);
    }
  }, [page, query, status]);

  useEffect(() => {
    void load();
  }, [load]);

  const openCreate = () => {
    setEditing(null);
    setCredentialFile(null);
    form.resetFields();
    form.setFieldsValue({ status: 'ACTIVE' });
    setFormOpen(true);
  };

  const openEdit = useCallback(
    (account: CodexAccount) => {
      setEditing(account);
      setCredentialFile(null);
      form.setFieldsValue({
        displayName: account.displayName,
        email: account.email,
        status: account.status,
      });
      setFormOpen(true);
    },
    [form],
  );

  const saveAccount = async () => {
    const values = await form.validateFields();
    if (!editing && !credentialFile) {
      message.warning('请选择 Codex 凭据 JSON');
      return;
    }
    setSaving(true);
    try {
      const credential = credentialFile
        ? await readCredential(credentialFile)
        : undefined;
      if (editing) {
        const update: CodexAccountUpdate = { ...values };
        if (credential) update.credential = credential;
        await updateCodexAccount(editing.id, update);
        message.success('Codex 账户已更新');
      } else {
        await createCodexAccount({ ...values, credential: credential! });
        message.success('Codex 账户已添加');
      }
      setFormOpen(false);
      setCredentialFile(null);
      form.resetFields();
      await load();
    } catch (requestError) {
      if (requestError && typeof requestError === 'object' && 'errorFields' in requestError) {
        return;
      }
      message.error(
        requestError instanceof SyntaxError
          ? 'JSON 文件格式不正确'
          : getErrorMessage(requestError),
      );
    } finally {
      setSaving(false);
    }
  };

  const toggleAccount = useCallback(
    async (account: CodexAccount) => {
      const nextStatus: UserStatus = account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
      setBusyId(account.id);
      try {
        await updateCodexAccount(account.id, { status: nextStatus });
        message.success(nextStatus === 'ACTIVE' ? '账户已启用' : '账户已停用');
        await load();
      } catch (requestError) {
        message.error(getErrorMessage(requestError));
      } finally {
        setBusyId(null);
      }
    },
    [load, message],
  );

  const confirmDelete = useCallback(
    (account: CodexAccount) => {
      modal.confirm({
        cancelText: '取消',
        content: `删除后无法恢复：${account.email}`,
        okButtonProps: { danger: true },
        okText: '删除',
        onOk: async () => {
          setBusyId(account.id);
          try {
            await deleteCodexAccount(account.id);
            message.success('Codex 账户已删除');
            await load();
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setBusyId(null);
          }
        },
        title: '删除 Codex 账户？',
      });
    },
    [load, message, modal],
  );

  const columns = useMemo<TableColumnsType<CodexAccount>>(
    () => [
      {
        dataIndex: 'email',
        render: (_, account) => (
          <Space size={10}>
            <Avatar className="cqg-codex-avatar" icon={<KeyOutlined />} />
            <span className="cqg-user-cell">
              <Typography.Text strong>{account.displayName}</Typography.Text>
              <Typography.Text type="secondary">{account.email}</Typography.Text>
            </span>
          </Space>
        ),
        title: '账户',
      },
      {
        dataIndex: 'status',
        render: (value: UserStatus) =>
          value === 'ACTIVE' ? (
            <Tag color="success" icon={<CheckCircleOutlined />}>启用</Tag>
          ) : (
            <Tag icon={<StopOutlined />}>停用</Tag>
          ),
        title: '状态',
        width: 110,
      },
      {
        dataIndex: 'updatedAt',
        render: (value: string) => formatDate(value),
        title: '更新时间',
        width: 180,
      },
      {
        render: (_, account) => (
          <Space size={2}>
            <Tooltip title="编辑">
              <Button
                aria-label={`编辑 ${account.email}`}
                icon={<EditOutlined />}
                onClick={() => openEdit(account)}
                type="text"
              />
            </Tooltip>
            <Button
              loading={busyId === account.id}
              onClick={() => void toggleAccount(account)}
              size="small"
            >
              {account.status === 'ACTIVE' ? '停用' : '启用'}
            </Button>
            <Tooltip title="删除">
              <Button
                aria-label={`删除 ${account.email}`}
                danger
                icon={<DeleteOutlined />}
                onClick={() => confirmDelete(account)}
                type="text"
              />
            </Tooltip>
          </Space>
        ),
        title: '操作',
        width: 180,
      },
    ],
    [busyId, confirmDelete, openEdit, toggleAccount],
  );

  const uploadFiles: UploadFile[] = credentialFile
    ? [{ name: credentialFile.name, size: credentialFile.size, status: 'done', uid: 'credential' }]
    : [];
  const beforeUpload: UploadProps['beforeUpload'] = (file) => {
    if (!file.name.toLowerCase().endsWith('.json')) {
      message.error('请选择 .json 文件');
      return Upload.LIST_IGNORE;
    }
    if (file.size > MAX_CREDENTIAL_BYTES) {
      message.error('文件不能超过 1 MiB');
      return Upload.LIST_IGNORE;
    }
    setCredentialFile(file);
    return false;
  };

  return (
    <PageContainer
      content="管理 Codex 登录账户。"
      extra={[
        <Button icon={<PlusOutlined />} key="create" onClick={openCreate} type="primary">
          新增账户
        </Button>,
      ]}
      title="Codex 账户"
    >
      {error ? (
        <Alert
          action={<Button onClick={() => void load()}>重试</Button>}
          className="cqg-inline-alert"
          description={error}
          title="账户加载失败"
          showIcon
          type="error"
        />
      ) : null}
      <ProCard
        className="cqg-users-card"
        extra={
          <Tooltip title="刷新">
            <Button
              aria-label="刷新 Codex 账户"
              icon={<ReloadOutlined />}
              loading={loading}
              onClick={() => void load()}
              type="text"
            />
          </Tooltip>
        }
      >
        <Space className="cqg-users-toolbar" wrap>
          <Input.Search
            allowClear
            onSearch={(value) => {
              setPage(0);
              setQuery(value.trim());
            }}
            placeholder="邮箱或名称"
            style={{ width: 260 }}
          />
          <Select<UserStatus | ''>
            allowClear
            onChange={(value) => {
              setPage(0);
              setStatus(value ?? '');
            }}
            options={[
              { label: '启用', value: 'ACTIVE' },
              { label: '停用', value: 'DISABLED' },
            ]}
            placeholder="全部状态"
            style={{ width: 140 }}
          />
        </Space>
        <Table<CodexAccount>
          columns={columns}
          dataSource={data?.items ?? []}
          loading={loading}
          locale={{
            emptyText: <Empty description="暂无账户" image={Empty.PRESENTED_IMAGE_SIMPLE} />,
          }}
          pagination={
            data
              ? {
                  current: data.page + 1,
                  onChange: (nextPage) => setPage(nextPage - 1),
                  pageSize: data.size,
                  showSizeChanger: false,
                  total: data.totalElements,
                }
              : false
          }
          rowKey="id"
          scroll={{ x: 760 }}
        />
      </ProCard>

      <Modal
        forceRender
        okButtonProps={{ loading: saving }}
        okText={editing ? '保存' : '添加'}
        onCancel={() => {
          setFormOpen(false);
          setCredentialFile(null);
          form.resetFields();
        }}
        onOk={() => void saveAccount()}
        open={formOpen}
        title={editing ? '编辑 Codex 账户' : '新增 Codex 账户'}
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
            <Input maxLength={320} />
          </Form.Item>
          <Form.Item
            label="名称"
            name="displayName"
            rules={[{ message: '请输入名称', required: true }]}
          >
            <Input maxLength={120} />
          </Form.Item>
          <Form.Item
            label="状态"
            name="status"
            rules={[{ message: '请选择状态', required: true }]}
          >
            <Select
              options={[
                { label: '启用', value: 'ACTIVE' },
                { label: '停用', value: 'DISABLED' },
              ]}
            />
          </Form.Item>
        </Form>
        <Alert
          className="cqg-inline-alert"
          title="凭据会在 Java 后端加密后保存，不会在查询接口中返回。"
          showIcon
          type="info"
        />
        <Upload.Dragger
          accept="application/json,.json"
          beforeUpload={beforeUpload}
          fileList={uploadFiles}
          maxCount={1}
          onRemove={() => {
            setCredentialFile(null);
            return true;
          }}
        >
          <p className="ant-upload-drag-icon"><UploadOutlined /></p>
          <p>{editing ? '可选：上传新凭据替换原凭据' : '上传 Codex 凭据 JSON'}</p>
        </Upload.Dragger>
      </Modal>
    </PageContainer>
  );
}
