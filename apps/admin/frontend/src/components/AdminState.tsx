import { Button, Card, Result, Skeleton } from 'antd';

export function getErrorMessage(error: unknown, fallback = '请求失败，请稍后重试'): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function LoadingState({ label }: { label: string }) {
  return (
    <Card aria-busy="true" aria-label={label} className="cqg-state-card">
      <Skeleton active paragraph={{ rows: 3 }} />
    </Card>
  );
}

export function ErrorState({
  description,
  onRetry,
  title,
}: {
  description: string;
  onRetry: () => void;
  title: string;
}) {
  return (
    <Result
      extra={
        <Button onClick={onRetry} type="primary">
          重试
        </Button>
      }
      status="error"
      subTitle={description}
      title={title}
    />
  );
}
