<template>
  <el-container class="index-layout-container">
    <!-- 左侧侧边栏 -->
    <el-aside 
      :width="isCollapse ? '64px' : '240px'" 
      class="aside-container"
      :class="{ 'aside-collapse': isCollapse }"
    >
      <!-- 侧边栏头部（Logo） -->
      <div class="aside-header">
        <el-icon :size="24" class="collapse-btn" @click="toggleCollapse">
          <component :is="isCollapse ? Expand : Fold" />
        </el-icon>
        <h1 v-if="!isCollapse" class="logo-text">RAG知识库</h1>
      </div>

      <!-- 侧边栏菜单 -->
      <el-menu 
        default-active="1" 
        class="aside-menu"
        :collapse="isCollapse"
        background-color="#0f172a"
        text-color="#fff"
        active-text-color="#409eff"
        :unique-opened="true"
      >
        <!-- 聊天问答菜单 -->
        <el-menu-item index="1" @click="$router.push('/chat')">
          <el-icon slot="icon"><Message /></el-icon>
          <span slot="title" v-if="!isCollapse">聊天问答</span>
        </el-menu-item>

        <!-- 知识库管理菜单 -->
        <el-menu-item index="2" @click="$router.push('/knowledge')">
          <el-icon slot="icon"><Folder /></el-icon>
          <span slot="title" v-if="!isCollapse">知识库管理</span>
        </el-menu-item>

        <!-- 系统设置菜单 -->
        <el-menu-item index="3" @click="$router.push('/setting')">
          <el-icon slot="icon"><Setting /></el-icon>
          <span slot="title" v-if="!isCollapse">系统设置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 右侧主内容区 -->
    <el-container class="main-container">
      <!-- 顶部导航栏（可选，可添加用户信息、退出按钮） -->
      <el-header class="main-header">
        <div class="header-right">
          <el-dropdown>
            <span class="user-info">
              <el-icon><User /></el-icon>
              <span v-if="!isCollapse">本地用户</span>
            </span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item @click="clearAllData">清空本地数据</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </div>
      </el-header>

<!-- 内容区域（路由占位符） -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
// 引入Element Plus组件和图标
import { 
  ElContainer, ElAside, ElMain, ElHeader, ElMenu, ElMenuItem,
  ElDropdown, ElDropdownMenu, ElDropdownItem, ElIcon, ElLoading
} from 'element-plus'
import { Fold, Expand, Message, Folder, Setting, User } from '@element-plus/icons-vue'
// 引入Pinia状态管理
import { useChatStore } from '@/store/chatStore'
import { useKnowledgeStore } from '@/store/knowledgeStore'

const router = useRouter()
const chatStore = useChatStore()
const knowledgeStore = useKnowledgeStore()

// 侧边栏折叠/展开状态
const isCollapse = ref(false)

// 切换侧边栏折叠状态
const toggleCollapse = () => {
  isCollapse.value = !isCollapse.value
}

// 清空本地数据（会话+文档）
const clearAllData = () => {
  ElLoading.service({ text: '清空数据中...' })
  chatStore.$reset()
  knowledgeStore.$reset()
  ElMessage.success('本地数据已清空')
  ElLoading.service().close()
}
</script>

<style scoped>
.index-layout-container {
  height: 100vh;
  overflow: hidden;
}

/* 侧边栏样式 */
.aside-container {
  background-color: #0f172a;
  color: #fff;
  transition: width 0.3s ease;
  height: 100vh;
  overflow: hidden;
}

.aside-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  height: 60px;
  border-bottom: 1px solid #1e293b;
}

.collapse-btn {
  cursor: pointer;
  color: #fff;
}

.logo-text {
  font-size: 18px;
  font-weight: 600;
  margin: 0;
}

.aside-menu {
  margin-top: 20px;
}

/* 侧边栏折叠样式 */
.aside-collapse .el-menu-item__content {
  display: none !important;
}

/* 主内容区样式 */
.main-container {
  flex: 1;
  overflow: hidden;
}

.main-header {
  height: 60px;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 20px;
}

.user-info {
  display: flex;
  align-items: center;
  cursor: pointer;
  color: #333;
}

.main-content {
  padding: 20px;
  overflow-y: auto;
  height: calc(100vh - 60px);
  background-color: #f8fafc;
}

/* 响应式适配（小屏幕自动折叠侧边栏） */
@media (max-width: 1200px) {
  .aside-container {
    width: 64px !important;
  }
  .isCollapse {
    display: none;
  }
  .aside-header .logo-text {
    display: none;
  }
}
</style>