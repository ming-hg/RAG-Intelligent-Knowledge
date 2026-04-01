<template>
  <div class="knowledge-container">
    <el-page-header content="知识库管理" />

    <!-- 文档上传区 -->
    <div class="upload-container">
      <el-upload
        class="upload-demo"
        drag
        action="/api/knowledge/upload"
        :on-success="handleUploadSuccess"
        :on-error="handleUploadError"
        :on-progress="handleUploadProgress"
        :before-upload="beforeUpload"
        :file-list="fileList"
        accept=".pdf,.docx,.md,.txt"
      >
        <i class="el-icon-upload"></i>
        <div class="el-upload__text">
          <em>点击或拖拽文件</em> 上传（支持PDF/DOCX/MD/TXT格式）
        </div>
        <div class="el-upload__tip" slot="tip">
          单个文件不超过100MB，支持多文件同时上传
        </div>
      </el-upload>

      <!-- 上传进度条 -->
      <el-progress 
        v-if="uploadProgress > 0 && uploadProgress < 100"
        :percentage="uploadProgress" 
        status="info"
        class="upload-progress"
      />
    </div>

    <!-- 知识库状态面板 -->
    <div class="status-panel">
      <el-card shadow="hover" v-for="(item, index) in statusList" :key="index">
        <div class="status-item">
          <div class="status-label">{{ item.label }}</div>
          <div class="status-value">{{ item.value }}</div>
        </div>
      </el-card>
    </div>

    <!-- 文档列表区 -->
    <div class="document-list-container">
      <div class="list-header">
        <el-input 
          placeholder="搜索文档名称" 
          v-model="searchKey" 
          class="search-input"
          size="small"
        />
        <el-button 
          type="danger" 
          size="small" 
          @click="deleteSelectedDocuments"
          :disabled="selectedDocumentIds.length === 0"
        >
          批量删除
        </el-button>
      </div>

      <el-table
        :data="filteredDocumentList"
        border
        stripe
        :header-cell-style="{ background: '#f9fafb' }"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="55" />
        <el-table-column label="文档名称" prop="fileName" align="center" />
        <el-table-column label="上传时间" prop="uploadTime" align="center">
          <template #default="scope">
            {{ formatTime(scope.row.uploadTime) }}
          </template>
        </el-table-column>
        <el-table-column label="文件大小" prop="fileSize" align="center">
          <template #default="scope">
            {{ formatFileSize(scope.row.fileSize) }}
          </template>
        </el-table-column>
        <el-table-column label="分块数量" prop="chunkCount" align="center" />
        <el-table-column label="操作" align="center" width="120">
          <template #default="scope">
            <el-button 
              type="text" 
              color="danger" 
              @click="deleteSingleDocument(scope.row.fileName)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="empty-tip" v-if="filteredDocumentList.length === 0">
        暂无上传的文档，请上传文档加入知识库
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useKnowledgeStore } from '@/store/knowledgeStore'
import { ElPageHeader, ElUpload, ElProgress, ElCard, ElInput, ElButton, ElTable, ElTableColumn, ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'

// Pinia状态
const knowledgeStore = useKnowledgeStore()
const documentList = ref(knowledgeStore.documentList)
const uploadProgress = ref(knowledgeStore.uploadProgress)
const fileList = ref([])
const searchKey = ref('')
const selectedDocumentIds = ref([]) // 选中的文档ID（此处用文件名作为唯一标识）

// 监听状态变化，同步更新
watch(() => knowledgeStore.documentList, (val) => {
  documentList.value = val
}, { deep: true })

watch(() => knowledgeStore.uploadProgress, (val) => {
  uploadProgress.value = val
})

// 知识库状态面板数据
const statusList = computed(() => [
  { label: '文档总数', value: documentList.value.length },
  { label: '总块数', value: documentList.value.reduce((total, item) => total + item.chunkCount, 0) },
  { label: '总大小', value: formatFileSize(documentList.value.reduce((total, item) => total + item.fileSize, 0)) }
])

// 过滤文档列表（搜索功能）
const filteredDocumentList = computed(() => {
  if (!searchKey.value.trim()) return documentList.value
  return documentList.value.filter(item => 
    item.fileName.toLowerCase().includes(searchKey.value.trim().toLowerCase())
  )
})

// 上传前校验（格式、大小）
const beforeUpload = (file) => {
  const allowedTypes = ['application/pdf', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'text/plain', 'text/markdown']
  const isAllowedType = allowedTypes.includes(file.type)
  const isAllowedSize = file.size <= 100 * 1024 * 1024 // 100MB

  if (!isAllowedType) {
    ElMessage.error('请上传PDF/DOCX/MD/TXT格式的文件')
    return false
  }
  if (!isAllowedSize) {
    ElMessage.error('单个文件大小不能超过100MB')
    return false
  }
  return true
}

// 上传进度回调
const handleUploadProgress = (event) => {
  const progress = Math.floor((event.loaded / event.total) * 100)
  knowledgeStore.updateUploadProgress(progress)
}

// 上传成功回调
const handleUploadSuccess = (response, file) => {
  // 重置上传进度
  knowledgeStore.resetUploadProgress()
  // 添加文档到知识库状态
  knowledgeStore.addDocument({
    fileName: file.name,
    uploadTime: new Date().toISOString(),
    fileSize: file.size,
    chunkCount: response.chunkCount
  })
  ElMessage.success('文档上传成功，已加入知识库')
  // 清空文件列表
  fileList.value = []
}

// 上传失败回调
const handleUploadError = (error) => {
  knowledgeStore.resetUploadProgress()
  ElMessage.error('文档上传失败：' + (error.response?.data?.message || '未知错误'))
  fileList.value = []
}

// 格式化时间
const formatTime = (timeStr) => {
  const date = new Date(timeStr)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

// 格式化文件大小（B→KB→MB）
const formatFileSize = (size) => {
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(2) + ' KB'
  return (size / (1024 * 1024)).toFixed(2) + ' MB'
}

// 表格选择事件（批量删除）
const handleSelectionChange = (val) => {
  selectedDocumentIds.value = val.map(item => item.fileName)
}

// 单条删除文档
const deleteSingleDocument = async (fileName) => {
  try {
    await ElMessageBox.confirm(
      '确定要删除该文档吗？删除后将从知识库中移除，不可恢复！',
      '确认删除',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    // 调用后端删除接口
    await request.post('/api/knowledge/delete', { fileName })
    // 更新本地状态
    knowledgeStore.deleteDocument(fileName)
    ElMessage.success('文档删除成功')
  } catch (err) {
    // 取消删除不提示
    if (err !== 'cancel') {
      ElMessage.error('文档删除失败：' + (err.message || '未知错误'))
    }
  }
}

// 批量删除文档
const deleteSelectedDocuments = async () => {
  try {
    await ElMessageBox.confirm(
      `确定要删除选中的${selectedDocumentIds.value.length}个文档吗？删除后将从知识库中移除，不可恢复！`,
      '确认批量删除',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    // 调用后端批量删除接口
    await request.post('/api/knowledge/batchDelete', { fileNames: selectedDocumentIds.value })
    // 更新本地状态
    selectedDocumentIds.value.forEach(fileName => {
      knowledgeStore.deleteDocument(fileName)
    })
    ElMessage.success('批量删除成功')
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error('批量删除失败：' + (err.message || '未知错误'))
    }
  }
}
</script>

<style scoped>
.knowledge-container {
  padding: 20px;
}
.upload-container {
  margin: 20px 0;
  padding: 20px;
  background-color: #f9fafb;
  border-radius: 8px;
}
.upload-progress {
  margin-top: 15px;
  width: 100%;
}
.status-panel {
  display: flex;
  gap: 20px;
  margin: 20px 0;
  flex-wrap: wrap;
}
.status-item {
  text-align: center;
  padding: 15px;
}
.status-label {
  font-size: 14px;
  color: #9ca3af;
  margin-bottom: 8px;
}
.status-value {
  font-size: 24px;
  font-weight: 600;
  color: #1f2937;
}
.document-list-container {
  margin-top: 30px;
}
.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}
.search-input {
  width: 300px;
}
.empty-tip {
  text-align: center;
  padding: 50px 0;
  color: #9ca3af;
  font-size: 14px;
  background-color: #f9fafb;
  border-radius: 8px;
}
</style>