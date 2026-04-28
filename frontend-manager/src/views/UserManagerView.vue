<template>
  <section class="content-stack">
    <div class="toolbar-panel">
      <el-form :model="query" class="query-form" label-width="72px" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model.trim="query.keyword"
            clearable
            placeholder="用户名 / 邮箱 / 昵称 / 手机号"
            @keyup.enter="handleSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部状态">
            <el-option label="启用" value="enabled" />
            <el-option label="停用" value="disabled" />
          </el-select>
        </el-form-item>

        <div class="query-actions">
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </div>
      </el-form>
    </div>

    <div class="table-panel">
      <div class="table-header">
        <div>
          <h2>用户列表</h2>
          <span>{{ pagination.total }} 条记录</span>
        </div>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新增用户</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="users"
        row-key="id"
        border
        stripe
        class="user-table"
        empty-text="暂无用户数据"
      >
        <el-table-column prop="id" label="ID" width="92" />
        <el-table-column prop="username" label="用户名" min-width="130" show-overflow-tooltip />
        <el-table-column prop="email" label="邮箱" min-width="210" show-overflow-tooltip />
        <el-table-column prop="nickname" label="昵称" min-width="130" show-overflow-tooltip />
        <el-table-column prop="phone" label="手机号" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'disabled' ? 'info' : 'success'" effect="light">
              {{ row.status === 'disabled' ? '停用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="170" show-overflow-tooltip />
        <el-table-column prop="lastLoginAt" label="最近登录" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastLoginAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="156" fixed="right" align="center">
          <template #default="{ row }">
            <el-tooltip content="编辑" placement="top">
              <el-button circle type="primary" :icon="Edit" @click="openEditDialog(row)" />
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button circle type="danger" :icon="Delete" @click="handleDelete(row)" />
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="loadUsers"
          @current-change="loadUsers"
        />
      </div>
    </div>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.mode === 'create' ? '新增用户' : '编辑用户'"
      width="560px"
      destroy-on-close
      @closed="resetForm"
    >
      <el-form
        ref="userFormRef"
        :model="userForm"
        :rules="userRules"
        label-width="86px"
        class="user-form"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model.trim="userForm.username" placeholder="请输入用户名" />
        </el-form-item>

        <el-form-item label="邮箱" prop="email">
          <el-input v-model.trim="userForm.email" placeholder="请输入邮箱" />
        </el-form-item>

        <el-form-item label="昵称" prop="nickname">
          <el-input v-model.trim="userForm.nickname" placeholder="请输入昵称" />
        </el-form-item>

        <el-form-item label="手机号" prop="phone">
          <el-input v-model.trim="userForm.phone" placeholder="请输入手机号" />
        </el-form-item>

        <el-form-item :label="dialog.mode === 'create' ? '密码' : '新密码'" prop="password">
          <el-input
            v-model="userForm.password"
            show-password
            :placeholder="dialog.mode === 'create' ? '请输入密码' : '留空则不修改'"
          />
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-segmented v-model="userForm.status" :options="statusOptions" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Delete,
  Edit,
  Plus,
  Refresh,
  Search
} from '@element-plus/icons-vue'
import {
  createUser,
  deleteUser,
  listUsers,
  updateUser
} from '@/api/users'

const loading = ref(false)
const saving = ref(false)
const users = ref([])
const userFormRef = ref()

const query = reactive({
  keyword: '',
  status: ''
})

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

const dialog = reactive({
  visible: false,
  mode: 'create',
  currentId: null
})

const userForm = reactive(defaultUserForm())

const statusOptions = [
  { label: '启用', value: 'enabled' },
  { label: '停用', value: 'disabled' }
]

const passwordRule = computed(() => ({
  validator(_rule, value, callback) {
    if (dialog.mode === 'create' && !value) {
      callback(new Error('请输入密码'))
      return
    }
    if (value && value.length < 6) {
      callback(new Error('密码至少 6 位'))
      return
    }
    callback()
  },
  trigger: 'blur'
}))

const userRules = computed(() => ({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 32, message: '用户名长度为 2-32 位', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  nickname: [{ max: 40, message: '昵称不能超过 40 位', trigger: 'blur' }],
  phone: [
    {
      pattern: /^$|^1[3-9]\d{9}$/,
      message: '手机号格式不正确',
      trigger: 'blur'
    }
  ],
  password: [passwordRule.value],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}))

function defaultUserForm() {
  return {
    username: '',
    email: '',
    nickname: '',
    phone: '',
    password: '',
    status: 'enabled'
  }
}

function assignForm(next) {
  Object.assign(userForm, defaultUserForm(), next)
}

async function loadUsers() {
  loading.value = true
  try {
    const data = await listUsers({
      keyword: query.keyword,
      status: query.status,
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize
    })
    users.value = data.records
    pagination.total = data.total
  } catch (error) {
    ElMessage.error(error.message || '用户列表加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.pageNum = 1
  loadUsers()
}

function handleReset() {
  query.keyword = ''
  query.status = ''
  pagination.pageNum = 1
  loadUsers()
}

function openCreateDialog() {
  dialog.mode = 'create'
  dialog.currentId = null
  assignForm()
  dialog.visible = true
}

function openEditDialog(row) {
  dialog.mode = 'edit'
  dialog.currentId = row.id
  assignForm({
    username: row.username || '',
    email: row.email || '',
    nickname: row.nickname || '',
    phone: row.phone || '',
    password: '',
    status: row.status || 'enabled'
  })
  dialog.visible = true
}

async function handleSave() {
  if (!userFormRef.value) return
  await userFormRef.value.validate()

  saving.value = true
  try {
    const payload = { ...userForm }
    if (dialog.mode === 'edit' && !payload.password) {
      delete payload.password
    }

    if (dialog.mode === 'create') {
      await createUser(payload)
      ElMessage.success('用户已新增')
    } else {
      await updateUser(dialog.currentId, payload)
      ElMessage.success('用户已更新')
    }

    dialog.visible = false
    loadUsers()
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除用户「${row.nickname || row.username || row.email}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await deleteUser(row.id)
    ElMessage.success('用户已删除')
    if (users.value.length === 1 && pagination.pageNum > 1) {
      pagination.pageNum -= 1
    }
    loadUsers()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '删除失败')
    }
  }
}

function resetForm() {
  userFormRef.value?.resetFields()
  assignForm()
}

onMounted(loadUsers)
</script>
