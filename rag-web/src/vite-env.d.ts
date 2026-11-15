/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

// Element Plus 只给了 .mjs 产物没有类型入口；语言包结构由它自己保证，这里只让插件选项过类型
declare module 'element-plus/dist/locale/zh-cn.mjs' {
  const zhCn: any
  export default zhCn
}
