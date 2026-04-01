import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('@/layout/IndexLayout.vue'),
    children: [
      // 聊天问答页面（默认首页）
      { path: '', redirect: '/chat' },
      { path: 'chat', component: () => import('@/views/ChatPage.vue'), name: 'Chat' },
      // 知识库管理页面
      { path: 'knowledge', component: () => import('@/views/KnowledgePage.vue'), name: 'Knowledge' },
      // 系统设置页面
      { path: 'setting', component: () => import('@/views/SettingPage.vue'), name: 'Setting' }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router