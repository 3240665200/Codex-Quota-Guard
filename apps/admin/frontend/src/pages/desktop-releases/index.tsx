import { PageContainer } from "@ant-design/pro-components";
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { getErrorMessage } from "../../components/AdminState";
import {
  changeDesktopRelease,
  createDesktopRelease,
  listDesktopReleases,
  type DesktopRelease,
  type ReleaseKind,
} from "../../services/desktopReleasesApi";

import { ReleaseUploadModal } from "./upload-modal";
const status = { DRAFT: "草稿", PUBLISHED: "已发布", WITHDRAWN: "已撤回" };
const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
export default function DesktopReleasesPage() {
  const { message } = App.useApp();
  const [kind, setKind] = useState<ReleaseKind>("desktop");
  const [uploadBusy, setUploadBusy] = useState(false);
  const loadEpoch = useRef(0);
  const meter = kind === "meter";
  const [rows, setRows] = useState<DesktopRelease[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [upload, setUpload] = useState<DesktopRelease>();
  const [form] = Form.useForm<{ version: string; notes: string }>();
  const load = useCallback(async () => {
    const epoch = ++loadEpoch.current;
    setLoading(true);
    setError("");
    try {
      const next = await listDesktopReleases(kind);
      if (epoch === loadEpoch.current) setRows(next);
    } catch (failure) {
      if (epoch === loadEpoch.current) setError(getErrorMessage(failure));
    } finally {
      if (epoch === loadEpoch.current) setLoading(false);
    }
  }, [kind]);
  useEffect(() => {
    void load();
  }, [load]);
  async function perform(work: () => Promise<unknown>, complete?: () => void) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await work();
      complete?.();
      void message.success("操作成功");
      await load();
    } catch (failure) {
      setError(getErrorMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <PageContainer
      title="版本管理"
      content="分别管理桌面客户端和统计程序的独立版本。上传支持进度显示、暂停和断点续传。"
    >
      <Space orientation="vertical" size={20} style={{ width: "100%" }}>
        <Tabs
          activeKey={kind}
          onChange={(value) => {
            loadEpoch.current++;
            setKind(value as ReleaseKind);
            setRows([]);
            setError("");
          }}
          items={[
            {
              key: "desktop",
              label: "桌面客户端",
              disabled: busy || uploadBusy,
            },
            { key: "meter", label: "统计程序", disabled: busy || uploadBusy },
          ]}
        />
        <Alert
          type="info"
          showIcon
          title="上传安装包与对应签名，再发布版本"
          description={
            meter
              ? "统计程序独立发布和签名，版本不受客户端版本约束。支持自动更新的 Windows 统计服务会每 5 分钟检查并静默强制更新，无需打开客户端或确认通知。首次需要安装支持自动更新的统计服务。"
              : "上传安装包和对应 .sig 文件。客户端按系统及架构检查更新，同一版本仅通知一次。"
          }
        />
        {error && <Alert type="error" showIcon title={error} />}
        <Card
          title={meter ? "统计程序版本" : "桌面版本"}
          extra={
            <Space>
              <Button
                onClick={() => void load()}
                loading={loading}
                disabled={busy || uploadBusy}
              >
                刷新列表
              </Button>
              <Button
                type="primary"
                disabled={busy || uploadBusy}
                onClick={() => {
                  form.resetFields();
                  setCreateOpen(true);
                }}
              >
                新建版本
              </Button>
            </Space>
          }
        >
          <Table<DesktopRelease>
            rowKey="id"
            dataSource={rows}
            loading={loading}
            pagination={{ pageSize: 10 }}
            scroll={{ x: 950 }}
            columns={[
              {
                title: "版本 / 状态",
                width: 150,
                render: (_, row) => (
                  <Space orientation="vertical">
                    <Typography.Text strong>v{row.version}</Typography.Text>
                    <Tag
                      color={row.status === "PUBLISHED" ? "success" : "default"}
                    >
                      {status[row.status]}
                    </Tag>
                  </Space>
                ),
              },
              {
                title: "更新说明",
                dataIndex: "notes",
                width: 260,
                render: (value) => (
                  <div
                    style={{
                      whiteSpace: "pre-wrap",
                      overflowWrap: "anywhere",
                      maxHeight: 150,
                      overflow: "auto",
                    }}
                  >
                    {value || "—"}
                  </div>
                ),
              },
              {
                title: "安装包",
                width: 300,
                render: (_, row) =>
                  row.artifacts.length ? (
                    <Space orientation="vertical">
                      {row.artifacts.map((file) => (
                        <div key={file.id}>
                          <Typography.Text strong>
                            {file.target === "darwin"
                              ? "macOS"
                              : file.target === "windows"
                                ? "Windows"
                                : "Linux"}{" "}
                            · {file.arch}
                          </Typography.Text>
                          <br />
                          <Typography.Text type="secondary">
                            {size(file.sizeBytes)} · 签名已验证
                          </Typography.Text>
                          <div
                            title={`SHA-256: ${file.sha256}`}
                            style={{
                              maxWidth: 280,
                              overflowWrap: "anywhere",
                              fontSize: 12,
                            }}
                          >
                            {file.filename}
                          </div>
                        </div>
                      ))}
                    </Space>
                  ) : (
                    <Typography.Text type="secondary">尚未上传</Typography.Text>
                  ),
              },
              {
                title: "发布时间",
                width: 175,
                render: (_, row) => (
                  <span style={{ whiteSpace: "nowrap" }}>
                    {row.publishedAt
                      ? new Date(row.publishedAt).toLocaleString("zh-CN")
                      : "尚未发布"}
                  </span>
                ),
              },
              {
                title: "操作",
                width: 190,
                render: (_, row) => (
                  <Space wrap>
                    {row.status === "DRAFT" && (
                      <Button
                        disabled={busy || uploadBusy}
                        onClick={() => {
                          setUpload(row);
                          setError("");
                        }}
                      >
                        上传安装包
                      </Button>
                    )}
                    {row.status !== "PUBLISHED" ? (
                      <Popconfirm
                        title={`发布 v${row.version}？`}
                        description={
                          meter
                            ? "发布后统计服务会自动强制更新，不向用户弹出通知。"
                            : "匹配平台的客户端下次检查将收到此版本。"
                        }
                        onConfirm={() =>
                          perform(() =>
                            changeDesktopRelease(row, "publish", kind),
                          )
                        }
                      >
                        <Button
                          type="primary"
                          disabled={busy || uploadBusy || !row.artifacts.length}
                        >
                          发布
                        </Button>
                      </Popconfirm>
                    ) : (
                      <Popconfirm
                        title={`撤回 v${row.version}？`}
                        description="撤回后将停止提供该版本的更新与下载。"
                        onConfirm={() =>
                          perform(() =>
                            changeDesktopRelease(row, "withdraw", kind),
                          )
                        }
                      >
                        <Button disabled={busy || uploadBusy}>撤回</Button>
                      </Popconfirm>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </Card>
      </Space>
      <Modal
        title={meter ? "新建统计程序版本" : "新建桌面版本"}
        open={createOpen}
        confirmLoading={busy}
        onCancel={() => {
          if (!busy) setCreateOpen(false);
        }}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) =>
            void perform(
              () =>
                createDesktopRelease(values.version, values.notes || "", kind),
              () => setCreateOpen(false),
            )
          }
        >
          <Form.Item
            name="version"
            label="版本号"
            rules={[
              {
                required: true,
                pattern: /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/,
                message: "请输入三段数字版本号，例如 0.4.12",
              },
            ]}
          >
            <Input
              maxLength={32}
              placeholder={meter ? "例如 0.2.13" : "例如 0.4.14"}
            />
          </Form.Item>
          <Form.Item name="notes" label="更新说明">
            <Input.TextArea rows={5} maxLength={4000} showCount />
          </Form.Item>
        </Form>
        {error && <Alert type="error" title={error} />}
      </Modal>
      {upload && (
        <ReleaseUploadModal
          key={`${kind}:${upload.id}`}
          release={upload}
          kind={kind}
          onActivity={setUploadBusy}
          onClose={() => setUpload(undefined)}
          onComplete={() => {
            setUpload(undefined);
            void load();
          }}
        />
      )}
    </PageContainer>
  );
}
