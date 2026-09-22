export default [
  {
    layout: false,
    path: '/user',
    routes: [
      {
        component: './user/login',
        name: '登录',
        path: '/user/login',
      },
      {
        path: '/user',
        redirect: '/user/login',
      },
      {
        component: './exception/404',
        path: '/user/*',
      },
    ],
  },
  {
    access: 'canAdmin',
    component: './dashboard',
    icon: 'dashboard',
    name: '概览',
    path: '/dashboard',
  },
  {
    access: 'canAdmin',
    component: './users',
    icon: 'team',
    name: '用户管理',
    path: '/users',
  },
  {
    access: 'canAdmin',
    component: './codex-accounts',
    icon: 'key',
    name: 'Codex 账户',
    path: '/codex-accounts',
  },
  {
    component: './exception/403',
    layout: false,
    path: '/403',
  },
  {
    path: '/',
    redirect: '/dashboard',
  },
  {
    component: './exception/404',
    layout: false,
    path: '/*',
  },
];
