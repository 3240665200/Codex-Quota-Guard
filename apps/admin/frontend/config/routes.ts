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
    component: './teams',
    icon: 'team',
    name: '团队与额度策略',
    path: '/teams',
  },
  {
    access: 'canAdmin',
    component: './official-accounts',
    icon: 'profile',
    name: '官方账户',
    path: '/official-accounts',
  },
  {
    access: 'canAdmin',
    component: './proxy-nodes',
    icon: 'global',
    name: '代理节点',
    path: '/proxy-nodes',
  },
  {
    access: 'canAdmin',
    component: './quotas',
    icon: 'fund',
    name: '额度查看',
    path: '/quotas',
  },
  {
    access: 'canAdmin',
    component: './usage',
    icon: 'barChart',
    name: '用量统计',
    path: '/usage',
  },
  {
    access: 'canAdmin',
    component: './quotas/user',
    layout: true,
    name: '用户额度',
    path: '/quotas/users/:id',
    hideInMenu: true,
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
