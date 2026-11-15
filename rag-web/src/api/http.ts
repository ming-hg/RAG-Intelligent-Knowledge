import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types/api'

/** 后端把业务码放在 ApiResponse.code，HTTP 状态同步变化（400/404/409/503），两个都要看。 */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: number,
    readonly traceId: string | null,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

type QueryValue = string | number | boolean | null | undefined | Array<string | number>

export function queryString(params?: Record<string, QueryValue>): string {
  if (!params) return ''
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) {
      // Spring 的 List 参数接受同名重复键；空数组宁可一个都不写，也不要写出一条 docIds= 让后端读成 [0]
      for (const item of value) search.append(key, String(item))
    } else {
      search.append(key, String(value))
    }
  }
  const encoded = search.toString()
  return encoded ? `?${encoded}` : ''
}

interface RequestOptions {
  /** 界面已有位置展示错误（健康自检、进度轮询），不再弹一次全局 toast。 */
  silent?: boolean
  /** 非 200 的 code 也当成功返回：/api/health 的 503 报告本身就是要显示的数据。 */
  tolerateError?: boolean
}

async function send(path: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(`/api${path}`, {
      ...init,
      headers: { Accept: 'application/json', ...(init?.headers ?? {}) },
    })
  } catch {
    // 浏览器只会给 "Failed to fetch"，说不出是哪个端口没起，这里补成人话
    throw new ApiError(
      `后端不可达：/api${path} ｜ 请确认 rag-api 已启动（dev 代理目标默认 http://localhost:8080）`,
      0,
      null,
    )
  }
}

async function readEnvelope<T>(response: Response): Promise<ApiResponse<T>> {
  const text = await response.text()
  if (!text) {
    return {
      code: response.status,
      message: response.statusText || '空响应',
      data: undefined as T,
      traceId: null,
    }
  }
  try {
    return JSON.parse(text) as ApiResponse<T>
  } catch {
    throw new ApiError(`响应不是 JSON（HTTP ${response.status}）：${text.slice(0, 120)}`, response.status, null)
  }
}

async function requestJson<T>(path: string, init?: RequestInit, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, init)
  const body = await readEnvelope<T>(response)
  if (!options.tolerateError && (!response.ok || body.code !== 200)) {
    const detail = body.traceId ? `${body.message} ｜ traceId=${body.traceId}` : body.message
    const error = new ApiError(detail || `请求失败（HTTP ${response.status}）`, body.code || response.status, body.traceId)
    if (!options.silent) ElMessage.error(error.message)
    throw error
  }
  return body.data
}

/** 需要同时拿到 code、message、data 的接口（健康自检）走这个，绕开非 200 即抛错的默认行为。 */
export async function requestEnvelope<T>(path: string, init?: RequestInit): Promise<ApiResponse<T>> {
  return readEnvelope<T>(await send(path, init))
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

export const http = {
  get: <T>(path: string, params?: Record<string, QueryValue>, options?: RequestOptions) =>
    requestJson<T>(`${path}${queryString(params)}`, undefined, options),

  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    requestJson<T>(
      path,
      {
        method: 'POST',
        headers: body === undefined ? undefined : JSON_HEADERS,
        body: body === undefined ? undefined : JSON.stringify(body),
      },
      options,
    ),

  /** PUT 的覆盖类接口把「字段为 null」当作业务语义（这项不覆盖），所以 body 必须原样序列化，不能剔空键。 */
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    requestJson<T>(
      path,
      {
        method: 'PUT',
        headers: body === undefined ? undefined : JSON_HEADERS,
        body: body === undefined ? undefined : JSON.stringify(body),
      },
      options,
    ),

  patch: <T>(path: string, params?: Record<string, QueryValue>, options?: RequestOptions) =>
    requestJson<T>(`${path}${queryString(params)}`, { method: 'PATCH' }, options),

  /** 后端的 PATCH 有两种写法：会话改名走查询参数，消息反馈走 JSON 体（字段要用 null 和空串区分「不改」和「清空」）。 */
  patchJson: <T>(path: string, body: unknown, options?: RequestOptions) =>
    requestJson<T>(path, { method: 'PATCH', headers: JSON_HEADERS, body: JSON.stringify(body) }, options),

  del: <T>(path: string, options?: RequestOptions) =>
    requestJson<T>(path, { method: 'DELETE' }, options),

  upload: <T>(path: string, form: FormData, options?: RequestOptions) =>
    requestJson<T>(path, { method: 'POST', body: form }, options),
}
