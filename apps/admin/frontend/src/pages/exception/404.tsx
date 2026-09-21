import { Result } from 'antd';
import { Link } from '@umijs/max';

export default function NotFoundPage() {
  return (
    <main className="cqg-exception-page">
      <Result
        extra={<Link to="/dashboard">返回概览</Link>}
        status="404"
        subTitle="你访问的页面不存在。"
        title="页面不存在"
      />
    </main>
  );
}
