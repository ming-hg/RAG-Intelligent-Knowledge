import { defineStore } from 'pinia'

export const useKnowledgeStore = defineStore('knowledge', {
  state: () => ({
    documentList: [], // 已上传文档：[{fileName: 'xxx', size: 'xxx', time: 'xxx'}]
    knowledgeStatus: 'normal', // normal/loading/error
    uploadProgress: 0, // 上传进度（0-100）
    uploadError: '' // 上传错误信息
  }),
  actions: {
    // 更新上传进度
    updateUploadProgress(progress) {
      this.uploadProgress = progress
    },
    // 重置上传状态（上传完成/失败后调用）
    resetUploadState() {
      this.uploadProgress = 0
      this.uploadError = ''
      this.knowledgeStatus = 'normal'
    },
    // 重置上传进度
    resetUploadProgress() {
      this.uploadProgress = 0
    },
    // 删除文档
    deleteDocument(fileName) {
      this.documentList = this.documentList.filter(doc => doc.fileName !== fileName)
    },
    // 设置上传错误
    setUploadError(msg) {
      this.uploadError = msg
      this.knowledgeStatus = 'error'
    },
    // 添加上传成功的文档
    addDocument(document) {
      this.documentList.push(document)
    }
  },
  persist: {
    key: 'knowledge-store',
    paths: ['documentList']
  }
})