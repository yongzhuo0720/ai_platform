export const ADMIN_ACCESS_KEY = 'admin:access'

export const adminMenus = [
  {
    title: '用户管理',
    path: '/admin/users',
    routeName: 'admin-users',
    icon: 'User',
  },
  {
    title: '角色管理',
    path: '/admin/roles',
    routeName: 'admin-roles',
    icon: 'Avatar',
  },
  {
    title: '部门管理',
    path: '/admin/units',
    routeName: 'admin-units',
    icon: 'OfficeBuilding',
  },
  {
    title: '知识库管理',
    path: '/admin/knowledge-bases',
    routeName: 'admin-knowledge-bases',
    icon: 'Collection',
  },
  {
    title: 'Dify配置管理',
    path: '/admin/dify-config',
    routeName: 'admin-dify-config',
    icon: 'Tools',
  },
  {
    title: '模型配置',
    path: '/admin/models-config',
    routeName: 'admin-models-config',
    icon: 'Cpu',
  },
  {
    title: 'AI调用治理',
    path: '/admin/ai-gateway',
    routeName: 'admin-ai-gateway',
    icon: 'DataLine',
  },
  {
    title: '系统日志',
    path: '/admin/logs',
    routeName: 'admin-logs',
    icon: 'Document',
  },
]

export const aiPlatformMenus: any[] = [
  {
    id: 'admin-access',
    title: '后台管理',
    icon: 'Setting',
    items: [
      { title: '后台管理', path: '/admin', routeName: 'admin:access' },
    ],
  },
  {
    id: 'chat',
    title: '问答交互引擎',
    icon: 'ChatDotRound',
    items: [
      { title: '问答交互引擎', path: '/chat', routeName: 'chat' },
    ],
  },
  {
    id: 'knowledge',
    title: '知识库中心',
    icon: 'Collection',
    items: [
      { title: '知识库中心', path: '/knowledge-bases', routeName: 'knowledge-bases' },
    ],
  },
  {
    id: 'capabilities',
    title: '能力广场',
    icon: 'Grid',
    items: [
      { title: '能力广场', path: '/capabilities', routeName: 'capabilities' },
    ],
  },
  {
    id: 'tools',
    title: '场景应用',
    icon: 'Tools',
    items: [
      { title: '场景应用', path: '/applications', routeName: 'applications' },
    ],
  },
  {
    id: 'repos',
    title: '资源仓库',
    icon: 'Box',
    collapsible: true,
    items: [
      { title: '模型仓库', path: '/models', routeName: 'models' },
      { title: '数据集仓库', path: '/datasets', routeName: 'datasets' },
    ],
  },
]
