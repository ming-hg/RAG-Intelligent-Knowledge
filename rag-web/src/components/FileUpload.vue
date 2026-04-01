<template>
  <el-upload
    class="file-upload-container"
    drag
    :action="uploadUrl"
    :file-list="fileList"
    :before-upload="beforeUpload"
    :on-progress="handleProgress"
    :on-success="handleSuccess"
    :on-error="handleError"
    :auto-upload="true"
    accept=".pdf,.docx,.md,.txt"
  >
    <i class="el-icon-upload"></i>
    <div class="el-upload__text">
      <em>点击或拖拽文件到此处上传</em>
    </div>
    <div class="el-upload__tip" slot="tip">
      支持PDF、DOCX、MD、TXT格式，单文件不超过100MB
    </div>
    <!-- 上传进度条 -->
    <el-progress 
      v-if="uploadProgress > 0 &amp;&amp; uploadProgress < 100"
      :percentage="uploadProgress" 
      class="upload-progress"
      stroke-width="6"
    ></el-progress>
  </el-upload>
</template>

<script setup>
import { ref } from 'vue'
import { ElUpload, ElProgress, ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { useKnowledgeStore } from '@/store/knowledgeStore'

// 上传接口地址（后端文档上传接口）
const uploadUrl = 'http://localhost:3000/api/document/upload'

// 文件列表
const fileList = ref([])
// 上传进度
const uploadProgress = ref(0)

// 知识库状态管理
const knowledgeStore = useKnowledgeStore()

// 上传前校验（格式+大小）
const beforeUpload = (file) => {
  const fileSize = file.size / 1024 / 1024 // 转换为MB
  const fileType = file.name.substring(file.name.lastIndexOf('.'))
  const allowedTypes = ['.pdf', '.docx', '.md', '.txt']

  // 格式校验
  if (!allowedTypes.includes(fileType.toLowerCase())) {
    ElMessage.error('不支持的文件格式，仅支持PDF、DOCX、MD、TXT格式')
    return false
  }

  // 大小校验（100MB）
  if (fileSize > 100) {
    ElMessage.error('单文件大小不能超过100MB')
    return false
  }

  return true
}

// 上传进度回调
const handleProgress = (event, file, fileList) => {
  const progress = Math.floor((event.loaded / event.total) * 100)
  uploadProgress.value = progress
  // 更新全局上传进度
  knowledgeStore.updateUploadProgress(progress)
}

// 上传成功回调
const handleSuccess = (response, file, fileList) => {
  uploadProgress.value = 0
  knowledgeStore.resetUploadProgress()
  // 将上传成功的文档添加到知识库
  knowledgeStore.addDocument({
    fileName: file.name,
    fileSize: (file.size / 1024 / 1024).toFixed(2) + 'MB',
    uploadTime: new Date().toLocaleString(),
    chunkCount: response.data[0].chunkCount
  })
  ElMessage.success(`文件${file.name}上传成功，已加入知识库`)
  // 清空文件列表
  fileList.value = []
}

// 上传失败回调
const handleError = (error, file, fileList) => {
  uploadProgress.value = 0
  knowledgeStore.resetUploadProgress()
  ElMessage.error(`文件${file.name}上传失败，请重试`)
  fileList.value = []
}
</script>

<style scoped>
.file-upload-container {
  width: 100%;
  border: 2px dashed #e5e7eb;
  border-radius: 8px;
  padding: 40px 20px;
  text-align: center;
  margin-bottom: 20px;
}

.upload-progress {
  margin-top: 16px;
  width: 80%;
  margin-left: 10%;
}

.el-upload__text em {
  color: #409eff;
}
</style>