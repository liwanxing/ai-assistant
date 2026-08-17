<template>
  <view class="memory-page">
    <!-- 顶部提示 -->
    <view class="tip-bar">
      <text class="tip-text">💡 AI 从对话中自动提取的长期记忆，跨会话生效</text>
    </view>

    <!-- 记忆列表 -->
    <scroll-view class="memory-list" scroll-y>
      <view v-if="memories.length === 0 && !loading" class="empty-state">
        <text class="empty-icon">🧠</text>
        <text class="empty-text">暂无记忆记录</text>
        <text class="empty-sub">多和智能助手聊几句，AI 会自动记住你的偏好</text>
      </view>

      <view v-for="item in memories" :key="item.id" class="memory-card">
        <view class="card-content">
          <text class="card-text">{{ item.content }}</text>
          <text class="card-time">{{ item.createTime }}</text>
        </view>
        <view class="card-actions">
          <text class="action-btn edit" @click="editMemory(item)">✏️</text>
          <text class="action-btn delete" @click="deleteMemory(item)">🗑️</text>
        </view>
      </view>
    </scroll-view>

    <!-- 编辑弹窗 -->
    <view class="modal-mask" v-if="showEdit" @click="showEdit = false">
      <view class="modal-box" @click.stop>
        <text class="modal-title">编辑记忆</text>
        <textarea class="modal-textarea" v-model="editContent" placeholder="请输入记忆内容" />
        <view class="modal-footer">
          <text class="modal-btn cancel" @click="showEdit = false">取消</text>
          <text class="modal-btn confirm" @click="saveEdit">确定</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const memories = ref([
  { id: 1, content: '示例记忆：用户喜欢简洁的回答风格', createTime: '2026-08-16 10:00:00' }
])
const loading = ref(false)
const showEdit = ref(false)
const editContent = ref('')
const editingId = ref<number | null>(null)

// TODO: Step 4 接入后端 API
const editMemory = (item: any) => {
  editingId.value = item.id
  editContent.value = item.content
  showEdit.value = true
}

const saveEdit = () => {
  // TODO: Step 4 调用 PUT /memory/{id}
  showEdit.value = false
  uni.showToast({ title: '保存成功', icon: 'success' })
}

const deleteMemory = (item: any) => {
  // @ts-ignore
  uni.showModal({
    title: '提示',
    content: '删除后无法恢复，确定删除这条记忆吗？',
    success: (res) => {
      if (res.confirm) {
        // TODO: Step 4 调用 DELETE /memory/{id}
        memories.value = memories.value.filter(m => m.id !== item.id)
        uni.showToast({ title: '删除成功', icon: 'success' })
      }
    }
  })
}
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
}
.card-time {
  font-size: 12px;
  color: #999;
  margin-top: 6px;
}
.card-actions {
  display: flex;
  gap: 12px;
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
</style>
