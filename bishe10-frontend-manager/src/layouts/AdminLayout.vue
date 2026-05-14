<template>
  <el-container class="admin-shell">
    <el-aside class="admin-aside" width="232px">
      <div class="brand">
        <span class="brand-mark">B</span>
        <span class="brand-text">Blinder小程序管理端</span>
      </div>

      <el-menu
        router
        :default-active="activeMenu"
        class="side-menu"
        background-color="#111827"
        text-color="#cbd5e1"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>数据总览</span>
        </el-menu-item>
        <el-menu-item index="/audit-logs">
          <el-icon><Tickets /></el-icon>
          <span>系统日志</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><UserFilled /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/news-sources">
          <el-icon><Document /></el-icon>
          <span>新闻源管理</span>
        </el-menu-item>
        <el-menu-item index="/news-algorithm">
          <el-icon><TrendCharts /></el-icon>
          <span>新闻算法管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <div>
          <div class="page-title">{{ pageTitle }}</div>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>后台管理</el-breadcrumb-item>
            <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <el-dropdown trigger="click" @command="handleCommand">
          <button class="admin-profile" type="button">
            <span class="profile-avatar">{{ avatarText }}</span>
            <span class="profile-name">{{ authStore.displayName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="admin-main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  DataAnalysis,
  Document,
  SwitchButton,
  Tickets,
  TrendCharts,
  UserFilled
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => route.meta.title || '控制台')
const avatarText = computed(() => authStore.displayName.slice(0, 1).toUpperCase())

async function handleCommand(command) {
  if (command !== 'logout') return
  await authStore.logout()
  ElMessage.success('已退出登录')
  router.replace({ name: 'login' })
}

function handleUnauthorized() {
  authStore.clear()
  router.replace({ name: 'login' })
}

onMounted(() => {
  window.addEventListener('bishe10-manager:unauthorized', handleUnauthorized)
})

onUnmounted(() => {
  window.removeEventListener('bishe10-manager:unauthorized', handleUnauthorized)
})
</script>
