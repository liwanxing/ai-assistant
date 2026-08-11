<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Refresh, Delete } from '@element-plus/icons-vue'
import request from '../utils/request'

// ──────────────────────────────────────
// 文档列表数据
// ──────────────────────────────────────

const documents = ref([])
const loading = ref(false)

// 查询文档列表：GET /rag/documents
const fetchDocuments = async () => {
  loading.value = true
  try {
    const res = await request.get('/rag/documents')
    documents.value = res.data || []
  } catch {
    // 错误已在响应拦截器里弹了 ElMessage
  } finally {
    loading.value = false
  }
}

// ──────────────────────────────────────
// 文件上传（异步）
// ──────────────────────────────────────

const uploading = ref(false)
const fileInputRef = ref(null)

// 切分方式：token = 按 token 数量硬切，paragraph = 按段落切分
const splitStrategy = ref('TOKEN')

const strategyOptions = [
  { label: '语义切分', value: 'SEMANTIC' },
  { label: '段落切分', value: 'PARAGRAPH' },
  { label: 'Token切分', value: 'TOKEN' },
]

const triggerFileSelect = () => {
  fileInputRef.value.click()
}

const handleFileChange = async (event) => {
  const file = event.target.files[0]
  if (!file) return

  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('splitStrategy', splitStrategy.value)
    await request.post('/rag/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
    })
    ElMessage.success('文档已上传，后台正在处理中...')
    // 上传后立即刷新列表，能看到新增的 PROCESSING 记录
    fetchDocuments()
  } catch {
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

// ──────────────────────────────────────
// 状态标签颜色映射
// ──────────────────────────────────────

const statusMap = {
  PROCESSING: { text: '处理中', type: 'warning' },
  SUCCESS: { text: '成功', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
}

const getStatusTag = (status) => statusMap[status] || { text: status, type: 'info' }

// 格式化文件大小：字节 → KB/MB
const formatSize = (bytes) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

// ──────────────────────────────────────
// 删除文档：DELETE /rag/documents/{id}
// ──────────────────────────────────────

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.fileName}」吗？删除后知识库中相关内容也会清除。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch {
    return // 用户点了取消
  }

  try {
    await request.delete(`/rag/documents/${row.id}`)
    ElMessage.success('删除成功')
    fetchDocuments()
  } catch {
  }
}

// 页面加载时查询一次
onMounted(() => {
  fetchDocuments()
})
</script>

<template>
  <div>
    <!-- 顶部操作栏 -->
    <div style="margin-bottom: 16px;">
      <input
        ref="fileInputRef"
        type="file"
        accept=".txt,.pdf"
        style="display: none;"
        @change="handleFileChange"
      />
      <el-button type="primary" :icon="Upload" :loading="uploading" @click="triggerFileSelect">
        上传文档
      </el-button>
      <el-select v-model="splitStrategy" style="width: 130px; margin-left: 12px;" size="default">
        <el-option
          v-for="opt in strategyOptions"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <el-button :icon="Refresh" @click="fetchDocuments">刷新</el-button>
      <span style="margin-left: 12px; color: #999; font-size: 13px;">
        语义切分效果最好但最慢，段落切分兼顾速度与语义，Token切分最快
      </span>
    </div>

    <!-- 文档列表 -->
    <el-table :data="documents" v-loading="loading" border style="width: 100%;">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="fileName" label="文件名" min-width="200" />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          {{ row.fileType }}
        </template>
      </el-table-column>
      <el-table-column label="大小" width="100">
        <template #default="{ row }">
          {{ formatSize(row.fileSize) }}
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="切分块数" width="100">
        <template #default="{ row }">
          {{ row.chunkCount || '-' }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="getStatusTag(row.status).type" size="small">
            {{ getStatusTag(row.status).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="errorMessage" label="错误信息" min-width="200">
        <template #default="{ row }">
          <span v-if="row.errorMessage" style="color: #f56c6c;">{{ row.errorMessage }}</span>
          <span v-else style="color: #ccc;">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="上传时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button type="danger" :icon="Delete" size="small" @click="handleDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
