<template>
  <main class="not-found">
    <div>
      <h1>404</h1>
      <p>页面不存在</p>
      <el-button type="primary" @click="$router.replace('/dashboard')">返回后台</el-button>
    </div>
  </main>
</template>
