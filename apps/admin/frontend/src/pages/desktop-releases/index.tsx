import { PageContainer } from '@ant-design/pro-components';
import { Alert, App, Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { getErrorMessage } from '../../components/AdminState';
import { changeDesktopRelease, createDesktopRelease, listDesktopReleases, uploadDesktopArtifact, type DesktopRelease } from '../../services/desktopReleasesApi';

const targets = [{ value: 'windows', label: 'Windows · NSIS (.exe)' }, { value: 'darwin', label: 'macOS (.app.tar.gz)' }, { value: 'linux', label: 'Linux (.AppImage)' }];
const status = { DRAFT: '草稿', PUBLISHED: '已发布', WITHDRAWN: '已撤回' };
const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
export default function DesktopReleasesPage() {
  const { message } = App.useApp();
  const [rows, setRows] = useState<DesktopRelease[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [upload, setUpload] = useState<DesktopRelease>();
  const [target, setTarget] = useState('windows');
  const [arch, setArch] = useState('x86_64');
  const [installer, setInstaller] = useState<File>();
  const [signature, setSignature] = useState<File>();
  const [form] = Form.useForm<{ version: string; notes: string }>();
  const load = useCallback(async () => {
    setLoading(true); setError('');
    try { setRows(await listDesktopReleases()); } catch (failure) { setError(getErrorMessage(failure)); } finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  async function perform(work: () => Promise<unknown>, complete?: () => void) {
    if (busy) return;
    setBusy(true); setError('');
    try { await work(); complete?.(); void message.success('操作成功'); await load(); }
    catch (failure) { setError(getErrorMessage(failure)); }
    finally { setBusy(false); }
  }
  const uploadError = installer && (installer.size === 0 || installer.size > 512 * 1024 * 1024) ? '请选择不超过 512 MiB 的安装包。'
    : signature && (signature.size === 0 || signature.size > 32768) ? '签名文件不能为空且须小于 32 KiB。' : '';
  return <PageContainer title="版本管理" content="管理桌面客户端版本及各平台安装包。发布后，客户端启动时会检查更新。">
    <Space orientation="vertical" size={20} style={{ width: '100%' }}>
      <Alert type="info" showIcon title="上传安装包与对应签名，再发布版本"
        description="仅接受本项目签名打包产生的更新包及 .sig 文件，包内签名版本号须与发布版本一致。Windows、macOS 和 Linux 按系统与架构分别上传；客户端只检查匹配平台。相同版本仅提醒一次，下一次更新请使用更高版本号。" />
      {error && <Alert type="error" showIcon title={error} />}
      <Card title="桌面版本" extra={<Space><Button onClick={() => void load()} loading={loading} disabled={busy}>刷新列表</Button><Button type="primary" disabled={busy} onClick={() => { form.resetFields(); setCreateOpen(true); }}>新建版本</Button></Space>}>
        <Table<DesktopRelease> rowKey="id" dataSource={rows} loading={loading} pagination={{ pageSize: 10 }} scroll={{ x: 950 }} columns={[
          { title: '版本 / 状态', width: 150, render: (_, row) => <Space orientation="vertical"><Typography.Text strong>v{row.version}</Typography.Text><Tag color={row.status === 'PUBLISHED' ? 'success' : 'default'}>{status[row.status]}</Tag></Space> },
          { title: '更新说明', dataIndex: 'notes', width: 260, render: value => <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 150, overflow: 'auto' }}>{value || '—'}</div> },
          { title: '安装包', width: 300, render: (_, row) => row.artifacts.length ? <Space orientation="vertical">{row.artifacts.map(file => <div key={file.id}><Typography.Text strong>{file.target === 'darwin' ? 'macOS' : file.target === 'windows' ? 'Windows' : 'Linux'} · {file.arch}</Typography.Text><br /><Typography.Text type="secondary">{size(file.sizeBytes)} · 签名已验证</Typography.Text><div title={`SHA-256: ${file.sha256}`} style={{ maxWidth: 280, overflowWrap: 'anywhere', fontSize: 12 }}>{file.filename}</div></div>)}</Space> : <Typography.Text type="secondary">尚未上传</Typography.Text> },
          { title: '发布时间', width: 175, render: (_, row) => <span style={{ whiteSpace: 'nowrap' }}>{row.publishedAt ? new Date(row.publishedAt).toLocaleString('zh-CN') : '尚未发布'}</span> },
          { title: '操作', width: 190, render: (_, row) => <Space wrap>
            {row.status === 'DRAFT' && <Button disabled={busy} onClick={() => { setUpload(row); setInstaller(undefined); setSignature(undefined); setError(''); }}>上传安装包</Button>}
            {row.status !== 'PUBLISHED' ? <Popconfirm title={`发布 v${row.version}？`} description="匹配平台的客户端下次检查将收到此版本。" onConfirm={() => perform(() => changeDesktopRelease(row, 'publish'))}><Button type="primary" disabled={busy || !row.artifacts.length}>发布</Button></Popconfirm>
              : <Popconfirm title={`撤回 v${row.version}？`} description="撤回后将停止提供该版本的更新与下载。" onConfirm={() => perform(() => changeDesktopRelease(row, 'withdraw'))}><Button disabled={busy}>撤回</Button></Popconfirm>}
          </Space> },
        ]} />
      </Card>
    </Space>
    <Modal title="新建桌面版本" open={createOpen} confirmLoading={busy} onCancel={() => { if (!busy) setCreateOpen(false); }} onOk={() => form.submit()} destroyOnHidden>
      <Form form={form} layout="vertical" onFinish={values => void perform(() => createDesktopRelease(values.version, values.notes || ''), () => setCreateOpen(false))}>
        <Form.Item name="version" label="版本号" rules={[{ required: true, pattern: /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/, message: '请输入三段数字版本号，例如 0.4.12' }]}><Input maxLength={32} placeholder="例如 0.4.12" /></Form.Item>
        <Form.Item name="notes" label="更新说明"><Input.TextArea rows={5} maxLength={4000} showCount /></Form.Item>
      </Form>
      {error && <Alert type="error" title={error} />}
    </Modal>
    <Modal title={`上传安装包 · v${upload?.version || ''}`} open={!!upload} confirmLoading={busy} okText="上传并校验" okButtonProps={{ disabled: !installer || !signature || !!uploadError }}
      onCancel={() => { if (!busy) setUpload(undefined); }} destroyOnHidden
      onOk={() => { if (upload && installer && signature && !uploadError) void perform(() => uploadDesktopArtifact(upload, target, arch, installer, signature), () => setUpload(undefined)); }}>
      <Space orientation="vertical" size={18} style={{ width: '100%' }}>
        <Alert type="info" title="安装包发布后不能替换，请确认系统和架构" description="上传大文件可能需要一些时间。更新包必须使用与现有客户端相同的发布密钥签名，私钥应留在打包机器上。" />
        <label>系统<Select aria-label="安装包系统" value={target} options={targets} style={{ width: '100%', marginTop: 8 }} disabled={busy} onChange={value => { setTarget(value); setInstaller(undefined); }} /></label>
        <label>架构<Select aria-label="安装包架构" value={arch} style={{ width: '100%', marginTop: 8 }} disabled={busy} onChange={setArch} options={[{ value: 'x86_64', label: 'x64 / Intel (x86_64)' }, { value: 'aarch64', label: 'ARM64 / Apple Silicon (aarch64)' }]} /></label>
        <label>安装包（不超过 512 MiB）<input key={target} type="file" aria-label="安装包" disabled={busy} accept={target === 'windows' ? '.exe' : target === 'darwin' ? '.gz' : '.AppImage'} style={{ display: 'block', marginTop: 8, width: '100%' }} onChange={event => setInstaller(event.target.files?.[0])} /></label>
        <label>对应 .sig 签名文件<input type="file" aria-label="签名文件" accept=".sig" disabled={busy} style={{ display: 'block', marginTop: 8, width: '100%' }} onChange={event => setSignature(event.target.files?.[0])} /></label>
        {(uploadError || error) && <Alert type="error" title={uploadError || error} />}
      </Space>
    </Modal>
  </PageContainer>;
}
