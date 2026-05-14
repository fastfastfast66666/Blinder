import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AdminLayout from '@/layouts/AdminLayout.vue'
import DashboardView from '@/views/DashboardView.vue'
import LoginView from '@/views/LoginView.vue'
import NewsAlgorithmManagerView from '@/views/NewsAlgorithmManagerView.vue'
import NewsSourceManagerView from '@/views/NewsSourceManagerView.vue'
import SystemAuditLogView from '@/views/SystemAuditLogView.vue'
import UserManagerView from '@/views/UserManagerView.vue'
import NotFoundView from '@/views/NotFoundView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { public: true, title: '管理员登录' }
    },
    {
      path: '/',
      component: AdminLayout,
      redirect: '/dashboard',
      meta: { requiresAuth: true },
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: DashboardView,
          meta: { title: '数据总览' }
        },
        {
          path: 'audit-logs',
          name: 'auditLogs',
          component: SystemAuditLogView,
          meta: { title: '系统日志' }
        },
        {
          path: 'users',
          name: 'users',
          component: UserManagerView,
          meta: { title: '用户管理' }
        },
        {
          path: 'news-sources',
          name: 'newsSources',
          component: NewsSourceManagerView,
          meta: { title: '新闻源管理' }
        },
        {
          path: 'news-algorithm',
          name: 'newsAlgorithm',
          component: NewsAlgorithmManagerView,
          meta: { title: '新闻算法管理' }
        }
      ]
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'notFound',
      component: NotFoundView,
      meta: { public: true, title: '页面不存在' }
    }
  ],
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach((to) => {
  const authStore = useAuthStore()
  authStore.restore()

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.name === 'login' && authStore.isAuthenticated) {
    return { name: 'dashboard' }
  }

  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '控制台'} - Bishe10 管理端`
})

export default router
