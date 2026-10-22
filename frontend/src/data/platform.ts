export const navigationSections: any[] = [
  {
    id: 'chat',
    title: '问答交互引擎',
    icon: 'ChatDotRound',
    items: [
      {
        title: '问答交互引擎',
        path: '/chat',
        routeName: 'chat',
      },
    ],
  },
  {
    id: 'agent-assistant',
    title: '智能体助手',
    icon: 'MagicStick',
    items: [
      {
        title: '智能体助手',
        path: '/agent-assistant',
        routeName: 'agent-assistant',
      },
    ],
  },
  {
    id: 'knowledge',
    title: '知识库中心',
    icon: 'Collection',
    items: [
      {
        title: '知识库中心',
        path: '/knowledge-bases',
        routeName: 'knowledge-bases',
      },
    ],
  },
  {
    id: 'capabilities',
    title: '能力广场',
    icon: 'Grid',
    items: [
      {
        title: '能力广场',
        path: '/capabilities',
        routeName: 'capabilities',
      },
    ],
  },
  {
    id: 'applications',
    title: '场景应用',
    icon: 'Tools',
    items: [
      {
        title: '场景应用',
        path: '/applications',
        routeName: 'applications',
      },
    ],
  },
  {
    id: 'repos',
    title: '资源仓库',
    icon: 'Box',
    collapsible: true,
    items: [
      {
        title: '模型仓库',
        path: '/models',
        routeName: 'models',
      },
      {
        title: '数据集仓库',
        path: '/datasets',
        routeName: 'datasets',
      },
    ],
  },
]

export const pageDescriptions: Record<string, string> = {
  capabilities: '浏览并调用平台内集成的三方平台能力。',
  applications: '浏览并调用平台内开放的Agentic工作流场景应用。',
  models: '浏览和下载不同类型的基础模型。',
  datasets: '上传、下载和管理训练数据集资产。',
}

