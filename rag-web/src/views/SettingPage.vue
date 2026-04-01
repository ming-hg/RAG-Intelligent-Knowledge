<template>
  <div class="setting-container">
    <el-page-header content="系统设置" />

    <el-card shadow="hover" class="setting-card">
      <template #header>
        <div class="card-header">LM Studio 配置</div>
      </template>
      <el-form :model="lmStudioConfig" label-width="180px" class="setting-form">
        <el-form-item label="LM Studio 服务地址">
          <el-input 
            v-model="lmStudioConfig.baseURL" 
            placeholder="请输入LM Studio本地服务地址"
          />
          <el-form-item__error class="form-tip">默认地址：http://localhost:1234/v1，需与LM Studio启动地址一致</el-form-item__error>
        </el-form-item>
        <el-form-item label="主干模型名称">
          <el-input 
            v-model="lmStudioConfig.model" 
            placeholder="请输入LM Studio中加载的主干模型名称"
          />
          <el-form-item__error class="form-tip">需与LM Studio中加载的模型名称完全一致（如qwen2-7b-instruct）</el-form-item__error>
        </el-form-item>
        <el-form-item label="Embedding模型名称">
          <el-input 
            v-model="lmStudioConfig.embeddingModel" 
            placeholder="请输入LM Studio中加载的Embedding模型名称"
          />
          <el-form-item__error class="form-tip">推荐：bge-base-zh-v1.5 或 m3e-base</el-form-item__error>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="setting-card">
      <template #header>
        <div class="card-header">模型参数配置</div>
      </template>
      <el-form :model="modelParams" label-width="180px" class="setting-form">
        <el-form-item label="温度（temperature）">
          <el-input-number 
            v-model="modelParams.temperature" 
            :min="0" 
            :max="1" 
            :step="0.1"
          />
          <el-form-item__error class="form-tip">RAG场景推荐0.1-0.4，值越低回答越严谨，值越高越灵活</el-form-item__error>
        </el-form-item>
        <el-form-item label="TopP">
          <el-input-number 
            v-model="modelParams.topP" 
            :min="0" 
            :max="1" 
            :step="0.1"
          />
          <el-form-item__error class="form-tip">控制生成内容多样性，推荐0.8，无需频繁调整</el-form-item__error>
        </el-form-item>
        <el-form-item label="检索条数（TopK）">
          <el-input-number 
            v-model="modelParams.topK" 
            :min="1" 
            :max="10" 
            :step="1"
          />
          <el-form-item__error class="form-tip">推荐4-6条，条数越多上下文越全，但响应越慢</el-form-item__error>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="setting-card">
      <template #header>
        <div class="card-header">文本分块配置</div>
      </template>
      <el-form :model="splitParams" label-width="180px" class="setting-form">
        <el-form-item label="单块最大字符数（chunkSize）">
          <el-input-number 
            v-model="splitParams.chunkSize" 
            :min="200" 
            :max="1000" 
            :step="50"
          />
          <el-form-item__error class="form-tip">中文场景推荐500，避免语义断裂</el-form-item__error>
        </el-form-item>
        <el-form-item label="块重叠字符数（chunkOverlap）">
          <el-input-number 
            v-model="splitParams.chunkOverlap" 
            :min="50" 
            :max="200" 
            :step="10"
          />
          <el-form-item__error class="form-tip">推荐100，保留上下文关联，提升检索准确率</el-form-item__error>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="setting-card">
      <template #header>
        <div class="card-header">系统操作</div>
      </template>
      <div class="system-operations">
        <el-button type="danger" @click="clearChatHistory">清空所有会话历史</el-button>
        <el-button type="primary" @click="saveSettings" class="save-button">保存所有配置</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useChatStore } from '@/store/chatStore'
import { ElPageHeader, ElCard, ElForm, ElFormItem, ElInput, ElInputNumber, ElButton, ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'

// 会话状态（用于清空会话历史）
const chatStore = useChatStore()

// LM Studio配置（默认值与后端一致）
const lmStudioConfig = ref({
  baseURL: 'http://localhost:1234/v1',
  model: 'qwen2-7b-instruct',
  embeddingModel: 'bge-base-zh-v1.5'
})

// 模型参数配置
const modelParams = ref({
  temperature: 0.3,
  topP: 0.8,
  topK: 4
})

// 文本分块配置
const splitParams = ref({
  chunkSize: 500,
  chunkOverlap: 100
})

// 页面挂载时，从本地存储加载配置（若有）
onMounted(() => {
  const savedSettings = localStorage.getItem('system-settings')
  if (savedSettings) {
    const { lmStudio, model, split } = JSON.parse(savedSettings)
    lmStudioConfig.value = lmStudio || lmStudioConfig.value
    modelParams.value = model || modelParams.value
    splitParams.value = split || splitParams.value
  }
})

// 保存所有配置（本地存储 + 后端同步）
const saveSettings = async () => {
  try {
    // 1. 本地存储配置
    const settings = {
      lmStudio: lmStudioConfig.value,
      model: modelParams.value,
      split: splitParams.value
    }
    localStorage.setItem('system-settings', JSON.stringify(settings))

    // 2. 同步到后端（更新后端配置）
    await request.post('/api/setting/save', settings)

    ElMessage.success('配置保存成功，部分配置需重启服务后生效')
  } catch (err) {
    ElMessage.error('配置保存失败：' + (err.message || '未知错误'))
  }
}

// 清空所有会话历史
const clearChatHistory = async () => {
  try {
    await ElMessageBox.confirm(
      '确定要清空所有会话历史吗？清空后不可恢复！',
      '确认清空',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    // 清空Pinia状态
    chatStore.sessionList = []
    chatStore.currentSessionId = ''
    chatStore.messageList = []
    // 同步清空后端会话（若有）
    await request.post('/api/chat/clearAll')
    ElMessage.success('所有会话历史已清空')
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error('清空失败：' + (err.message || '未知错误'))
    }
  }
}
</script>

<style scoped>
.setting-container {
  padding: 20px;
}
.setting-card {
  margin-bottom: 20px;
}
.card-header {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}
.setting-form {
  padding: 20px 0;
}
.form-tip {
  color: #9ca3af;
  font-size: 12px;
  margin-top: 5px;
}
.system-operations {
  padding: 20px;
  display: flex;
  gap: 20px;
  align-items: center;
}
.save-button {
  margin-left: auto;
}
</style>