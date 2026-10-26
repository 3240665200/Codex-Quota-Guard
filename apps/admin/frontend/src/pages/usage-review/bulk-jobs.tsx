import { Alert, Button, Progress, Space, Typography } from 'antd';
import type { ReviewBulkJob } from '../../services/usageReviewApi';

export const activeJob = (job: ReviewBulkJob) => job.status === 'QUEUED' || job.status === 'RUNNING';
const labels: Record<ReviewBulkJob['status'], string> = {
  DRAFT: '待确认', QUEUED: '已排队', RUNNING: '处理中', COMPLETED: '已完成', COMPLETED_WITH_ERRORS: '已完成，部分需处理', FAILED: '任务已停止',
};
export function BulkJobs({ jobs, error, refresh }: { jobs: ReviewBulkJob[]; error: string; refresh: () => void }) {
  if (!jobs.length && !error) return null;
  return <section className="cqg-review-jobs" aria-label="批量核对任务">
    <Space wrap style={{ justifyContent: 'space-between', width: '100%' }}>
      <Typography.Title level={5} style={{ margin: 0 }}>批量任务进度</Typography.Title>
      <Button size="small" onClick={refresh}>刷新任务</Button>
    </Space>
    <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>任务在服务器后台执行，关闭网页仍会继续；新上传记录不会加入已经确认的任务。</Typography.Paragraph>
    {error && <Alert type="warning" showIcon title={error} />}
    {[...jobs.filter(activeJob), ...jobs.filter((job) => !activeJob(job)).slice(0, 3)].map((job) => <div className="cqg-review-job" key={job.id}>
      <Space wrap style={{ justifyContent: 'space-between', width: '100%' }}>
        <strong>{job.action === 'ARCHIVE' ? '批量归档' : '按原始时间批量入账'} · {labels[job.status]}</strong>
        <Typography.Text type="secondary">{new Date(job.createdAt).toLocaleString('zh-CN')}</Typography.Text>
      </Space>
      <Progress percent={job.totalCount > 0 ? Math.min(100, Math.round(job.processedCount / job.totalCount * 100)) : 0}
        status={job.status === 'FAILED' ? 'exception' : activeJob(job) ? 'active' : undefined} aria-label="批量任务完成进度" />
      <Typography.Text>进度 {job.processedCount.toLocaleString()} / {job.totalCount.toLocaleString()} · 成功 {job.succeededCount.toLocaleString()} · 已被处理而跳过 {job.skippedCount.toLocaleString()} · 失败 {job.failedCount.toLocaleString()}</Typography.Text>
      {job.excludedCount > 0 && <p className="cqg-usage-muted">已排除 {job.excludedCount.toLocaleString()} 条未来时间记录，仍保留待核对。</p>}
      {job.lastErrorCode && <p className="cqg-usage-muted">{job.lastErrorCode === 'ADMIN_UNAVAILABLE' ? '任务创建者的管理员权限已失效，未处理记录仍保留。' : '未完成的记录仍保留待核对，请按用户和时间筛选检查。'}</p>}
    </div>)}
  </section>;
}
