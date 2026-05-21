import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '仪表盘' }
      },
      {
        path: 'chat/tokens',
        name: 'TokenRecords',
        component: () => import('../views/chat/TokenRecords.vue'),
        meta: { title: 'Token 记录' }
      },
      {
        path: 'users',
        name: 'UserManagement',
        component: () => import('../views/user/index.vue'),
        meta: { title: '用户管理' }
      },
      {
        path: 'llm',
        name: 'LLMManagement',
        component: () => import('../views/llm/index.vue'),
        meta: { title: '模型管理' }
      },
      {
        path: 'milvus',
        name: 'MilvusManagement',
        component: () => import('../views/milvus/index.vue'),
        meta: { title: '向量库管理' }
      },
      {
        path: 'milvus/stats',
        name: 'PhysicalStats',
        component: () => import('../views/milvus/PhysicalStats.vue'),
        meta: { title: '物理统计' }
      },
      {
        path: 'evaluate',
        name: 'EvaluateManagement',
        component: () => import('../views/evaluate/index.vue'),
        meta: { title: '评估管理' }
      },
      {
        path: 'etl/pipeline',
        name: 'PipelineConfig',
        component: () => import('../views/etl/PipelineConfig.vue'),
        meta: { title: 'ETL 流水线' }
      },
      {
        path: 'mcp/tools',
        name: 'ToolsManager',
        component: () => import('../views/mcp/ToolsManager.vue'),
        meta: { title: 'MCP 工具' }
      },
      {
        path: 'announcement',
        name: 'AnnouncementManager',
        component: () => import('../views/announcement/AnnouncementManager.vue'),
        meta: { title: '公告管理' }
      },
      {
        path: 'intent/tree',
        name: 'IntentTree',
        component: () => import('../views/intent/IntentTree.vue'),
        meta: { title: '意图树' }
      },
      {
        path: 'trace/info',
        name: 'TraceInfo',
        component: () => import('../views/trace/TraceInfo.vue'),
        meta: { title: '调用链追踪' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
