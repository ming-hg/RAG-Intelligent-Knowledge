import { defineStore } from 'pinia'
import { healthApi } from '@/api/health'
import type { HealthProbe } from '@/types/api'

export const useHealthStore = defineStore('health', {
  state: () => ({
    checking: false,
    loaded: false,
    ready: false,
    message: '尚未自检',
    probes: [] as HealthProbe[],
  }),

  getters: {
    tone(state): 'idle' | 'ok' | 'warn' | 'down' {
      if (state.probes.some((probe) => probe.status === 'DOWN')) return 'down'
      if (!state.loaded) return 'idle'
      if (state.ready) return 'ok'
      return 'warn'
    },
    label(): string {
      if (this.checking) return '自检中'
      if (this.tone === 'down') return '依赖不可用'
      if (this.tone === 'warn') return '部分降级'
      if (this.tone === 'idle') return '未自检'
      return '就绪'
    },
    degradedCount(state): number {
      return state.probes.filter((probe) => probe.status !== 'UP').length
    },
  },

  actions: {
    /** 模型探测要真的调一次推理，只有自检页显式打开；顶栏那颗点只做快探测。 */
    async refresh(withModels = false) {
      this.checking = true
      try {
        const report = await healthApi.report(withModels)
        this.ready = report.ready
        this.message = report.message
        this.probes = report.probes
        this.loaded = true
      } catch (error) {
        // 后端没起时 fetch 直接失败：把原因写进 message，界面不至于只显示「就绪/未就绪」
        this.ready = false
        this.loaded = true
        this.probes = [
          {
            name: 'backend',
            status: 'DOWN',
            detail: error instanceof Error ? error.message : String(error),
            tookMs: 0,
          },
        ]
        this.message = '后端不可达'
      } finally {
        this.checking = false
      }
    },
  },
})
