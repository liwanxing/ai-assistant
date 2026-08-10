<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../utils/request'

// ──────────────────────────────────────
// 数据定义（类似后端的 Service 层变量）
// ──────────────────────────────────────

const router = useRouter()

// 用户列表（表格展示的数据源）
const userList = ref([])
// 表格加载状态
const loading = ref(false)

// 对话框控制：新增和编辑共用一个对话框
const dialogVisible = ref(false)
const dialogTitle = ref('')
// 是否编辑模式（true=编辑，false=新增）
const isEdit = ref(false)
// 当前编辑的用户ID
const editingId = ref(null)

// 表单数据（类似后端的 DTO）
const form = ref({
  username: '',
  password: '',
  nickname: '',
  email: '',
  phone: '',
})

// ──────────────────────────────────────
// 业务方法（类似后端的 Controller 调 Service）
// ──────────────────────────────────────

// 查询所有用户：GET /users
// 后端返回 { code: 200, data: [ {id, username, nickname, ...}, ... ] }
const loadUsers = async () => {
  loading.value = true
  try {
    const res = await request.get('/users')
    userList.value = res.data
  } catch {
    // 错误已在响应拦截器里弹了 ElMessage，这里不用再处理
  } finally {
    loading.value = false
  }
}

// 打开新增对话框
const handleAdd = () => {
  isEdit.value = false
  dialogTitle.value = '新增用户'
  form.value = { username: '', password: '', nickname: '', email: '', phone: '' }
  dialogVisible.value = true
}

// 打开编辑对话框：把当前行的数据填到表单里
const handleEdit = (row) => {
  isEdit.value = true
  dialogTitle.value = '编辑用户'
  editingId.value = row.id
  form.value = {
    username: row.username,  // 仅展示，编辑时不改用户名
    password: '',            // 编辑模式不提交密码（UserUpdateDTO 没有密码字段）
    nickname: row.nickname || '',
    email: row.email || '',
    phone: row.phone || '',
  }
  dialogVisible.value = true
}

// 提交表单：根据 isEdit 调不同接口
const handleSubmit = async () => {
  try {
    if (isEdit.value) {
      // 编辑：PUT /users/{id}，只提交 nickname, email, phone
      await request.put(`/users/${editingId.value}`, {
        nickname: form.value.nickname,
        email: form.value.email,
        phone: form.value.phone,
      })
      ElMessage.success('修改成功')
    } else {
      // 新增：POST /users，提交全部字段
      await request.post('/users', form.value)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadUsers()  // 刷新表格
  } catch {
    // 错误已在拦截器处理
  }
}

// 删除用户：先弹确认框，确认后调 DELETE /users/{id}
const handleDelete = (row) => {
  ElMessageBox.confirm('确定删除该用户吗？', '提示', {
    type: 'warning',
    confirmButtonText: '确定',
    cancelButtonText: '取消',
  }).then(async () => {
    try {
      await request.delete(`/users/${row.id}`)
      ElMessage.success('删除成功')
      loadUsers()
    } catch {
      // 错误已在拦截器处理
    }
  }).catch(() => {})
}

// 退出登录：调后端 /logout 销毁 token，然后清前端 token 跳登录页
const handleLogout = async () => {
  try {
    await request.get('/logout')
  } catch {
    // 即使后端报错，也要清前端 token（防止token残留）
  } finally {
    localStorage.removeItem('satoken')
    router.push('/login')
  }
}

// 页面加载时自动查询用户列表（类似后端的 @PostConstruct）
onMounted(() => {
  loadUsers()
})
</script>

<template>
  <div style="padding: 20px;">
    <!-- 顶部操作栏 -->
    <div style="display: flex; justify-content: space-between; margin-bottom: 20px;">
      <el-button type="primary" @click="handleAdd">新增用户</el-button>
      <el-button type="danger" @click="handleLogout">退出登录</el-button>
    </div>

    <!-- 用户表格 -->
    <!-- :data 绑定数据源，v-loading 绑定加载状态，border 显示边框 -->
    <el-table :data="userList" v-loading="loading" border style="width: 100%;">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" width="120" />
      <el-table-column prop="nickname" label="昵称" width="120" />
      <el-table-column prop="email" label="邮箱" width="200" />
      <el-table-column prop="phone" label="手机号" width="150" />
      <!-- 状态列：用 el-tag 显示标签，正常绿色/禁用红色 -->
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="180" />
      <!-- 操作列：每行一个编辑按钮和一个删除按钮 -->
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="handleEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑对话框（共用一个） -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px">
      <el-form :model="form" label-width="80px">
        <!-- 新增模式：用户名可输入；编辑模式：用户名只读 -->
        <el-form-item label="用户名" v-if="!isEdit">
          <el-input v-model="form.username" placeholder="2-20个字符" />
        </el-form-item>
        <el-form-item label="用户名" v-else>
          <el-input v-model="form.username" disabled />
        </el-form-item>
        <!-- 密码只有新增模式才显示 -->
        <el-form-item label="密码" v-if="!isEdit">
          <el-input v-model="form.password" type="password" placeholder="6-50个字符" show-password />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="请输入昵称" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="请输入手机号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
