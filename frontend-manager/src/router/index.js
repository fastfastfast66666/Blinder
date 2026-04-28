import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AdminLayout from '@/layouts/AdminLayout.vue'
import LoginView from '@/views/LoginView.vue'
import NewsAlgorithmManagerView from '@/views/NewsAlgorithmManagerView.vue'
import NewsSourceManagerView from '@/views/NewsSourceManagerView.vue'
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
      redirect: '/users',
      meta: { requiresAuth: true },
      children: [
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
    return { name: 'users' }
  }

  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '控制台'} - Bishe10 管理端`
})

export default router
