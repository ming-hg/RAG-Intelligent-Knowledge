import { requestEnvelope } from './http'
import type { HealthProbe, HealthReport } from '@/types/api'

export interface HealthSummary {
  ready: boolean
  message: string
  probes: HealthProbe[]
}

/**
 * 就绪时 HTTP 200、未就绪时 503，但两种情况下 body 里都是完整报告。
 * 所以这里绕开「非 200 即抛错」的通用封装：自检页要的就是那份 DOWN 清单，不是异常。
 */
export const healthApi = {
  report: async (withModels = false): Promise<HealthSummary> => {
    const envelope = await requestEnvelope<HealthReport>(`/health?models=${withModels}`)
    return {
      ready: envelope.code === 200 && (envelope.data?.ready ?? false),
      message: envelope.message,
      probes: envelope.data?.probes ?? [],
    }
  },
}
