<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../utils/request'

// 记忆列表
const memoryList = ref([])
const loading = ref(false)

// 编辑对话框
const editVisible = ref(false)
const editingId = ref(null)
const editContent = ref('')

// 格式化时间
const formatTime = (time) => {
  if (!time) return ''
  return time.replace('T', ' ').substring(0, 19)
}

// 加载记忆列表
const loadMemories = async () => {
  loading.value = true
  try {
    const res = await request.get('/memory/list')
    memoryList.value = res.data || []
  } catch {
    // 错误已由拦截器处理
  } finally {
    loading.value = false
  }
}

// 打开编辑对话框
const handleEdit = (row) => {
  editingId.value = row.id
  editContent.value = row.content
  editVisible.value = true
}

// 提交修改
const handleSubmit = async () => {
  if (!editContent.value.trim()) {
    ElMessage.warning('内容不能为空')
    return
  }
  try {
    await request.put(`/memory/${editingId.value}`, {
      content: editContent.value.trim(),
    })
    ElMessage.success('修改成功')
    editVisible.value = false
    loadMemories()
  } catch {
    // 错误已由拦截器处理
  }
}

// 删除记忆
const handleDelete = (row) => {
  ElMessageBox.confirm('删除后无法恢复，确定删除这条记忆吗？', '提示', {
    type: 'warning',
    confirmButtonText: '确定',
    cancelButtonText: '取消',
  }).then(async () => {
    try {
      await request.delete(`/memory/${row.id}`)
      ElMessage.success('删除成功')
      loadMemories()
    } catch {
      // 错误已由拦截器处理
    }
  }).catch(() => {})
}

onMounted(() => {
  loadMemories()
})
</script>

<template>
  <div style="padding: 20px;">
    <!-- 说明 -->
    <el-alert
      title="长期记忆是 AI 从对话中自动提取的用户偏好，跨所有会话生效。修改和删除会同步更新向量库。"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 20px;"
    />

    <!-- 记忆列表 -->
    <el-table :data="memoryList" v-loading="loading" border style="width: 100%;">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="content" label="记忆内容" min-width="300" show-overflow-tooltip />
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">
          {{ formatTime(row.createTime) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="handleEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 空状态提示 -->
    <div v-if="!loading && memoryList.length === 0" style="text-align: center; padding: 40px; color: #999;">
      <p>暂无记忆记录</p>
      <p style="font-size: 13px;">多和智能助手聊几句，AI 会自动记住你的偏好</p>
    </div>

    <!-- 编辑对话框 -->
    <el-dialog v-model="editVisible" title="编辑记忆" width="500px">
      <el-input
        v-model="editContent"
        type="textarea"
        :rows="4"
        placeholder="请输入记忆内容"
      />
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
