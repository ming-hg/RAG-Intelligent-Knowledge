<template>
  <div class="loading-container" v-if="visible">
    <div class="loading-mask"></div>
    <div class="loading-content">
      <!-- <el-spinner size="50" class="loading-spinner"></el-spinner> -->
      <div class="loading-text">{{ text }}</div>
    </div>
  </div>
</template>

<script setup>
// import { ElSpinner } from 'element-plus'

// 接收父组件参数
const props = defineProps({
  // 是否显示加载状态
  visible: { type: Boolean, default: false },
  // 加载提示文本
  text: { type: String, default: '加载中...' }
})
</script>

<style scoped>
.loading-container {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.loading-mask {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: rgba(0, 0, 0, 0.3);
  backdrop-filter: blur(2px);
}

.loading-content {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background-color: #fff;
  padding: 32px;
  border-radius: 12px;
}

.loading-spinner {
  color: #409eff;
  margin-bottom: 16px;
}

.loading-text {
  font-size: 16px;
  color: #333;
}
</style>