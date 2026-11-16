import { ApiError } from './http'

export interface SseEvent {
  event: string
  data: string
}

export type SseHandler = (event: SseEvent) => void

const FRAME_END = /\r?\n\r?\n/

/**
 * POST + text/event-stream。
 * 浏览器原生 EventSource 只能发 GET，也带不上 JSON 请求体，而问答必须是 POST，所以这里按规范自己切帧。
 * 不做自动重连：回答是一次性的推理，断线重连等于让模型把同一个问题再答一遍。
 */
export async function postStream(
  path: string,
  body: unknown,
  onEvent: SseHandler,
  signal?: AbortSignal,
): Promise<void> {
  let response: Response
  try {
    response = await fetch(`/api${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body),
      signal,
    })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') return
    throw new ApiError(`后端不可达：/api${path} ｜ 请确认 rag-api 已启动`, 0, null)
  }

  const contentType = response.headers.get('content-type') ?? ''
  if (!response.ok && !contentType.includes('text/event-stream')) {
    // 参数错、并发上限这类失败在起流之前，后端回的是普通 JSON 信封
    const text = await response.text()
    let message = text.slice(0, 200)
    let traceId: string | null = null
    try {
      const envelope = JSON.parse(text) as { message?: string; traceId?: string }
      message = envelope.message ?? message
      traceId = envelope.traceId ?? null
    } catch {
      /* 保留原始文本，至少比 "500" 有用 */
    }
    throw new ApiError(traceId ? `${message} ｜ traceId=${traceId}` : message, response.status, traceId)
  }
  if (!response.body) {
    throw new ApiError('当前浏览器不支持流式响应体', 0, null)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    for (;;) {
      const match = FRAME_END.exec(buffer)
      if (!match) break
      const frame = buffer.slice(0, match.index)
      buffer = buffer.slice(match.index + match[0].length)
      const event = parseFrame(frame)
      if (event) onEvent(event)
    }
  }

  // 收尾：连接断开时最后一帧可能没有空行，但已经收到的内容不该整帧丢掉
  const tail = parseFrame(buffer)
  if (tail) onEvent(tail)
}

function parseFrame(frame: string): SseEvent | null {
  let event = 'message'
  const data: string[] = []
  for (const line of frame.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    // 规范允许冒号后紧跟一个空格作为分隔符，它不属于值
    const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  if (data.length === 0) return null
  return { event, data: data.join('\n') }
}
