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
    title: 'Dify 配置管理',
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
    title: 'AI 调用治理',
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
      { title: '知识检索', path: '/kb-search', routeName: 'kb-search' },
      { title: 'RAG 评估', path: '/rag-eval', routeName: 'rag-eval' },
    ],
  },
  {
    id: 'agents',
    title: 'Agent 工作台',
    icon: 'Tools',
    items: [
      { title: 'Agent 工作台', path: '/agents', routeName: 'agents' },
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
  {
    id: 'usage',
    title: '用量中心',
    icon: 'DataLine',
    items: [
      { title: '我的用量', path: '/my-usage', routeName: 'my-usage' },
      { title: 'API 密钥', path: '/api-keys', routeName: 'api-keys' },
    ],
  },
]
