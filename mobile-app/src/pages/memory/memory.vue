<template>
  <view class="memory-page">
    <!-- 顶部提示 -->
    <view class="tip-bar">
      <text class="tip-text">💡 AI 从对话中自动提取的长期记忆，跨会话生效</text>
    </view>

    <!-- 记忆列表 -->
    <scroll-view class="memory-list" scroll-y @scrolltolower="() => {}">
      <!-- 加载中 -->
      <view v-if="loading" class="loading-state">
        <text class="loading-text">加载中...</text>
      </view>

      <!-- 空状态 -->
      <view v-if="!loading && memories.length === 0" class="empty-state">
        <text class="empty-icon">🧠</text>
        <text class="empty-text">暂无记忆记录</text>
        <text class="empty-sub">多和智能助手聊几句，AI 会自动记住你的偏好</text>
      </view>

      <!-- 记忆卡片列表 -->
      <view v-for="item in memories" :key="item.id" class="memory-card">
        <view class="card-content" @click="editMemory(item)">
          <text class="card-text">{{ item.content }}</text>
          <text class="card-time">{{ formatTime(item.createTime) }}</text>
        </view>
        <view class="card-actions">
          <text class="action-btn" @click="editMemory(item)">✏️</text>
          <text class="action-btn" @click="handleDelete(item)">🗑️</text>
        </view>
      </view>
    </scroll-view>

    <!-- 编辑弹窗 -->
    <view class="modal-mask" v-if="showEdit" @click="showEdit = false">
      <view class="modal-box" @click.stop>
        <text class="modal-title">编辑记忆</text>
        <textarea
          class="modal-textarea"
          v-model="editContent"
          placeholder="请输入记忆内容"
        />
        <view class="modal-footer">
          <text class="modal-btn cancel" @click="showEdit = false">取消</text>
          <text class="modal-btn confirm" :class="{ disabled: saving }" @click="saveEdit">
            {{ saving ? '保存中...' : '确定' }}
          </text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getMemoryList, updateMemory, deleteMemory } from '../../api/memory'

// ---- 数据 ----
const memories = ref<any[]>([])
const loading = ref(false)
const showEdit = ref(false)
const editContent = ref('')
const editingId = ref<number | null>(null)
const saving = ref(false)

// ---- 格式化时间 ----
const formatTime = (time: string) => {
  if (!time) return ''
  return time.replace('T', ' ').substring(0, 16)
}

// ---- 加载记忆列表 ----
// onShow：每次页面显示时触发（包括从其他页面返回）
// 为什么不用 onMounted？
//   onMounted 只在页面首次创建时触发
//   用户编辑完记忆返回列表页时，onShow 会触发，可以自动刷新数据
const loadMemories = async () => {
  loading.value = true
  try {
    memories.value = await getMemoryList()
  } catch (e) {
    // 错误已在 request.js 拦截器处理
  } finally {
    loading.value = false
  }
}

// ---- 编辑 ----
const editMemory = (item: any) => {
  editingId.value = item.id
  editContent.value = item.content
  showEdit.value = true
}

const saveEdit = async () => {
  if (!editContent.value.trim()) {
    uni.showToast({ title: '内容不能为空', icon: 'none' })
    return
  }
  if (saving.value) return
  saving.value = true
  try {
    await updateMemory(editingId.value!, editContent.value.trim())
    uni.showToast({ title: '保存成功', icon: 'success' })
    showEdit.value = false
    loadMemories()  // 刷新列表
  } catch (e) {
    // 错误已在 request.js 拦截器处理
  } finally {
    saving.value = false
  }
}

// ---- 删除 ----
const handleDelete = (item: any) => {
  uni.showModal({
    title: '提示',
    content: '删除后无法恢复，确定删除这条记忆吗？',
    success: async (res) => {
      if (res.confirm) {
        try {
          await deleteMemory(item.id)
          uni.showToast({ title: '删除成功', icon: 'success' })
          loadMemories()  // 刷新列表
        } catch (e) {
          // 错误已在 request.js 拦截器处理
        }
      }
    }
  })
}

// ---- 页面显示时加载数据 ----
onShow(() => {
  loadMemories()
})
</script>

<style>
.memory-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: #f5f5f5;
}

/* 提示栏 */
.tip-bar {
  padding: 12px 16px;
  background-color: #ecf5ff;
  border-bottom: 1px solid #d9ecff;
}
.tip-text {
  font-size: 13px;
  color: #409EFF;
}

/* 记忆列表 */
.memory-list {
  flex: 1;
  padding: 12px;
}

.loading-state {
  display: flex;
  justify-content: center;
  margin-top: 60px;
}
.loading-text {
  font-size: 14px;
  color: #999;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-top: 100px;
}
.empty-icon {
  font-size: 50px;
  margin-bottom: 12px;
}
.empty-text {
  font-size: 16px;
  color: #666;
  margin-bottom: 8px;
}
.empty-sub {
  font-size: 13px;
  color: #999;
}

/* 记忆卡片 */
.memory-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-radius: 10px;
  padding: 14px;
  margin-bottom: 10px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}
.card-content {
  flex: 1;
  margin-right: 10px;
}
.card-text {
  font-size: 14px;
  color: #333;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-time {
  font-size: 12px;
  color: #999;
  margin-top: 6px;
  display: block;
}
.card-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.action-btn {
  font-size: 18px;
  padding: 4px;
}

/* 编辑弹窗 */
.modal-mask {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
}
.modal-box {
  width: 85%;
  background-color: #fff;
  border-radius: 12px;
  padding: 20px;
}
.modal-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 14px;
}
.modal-textarea {
  width: 100%;
  min-height: 100px;
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  box-sizing: border-box;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 16px;
}
.modal-btn {
  font-size: 15px;
  padding: 6px 16px;
  border-radius: 6px;
}
.cancel {
  color: #666;
}
.confirm {
  color: #fff;
  background-color: #409EFF;
}
.confirm.disabled {
  opacity: 0.6;
}
</style>
