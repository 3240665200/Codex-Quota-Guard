import { Result } from 'antd';
import { Link } from '@umijs/max';

export default function ForbiddenPage() {
  return (
    <main className="cqg-exception-page">
      <Result
        extra={<Link to="/user/login">返回登录</Link>}
        status="403"
        subTitle="当前账号没有访问管理员控制台的权限。"
        title="无权限访问"
      />
    </main>
  );
}
