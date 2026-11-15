#!/usr/bin/env node
/**
 * 端到端冒烟 / 回归。对着一个**已经在跑**的 rag-api 打真实 HTTP，按里程碑顺序断言。
 *
 *   node deploy/smoke.mjs                        # 非破坏性全流程（health→入库→检索→问答）
 *   node deploy/smoke.mjs --archive              # 额外跑归档段：会删除再导入 KB 内全部文档
 *   node deploy/smoke.mjs --shape-only           # 只验契约形状，用于对着 mock 服务自检本脚本
 *
 * 断言分两层：形状/契约层对任何实现了本 API 的服务都成立；事实层依赖 samples/ 里那批写死了
 * 数字的样本，只在真库 + 真模型上跑（--shape-only 会整段跳过它）。
 */
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { inflateRawSync } from 'node:zlib'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..')

const USAGE = `用法：node deploy/smoke.mjs [参数]
  --base=URL            后端地址，默认 http://localhost:8080（或环境变量 RAG_API）
  --kb=ID               目标知识库 id，默认取列表第一个
  --samples=DIR         实测样本目录，默认 samples
  --models              health 额外带 ?models=true，并要求 chat/embedding 探针不是 DOWN
  --ingest-timeout=SEC  等入库跑到终态的上限，默认 600（首次加载 embedding 模型就要半分钟）
  --stream-timeout=SEC  流式问答两段之间的静默上限，默认 120
  --archive             加跑归档导出/删除/导入回归（破坏性：会删掉该库全部文档再导回来）
  --shape-only          跳过依赖 samples 内容的断言，只验接口契约
  --help                看这个

退出码：0 全绿 / 1 有失败 / 2 连不上后端或参数有问题`

// ---------------------------------------------------------------- 参数

if (process.argv.includes('--help') || process.argv.includes('-h')) {
  console.log(USAGE)
  process.exit(0)
}
const flags = new Map()
for (const arg of process.argv.slice(2)) {
  const m = /^--([\w-]+)(?:=([\s\S]*))?$/.exec(arg)
  if (!m) {
    console.error(`不认识的参数：${arg}\n\n${USAGE}`)
    process.exit(2)
  }
  flags.set(m[1], m[2] === undefined ? 'true' : m[2])
}
const opt = {
  base: String(flags.get('base') ?? process.env.RAG_API ?? 'http://localhost:8080').replace(/\/+$/, ''),
  kb: flags.has('kb') ? Number(flags.get('kb')) : null,
  samples: path.resolve(ROOT, flags.get('samples') ?? 'samples'),
  models: flags.has('models'),
  archive: flags.has('archive'),
  shapeOnly: flags.has('shape-only'),
  ingestTimeout: Number(flags.get('ingest-timeout') ?? 600),
  streamTimeout: Number(flags.get('stream-timeout') ?? 120),
}

// ---------------------------------------------------------------- 断言台账

const report = { passed: 0, failed: 0, skipped: 0, failures: [] }
const startedAt = Date.now()
let stepName = ''

function step(name) {
  stepName = name
  console.log(`\n\u2500\u2500 ${name} ${'\u2500'.repeat(Math.max(2, 58 - name.length * 2))}`)
}

function log(kind, name, detail) {
  console.log(`  [${kind}] ${name}${detail ? ` ｜ ${detail}` : ''}`)
}

/** 断言拿不准就整条跳过，而不是硬凑一个通过。 */
const SKIP = Symbol('skip')

function skip(name, why) {
  report.skipped++
  log('SKIP', name, why)
  return SKIP
}

async function check(name, fn) {
  try {
    const note = await fn()
    if (note === SKIP) return
    report.passed++
    log('PASS', name, note)
  } catch (err) {
    // 断言消息里可能带半篇答案原文，压成一行，失败清单才可读
    const msg = String(err?.message ?? err).replace(/\s+/g, ' ').slice(0, 300)
    report.failed++
    report.failures.push(`${stepName} / ${name} ｜ ${msg}`)
    log('FAIL', name, msg)
  }
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg)
}

/** 只在校验契约时成立的断言：真库跑要更严，mock 跑不能被事实内容绊住。 */
async function checkFacts(name, fn) {
  if (opt.shapeOnly) return skip(name, 'shape-only')
  return check(name, fn)
}

class ApiError extends Error {
  constructor(message, code, traceId) {
    super(traceId ? `${message} ｜ traceId=${traceId}` : message)
    this.code = code
    this.traceId = traceId
  }
}

// ---------------------------------------------------------------- HTTP

async function send(method, apiPath, { json, form, params, accept } = {}) {
  const url = new URL(`${opt.base}/api${apiPath}`)
  for (const [k, v] of Object.entries(params ?? {})) {
    if (v !== undefined && v !== null) url.searchParams.set(k, String(v))
  }
  const init = { method, headers: { Accept: accept ?? 'application/json' } }
  if (json !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(json)
  } else if (form !== undefined) {
    init.body = form
  }
  let res
  try {
    res = await fetch(url, init)
  } catch (err) {
    throw new ApiError(`${method} ${url.pathname} 请求发不出去：${err.message}（后端没起？--base 对不对？）`, 0, null)
  }
  return res
}

/** 正常信封：返回 data。非 200 一律抛，把后端 message + traceId 带出来。 */
async function api(method, apiPath, opts) {
  const res = await send(method, apiPath, opts)
  const text = await res.text()
  let env
  try {
    env = JSON.parse(text)
  } catch {
    throw new ApiError(`${method} ${apiPath} → HTTP ${res.status}，响应不是 JSON 信封：${text.slice(0, 120)}`, res.status, null)
  }
  if (!res.ok || env.code !== 200) {
    throw new ApiError(`${method} ${apiPath} → HTTP ${res.status} code=${env.code} ${env.message ?? ''}`, env.code, env.traceId)
  }
  return env
}

/** 负例：期望后端在写任何东西之前就把请求挡下来。 */
async function apiFails(method, apiPath, opts, expectedStatus) {
  try {
    const env = await api(method, apiPath, opts)
    throw new Error(`本该报错，却返回 200：${JSON.stringify(env.data).slice(0, 100)}`)
  } catch (err) {
    if (err instanceof ApiError) {
      assert(err.code === expectedStatus, `期望 ${expectedStatus}，实际 code=${err.code}（${err.message}）`)
      return err
    }
    throw err
  }
}

const FRAME_END = /\r?\n\r?\n/

function parseFrame(frame) {
  let event = 'message'
  const data = []
  for (const line of frame.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  return data.length === 0 ? null : { event, data: data.join('\n') }
}

/**
 * POST + SSE。与前端 sse.ts 同一条切帧规则，包括「起流之前的失败回的是普通 JSON 信封」。
 * 只等静默超时，不等总时长：本地 7B 生成慢但一直在吐字，那种情况不该被掐断。
 */
async function postStream(apiPath, body) {
  const controller = new AbortController()
  let idle = null
  const armIdle = () => {
    clearTimeout(idle)
    idle = setTimeout(() => controller.abort(), opt.streamTimeout * 1000)
  }
  let res
  try {
    armIdle()
    res = await fetch(`${opt.base}/api${apiPath}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
  } catch (err) {
    throw new ApiError(`POST ${apiPath} 发不出去：${err.message}`, 0, null)
  }
  const type = res.headers.get('content-type') ?? ''
  if ((!res.ok || !type.includes('text/event-stream')) && type.includes('json')) {
    const env = JSON.parse(await res.text())
    throw new ApiError(`POST ${apiPath} → code=${env.code} ${env.message ?? ''}`, env.code, env.traceId)
  }
  if (!res.ok) throw new ApiError(`POST ${apiPath} → HTTP ${res.status}，且不是事件流`, res.status, null)
  if (!res.body) throw new ApiError(`POST ${apiPath} → 没有响应体`, 0, null)

  const events = []
  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    armIdle()
    buffer += decoder.decode(value, { stream: true })
    for (;;) {
      const m = FRAME_END.exec(buffer)
      if (!m) break
      const frame = buffer.slice(0, m.index)
      buffer = buffer.slice(m.index + m[0].length)
      const ev = parseFrame(frame)
      if (ev) events.push({ ...ev, payload: safeJson(ev.data) })
    }
  }
  clearTimeout(idle)
  const tail = parseFrame(buffer)
  if (tail) events.push({ ...tail, payload: safeJson(tail.data) })
  return events
}

function safeJson(text) {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

// ---------------------------------------------------------------- zip 只读解析

/** 只读中央目录：够用就行，为的是把实测指导 §10 里「包内该有什么」变成自动断言。 */
function zipEntries(buf) {
  let eocd = -1
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 66000; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocd = i
      break
    }
  }
  assert(eocd >= 0, '不是合法 zip：找不到中央目录结束标记')
  const count = buf.readUInt16LE(eocd + 10)
  let off = buf.readUInt32LE(eocd + 16)
  const entries = []
  for (let i = 0; i < count; i++) {
    assert(buf.readUInt32LE(off) === 0x02014b50, `中央目录第 ${i} 条签名不对`)
    const method = buf.readUInt16LE(off + 10)
    const compSize = buf.readUInt32LE(off + 20)
    const nameLen = buf.readUInt16LE(off + 28)
    const extraLen = buf.readUInt16LE(off + 30)
    const commentLen = buf.readUInt16LE(off + 32)
    const localOff = buf.readUInt32LE(off + 42)
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen)
    entries.push({ name, method, compSize, localOff })
    off += 46 + nameLen + extraLen + commentLen
  }
  return entries
}

function zipText(buf, entry) {
  const local = entry.localOff
  assert(buf.readUInt32LE(local) === 0x04034b50, `${entry.name} 的本地头签名不对`)
  const nameLen = buf.readUInt16LE(local + 26)
  const extraLen = buf.readUInt16LE(local + 28)
  const start = local + 30 + nameLen + extraLen
  const slice = buf.subarray(start, start + entry.compSize)
  return (entry.method === 8 ? inflateRawSync(slice) : slice).toString('utf8')
}

// ---------------------------------------------------------------- 样本事实表
// 与 samples/ 里写死的数字一一对应；改样本就得改这张表，这是有意的。

const EXPECT_FAILED = [
  { prefix: '04-', code: 'GARBLED_TEXT', why: '只有空白正文' },
  { prefix: '05-', code: 'GARBLED_TEXT', why: '全角 ASCII 占比过半，判定 CID 字体乱码' },
]
const EXPECT_DUPLICATE = '06-'

const SEARCH_FACTS = [
  { q: '数据库备份保留多少天', mode: 'hybrid', doc: '运维手册', want: '35' },
  { q: '工单 OPS-2026-118 记了什么误配', mode: 'hybrid', doc: '运维手册', want: '1234' },
  { q: 'LEDGER-7731', mode: 'keyword', doc: '变更台账', want: 'LEDGER-7731' },
  { q: 'TAIL-9F3C', mode: 'hybrid', doc: '无标点长段落', want: 'TAIL-9F3C' },
  { q: 'RRF 的平滑常数 k 取多少', mode: 'hybrid', doc: '术语表', want: '60' },
]
const ABSENT_TOKEN = 'KB-ZZTOKEN-42'
const CHAT_QUESTION = '数据库备份保留多少天？请给出出处。'

// ---------------------------------------------------------------- 主流程

const uploads = []
let kbId = opt.kb
/** 问答的 meta 帧留着给第 10 步对照：SSE 说的降级/截断，计数端点必须也说。 */
let lastChatMeta = null

async function main() {
  console.log(`目标后端：${opt.base}`)
  console.log(`模式：${opt.shapeOnly ? 'shape-only（只验契约）' : '完整（含 samples 事实断言)'}` +
    `${opt.archive ? ' + 归档段（破坏性）' : ''}`)

  await stepHealth()
  await stepKnowledgeBases()
  await stepUpload()
  await stepIngest()
  await stepSearch()
  await stepNegative()
  await stepChat()
  await stepDisableSegment()
  if (opt.archive) await stepArchive()
  else skip('归档导出 / 删除 / 导入', '未带 --archive（该步骤会删除该库全部文档再导回来）')
  await stepDiagnostics()

  const seconds = ((Date.now() - startedAt) / 1000).toFixed(1)
  console.log(`\n${'═'.repeat(64)}`)
  console.log(`结果：PASS ${report.passed} ／ FAIL ${report.failed} ／ SKIP ${report.skipped} ／ ${seconds}s`)
  if (report.failures.length > 0) {
    console.log('\n失败清单：')
    for (const f of report.failures) console.log(`  · ${f}`)
    process.exit(1)
  }
  console.log('全绿。')
}

async function stepHealth() {
  step('1 health')
  await check('GET /health → ready', async () => {
    const env = await api('GET', '/health', { params: opt.models ? { models: 'true' } : undefined })
    assert(env.data.ready === true, `ready=${env.data.ready}`)
    const probes = env.data.probes ?? []
    const names = probes.map((p) => p.name)
    // 探针名字是后端契约的一部分：postgres 不在就等于连不上库，后面全是空跑
    assert(names.includes('postgres'), `探针里没有 postgres：${names.join(',')}`)
    for (const p of probes) console.log(`         · ${p.name} ${p.status} ${p.detail ?? ''}`)
    if (opt.models) {
      const modelProbes = probes.filter((p) => /chat|embedding/.test(p.name))
      assert(modelProbes.length >= 2, `带 models=true 却只有 ${modelProbes.map((p) => p.name).join(',')} 两个模型探针都没跑`)
      for (const p of modelProbes) assert(p.status !== 'DOWN', `${p.name} 探针 DOWN：${p.detail}`)
    }
    return `${probes.length} 个探针`
  })
  await check('GET /health 未带 models 时不真连模型', async () => {
    const env = await api('GET', '/health')
    const names = (env.data.probes ?? []).map((p) => p.name).join(',')
    return names
  })
}

async function stepKnowledgeBases() {
  step('2 知识库')
  await check('GET /knowledge-bases 非空', async () => {
    const env = await api('GET', '/knowledge-bases')
    const list = env.data ?? []
    assert(list.length > 0, '一个知识库都没有：先上传一份文档，默认库会自动建')
    if (kbId === null) kbId = list[0].id
    const kb = list.find((k) => k.id === kbId)
    assert(kb, `--kb=${kbId} 不在列表里（现有 ${list.map((k) => k.id).join('/')}）；` +
      '注意后端没有建库接口，跨库场景按实测指导 §10 用 SQL 插一行')
    assert(typeof kb.embeddingDim === 'number' && kb.embeddingDim > 0, 'embeddingDim 缺失')
    return `kbId=${kbId} 「${kb.name}」 dim=${kb.embeddingDim}`
  })
}

async function stepUpload() {
  step(`3 上传 samples（${path.relative(ROOT, opt.samples)}）`)
  let files = []
  try {
    files = readdirSync(opt.samples).filter((f) => !f.startsWith('.')).sort()
  } catch (err) {
    return skip('上传样本', `读不了 ${opt.samples}：${err.message}`)
  }
  if (files.length === 0) return skip('上传样本', `${opt.samples} 是空目录`)
  for (const name of files) {
    await check(`POST /documents ← ${name}`, async () => {
      const bytes = readFileSync(path.join(opt.samples, name))
      const form = new FormData()
      form.append('file', new Blob([bytes]), name)
      const env = await api('POST', '/documents', { form, params: { kbId } })
      const d = env.data
      assert(typeof d.documentId === 'number' && d.documentId > 0, `documentId 不对：${JSON.stringify(d)}`)
      assert(typeof d.status === 'string' && d.status.length > 0, 'status 缺失')
      uploads.push({ name, id: d.documentId, duplicated: d.duplicated === true, atUpload: d.status })
      return `id=${d.documentId} ${d.status}${d.duplicated ? ' duplicated' : ''}`
    })
  }
  await checkFacts('样本清单齐全', async () => {
    const names = uploads.map((u) => u.name).join(',')
    for (const prefix of ['01-', '02-', '03-', '04-', '05-', '06-', '07-']) {
      assert(names.includes(prefix), `samples 里缺 ${prefix}*.md/.csv：实测断言靠这些文件里写死的数字`)
    }
    return `${uploads.length} 份`
  })
}

const TERMINAL = new Set(['READY', 'FAILED'])

async function stepIngest() {
  step(`4 等入库到终态（上限 ${opt.ingestTimeout}s）`)
  const deadline = Date.now() + opt.ingestTimeout * 1000
  // 按「上传项」而不是「文档 id」排队：同 sha 的重复上传会共用同一个 id，用 Map 会把其中一条吞掉。
  const open = uploads.slice()
  let ticks = 0
  while (open.length > 0 && Date.now() < deadline) {
    const env = await api('GET', '/documents', { params: { kbId, limit: 200 } })
    const rows = new Map((env.data ?? []).map((r) => [r.id, r]))
    for (let i = open.length - 1; i >= 0; i--) {
      const row = rows.get(open[i].id)
      if (!row) continue
      open[i].last = row
      if (TERMINAL.has(row.status)) {
        open[i].final = row
        open.splice(i, 1)
      }
    }
    ticks++
    if (open.length > 0) {
      if (ticks % 3 === 1) {
        console.log('         … ' + open.map((u) =>
          `${u.name.replace(/\.[^.]+$/, '')}=${u.last?.status ?? u.atUpload}` +
          `${u.last?.progressMsg ? `(${u.last.progressMsg})` : ''}`).join(' '))
      }
      await new Promise((r) => setTimeout(r, 2000))
    }
  }
  for (const u of open) {
    u.final = { ...(u.last ?? {}), id: u.id, name: u.name, status: u.last?.status ?? 'TIMEOUT' }
  }
  await check('全部样本跑到终态', () => {
    const stuck = uploads.filter((u) => !TERMINAL.has(u.final?.status))
    assert(stuck.length === 0, `还在跑/超时：${stuck.map((u) => `${u.name}=${u.final?.status}`).join(' ')}`)
    return `${uploads.length} 份终态`
  })
  for (const rule of EXPECT_FAILED) {
    await checkFacts(`${rule.prefix}* 因 ${rule.why} 被拒`, () => {
      const row = uploads.find((u) => u.name.startsWith(rule.prefix))
      if (!row) return skip(`${rule.prefix}* 拒绝`, '样本不在')
      assert(row.final?.status === 'FAILED', `期望 FAILED，实际 ${row.final?.status}`)
      assert(row.final?.errorCode === rule.code, `期望 errorCode=${rule.code}，实际 ${row.final?.errorCode}`)
      assert((row.final?.errorMsg ?? '').length > 0, 'errorMsg 为空：失败必须能自证原因')
      return `${row.final.errorCode} ｜ ${row.final.errorMsg.slice(0, 60)}`
    })
  }
  await checkFacts('06- 与 01- 同 sha 被视为重复', () => {
    const row = uploads.find((u) => u.name.startsWith(EXPECT_DUPLICATE))
    const origin = uploads.find((u) => u.name.startsWith('01-'))
    if (!row || !origin) return skip('06- 重复判定', '样本不在')
    assert(row.final?.sha256 === undefined || row.final.sha256 === origin.final?.sha256,
      '两篇的 sha256 不同：06 应当是 01 的逐字节副本')
    assert(row.duplicated === true || row.final?.id === origin.id,
      `上传时没判成重复（duplicated=${row.duplicated}）：同库同 sha256 必须只回已存在`)
    return `id=${row.id} duplicated=${row.duplicated}`
  })
  await checkFacts('READY 的样本都真的写进了正文', () => {
    const ready = uploads.filter((u) => u.final?.status === 'READY')
    assert(ready.length >= 4, `READY 只有 ${ready.length} 份，期望至少 4（01/02/03/07）`)
    for (const u of ready) {
      assert((u.final.charCount ?? 0) > 0, `《${u.name}》status=READY 但 charCount=${u.final.charCount}`)
      assert(u.final.version >= 1, `《${u.name}》version=${u.final.version}，入库成功至少该是 1`)
    }
    return ready.map((u) => `${u.name}:${u.final.charCount}字/${u.final.version}代`).join(' ')
  })
}

async function stepSearch() {
  step('5 检索')
  for (const mode of ['hybrid', 'vector', 'keyword']) {
    await check(`POST /search mode=${mode} 形状`, async () => {
      const env = await api('POST', '/search', { json: { query: '知识库 检索', kbIds: [kbId], mode, topK: 8 } })
      const v = env.data
      assert(v.effectiveMode === mode.toUpperCase() || v.degraded === true,
        `请求 ${mode} 得到 ${v.effectiveMode} 且 degraded=false，说明模式没生效`)
      assert(v.hitCount === v.hits.length, `hitCount=${v.hitCount} 与 hits.length=${v.hits.length} 不一致`)
      assert(typeof v.tookMs === 'number', 'tookMs 缺失')
      v.hits.forEach((h, i) => {
        assert(h.rank === i + 1, `第 ${i} 条 rank=${h.rank}，必须从 1 连续`)
        assert(h.segmentId > 0 && h.docName, `命中缺 segmentId/docName：${JSON.stringify(h).slice(0, 80)}`)
        assert((h.snippet ?? '').length > 0, `segment ${h.segmentId} 的 snippet 为空`)
        assert(h.rrf > 0, `segment ${h.segmentId} 的 rrf=${h.rrf}，融合分必须为正`)
      })
      const worst = v.hits.at(-1)?.rrf
      return `${v.hitCount} 命中 / ${v.tookMs}ms / engine=${v.keywordEngine}` +
        (worst === undefined ? '' : ` / rrf 首末 ${v.hits[0].rrf}→${worst}`)
    })
    await checkFacts(`mode=${mode} 的 rrf 量纲还是 1/(60+rank) 那一套`, async () => {
      const env = await api('POST', '/search', { json: { query: '知识库 检索', kbIds: [kbId], mode, topK: 8 } })
      for (const h of env.data.hits) {
        // 单路 k=60 时第一名 1/61≈0.0164；两路都在第一名约 0.0328。冒出 6.5 这种数说明量纲换了。
        assert(h.rrf < 0.1, `${mode} 的 rrf=${h.rrf} 超出 1/(k+rank) 量纲，阈值配置得跟着改`)
      }
      return `${env.data.hits.length} 命中全部 < 0.1`
    })
  }
  for (const f of SEARCH_FACTS) {
    await checkFacts(`「${f.q}」${f.mode} 命中《${f.doc}》且含 ${f.want}`, async () => {
      const env = await api('POST', '/search', { json: { query: f.q, kbIds: [kbId], mode: f.mode, topK: 8 } })
      const hit = env.data.hits.find((h) => h.docName.includes(f.doc))
      assert(hit, `${f.mode} 没命中《${f.doc}》，top1=${env.data.hits[0]?.docName ?? '无命中'}`)
      assert(hit.snippet.includes(f.want) || env.data.hits.some((h) => (h.snippet ?? '').includes(f.want)),
        `命中了《${f.doc}》，但 8 条摘要里找不到「${f.want}」——分块把它切丢了还是清洗吃掉了`)
      return `rank=${hit.rank} rrf=${hit.rrf} 页=${hit.pageStart ?? '-'}`
    })
  }
  await checkFacts(`不存在的记号「${ABSENT_TOKEN}」不该被当成命中`, async () => {
    const env = await api('POST', '/search', { json: { query: ABSENT_TOKEN, kbIds: [kbId], mode: 'hybrid', topK: 5 } })
    const polluted = env.data.hits.filter((h) => (h.snippet ?? '').includes(ABSENT_TOKEN))
    assert(polluted.length === 0, `${env.data.hitCount} 条命中里 ${polluted.length} 条含该记号：库里没有它`)
    return `命中 ${env.data.hitCount} 条，均为向量近似（无一条含该记号）`
  })
}

async function stepNegative() {
  step('6 契约负例（写东西之前就该挡下）')
  const fails = (method, p, opts, status) =>
    apiFails(method, p, opts, status).then((err) => `code=${err.code} ${err.message.replace(/｜.*/, '').trim()}`)
  await check('search 不带 kbIds → 400', () => fails('POST', '/search', { json: { query: '随便' } }, 400))
  await check('search 空 query → 400', () =>
    fails('POST', '/search', { json: { query: '  ', kbIds: [kbId] } }, 400))
  await check('export 不存在的库 → 404', () => fails('GET', '/knowledge-bases/999999/export', {}, 404))
  await check('import 传非 zip → 400', async () => {
    const form = new FormData()
    form.append('file', new Blob([Buffer.from('这不是 zip')], { type: 'application/octet-stream' }), 'note.txt')
    return fails('POST', `/knowledge-bases/${kbId}/import`, { form }, 400)
  })
  await check('chat 缺 question → 400（起流前的普通 JSON）', async () => {
    try {
      await postStream('/chat/ask', { kbIds: [kbId] })
    } catch (err) {
      assert(err instanceof ApiError && err.code === 400, `期望 code=400，实际 ${err.message}`)
      return '走的是 JSON 信封，不是事件流'
    }
    throw new Error('本该 400，却吐出了事件流')
  })
  await check('chat 不带检索范围 → 400', async () => {
    try {
      await postStream('/chat/ask', { question: '在吗' })
    } catch (err) {
      assert(err instanceof ApiError && err.code === 400, `期望 code=400，实际 ${err.message}`)
      return '同上'
    }
    throw new Error('本该 400，却吐出了事件流')
  })
}

async function stepChat() {
  step('7 流式问答')
  let answer = ''
  let meta = null
  let done = null
  const streamError = { message: null }
  await check('POST /chat/ask 事件顺序 meta→delta…→done', async () => {
    const events = await postStream('/chat/ask', { question: CHAT_QUESTION, kbIds: [kbId], mode: 'hybrid' })
    const names = events.map((e) => e.event)
    const err = events.find((e) => e.event === 'error')
    if (err) streamError.message = err.payload?.message ?? 'error 事件'
    assert(names[0] === 'meta', `首个事件是 ${names[0]}，不是 meta：${names.slice(0, 4).join('>')}`)
    assert(names[names.length - 1] === 'done' || err, `末个事件是 ${names.at(-1)}，没收到 done`)
    assert(names.filter((n) => n === 'delta').length > 0, '一个 delta 都没有')
    meta = events.find((e) => e.event === 'meta')?.payload ?? null
    lastChatMeta = meta
    done = events.find((e) => e.event === 'done')?.payload
    answer = events.filter((e) => e.event === 'delta').map((e) => e.payload?.text ?? '').join('')
    return `${names.length} 事件（delta ${names.filter((n) => n === 'delta').length} 段）` +
      ` 答案 ${answer.length} 字 model=${done?.model ?? '-'}`
  })
  await check('meta 字段齐全', () => {
    assert(meta, '没拿到 meta')
    for (const key of ['sessionId', 'questionId', 'answerId', 'effectiveMode', 'keywordEngine', 'sources',
      'contextChars', 'budgetChars', 'unplacedHits', 'contextTruncated']) {
      assert(meta[key] !== undefined, `meta 缺字段 ${key}`)
    }
    assert(Array.isArray(meta.sources), 'meta.sources 不是数组')
    assert(meta.budgetChars > 0, `budgetChars=${meta.budgetChars}，上下文预算没算出来`)
    return `上下文 ${meta.contextChars}/${meta.budgetChars} 字 truncated=${meta.contextTruncated}`
  })
  await check('done 状态是终态之一', () => {
    assert(done, '没拿到 done')
    assert(['OK', 'REFUSED', 'FAILED', 'INTERRUPTED'].includes(done.status), `done.status=${done.status}`)
    assert(typeof done.totalMs === 'number', 'done.totalMs 缺失')
    return `${done.status} ${done.answerMs}ms 生成 / ${done.totalMs}ms 总计`
  })
  await checkFacts('引用非空且与检索命中同源', () => {
    if (streamError.message) throw new Error(`问答先报了 error：${streamError.message}（模型没加载？LM Studio 通不通？）`)
    assert(meta.sources.length > 0, 'sources 为空：有资料却没引用，溯源链断了')
    meta.sources.forEach((s, i) => {
      assert(s.citation === i + 1, `citation 不连续：第 ${i} 个是 ${s.citation}`)
      assert(s.docName && s.segmentId > 0, `引用缺 docName/segmentId：${JSON.stringify(s).slice(0, 80)}`)
      assert((s.location ?? '').length > 0 || s.pageStart, '引用既无 location 也无页码，前端跳不过去')
    })
    return `${meta.sources.length} 条引用：${meta.sources.map((s) => `${s.citation}=${s.docName}#${s.chunkNo}`).join(' ')}`
  })
  await checkFacts('答案里出现了样本里写死的「35 天」', () => {
    assert(/35\s*天|35\s*D/i.test(answer), `答案没有 35 天：${answer.slice(0, 120)}`)
    return answer.slice(0, 60).replace(/\s+/g, ' ')
  })
}

/** 块级人工禁用必须立刻影响两条检索路，且关掉再打开可逆。跑完恢复原状。 */
async function stepDisableSegment() {
  step('8 块级禁用可逆')
  if (opt.shapeOnly) {
    skip('禁用「薪酬」块 → 检索不到 → 恢复', 'shape-only')
    return
  }
  let target = null
  await check('找到含「薪酬」的块', async () => {
    const env = await api('POST', '/search', { json: { query: '薪酬', kbIds: [kbId], mode: 'keyword', topK: 10 } })
    target = env.data.hits.find((h) => (h.snippet ?? '').includes('薪酬'))
    assert(target, `关键词「薪酬」没命中任何块：${env.data.hitCount} 条命中，zhparser 那条路是不是降级了`)
    return `segment=${target.segmentId}《${target.docName}》#${target.chunkNo}`
  })
  if (!target) return
  await check('PATCH available=false 后不再召回', async () => {
    await api('PATCH', `/documents/${target.docId}/segments/${target.segmentId}`, { params: { available: false } })
    const env = await api('POST', '/search', { json: { query: '薪酬', kbIds: [kbId], mode: 'keyword', topK: 10 } })
    assert(!env.data.hits.some((h) => h.segmentId === target.segmentId),
      '关掉开关后这条块仍被召回：available 没进检索条件')
    return `召回 ${env.data.hitCount} 条，已无该块`
  })
  await check('PATCH available=true 后恢复召回', async () => {
    await api('PATCH', `/documents/${target.docId}/segments/${target.segmentId}`, { params: { available: true } })
    const env = await api('POST', '/search', { json: { query: '薪酬', kbIds: [kbId], mode: 'keyword', topK: 10 } })
    assert(env.data.hits.some((h) => h.segmentId === target.segmentId), '打开开关后仍召不回：状态没落库')
    return '已恢复原状'
  })
}

/** 破坏性：导出 → 删库内文档 → 导入，验「免重算」与人工状态原样带回。 */
async function stepArchive() {
  step('9 归档导出 / 删除 / 导入（破坏性）')
  let zip = null
  let exportedDocs = []
  let manifest = null
  await check('GET /export?vectors=true&files=true 是合法 zip', async () => {
    const res = await send('GET', `/knowledge-bases/${kbId}/export`, {
      params: { vectors: true, files: true }, accept: 'application/octet-stream',
    })
    assert(res.ok, `HTTP ${res.status}`)
    const type = res.headers.get('content-type') ?? ''
    if (type.includes('json')) {
      throw new Error(`导出没成流，后端回的是 JSON 信封：${(await res.text()).slice(0, 160)}`)
    }
    const bytes = Buffer.from(await res.arrayBuffer())
    assert(bytes.length > 22 && bytes.readUInt32LE(0) === 0x04034b50,
      `前 4 字节不是 PK\\x03\\x04（${bytes.length} 字节）`)
    const entries = zipEntries(bytes)
    const names = entries.map((e) => e.name)
    assert(names.includes('manifest.json'), `包里没有 manifest.json：${names.slice(0, 6).join(' ')}`)
    manifest = JSON.parse(zipText(bytes, entries.find((e) => e.name === 'manifest.json')))
    exportedDocs = Array.isArray(manifest.documents) ? manifest.documents : []
    zip = bytes
    return `${bytes.length} 字节 / ${entries.length} 条目 / ${exportedDocs.length} 篇`
  })
  await check('manifest 头部字段', () => {
    assert(manifest, '没有 manifest')
    assert(manifest.format === 1, `format=${manifest.format}，本脚本只认 1`)
    assert(manifest.vectors === true && manifest.files === true,
      `vectors=${manifest.vectors} files=${manifest.files}：请求带的是 true/true`)
    return `${manifest.source?.name ?? '?'} · ${manifest.source?.embeddingModel ?? '?'} · dim=${manifest.source?.embeddingDim ?? '?'}`
  })
  await check('包内条目按 slot 连续编号，正文/分段成对', () => {
    assert(exportedDocs.length > 0, 'manifest.documents 是空的：这个库还没有 READY 文档')
    const slots = exportedDocs.map((d) => d.slot)
    assert(slots.every((s, i) => s === i), `slot 不是从 0 连续编号：${slots.join(',')}`)
    const names = zipEntries(zip).map((e) => e.name)
    for (const d of exportedDocs) {
      assert(names.includes(`content/${d.slot}.txt`), `缺 content/${d.slot}.txt`)
      assert(names.includes(`segments/${d.slot}.jsonl`), `缺 segments/${d.slot}.jsonl`)
      assert(d.segmentCount > 0, `《${d.name}》segmentCount=${d.segmentCount}`)
    }
    const originals = names.filter((n) => n.startsWith('files/')).length
    assert(originals === exportedDocs.length,
      `files/ 有 ${originals} 条，篇数 ${exportedDocs.length}：带 files=true 就该每篇一个原件`)
    return `${exportedDocs.length} 篇 / ${exportedDocs.reduce((a, d) => a + d.segmentCount, 0)} 块 / 原件 ${originals}`
  })

  let disabled = null
  await check('先关掉一个块，看导入能不能把它原样带回来', async () => {
    const env = await api('GET', '/documents', { params: { kbId, status: 'READY', limit: 5 } })
    const doc = env.data[0]
    assert(doc, '库里没有 READY 文档，归档段没得测')
    const segs = await api('GET', `/documents/${doc.id}/segments`, { params: { onlySearchable: true, limit: 20 } })
    const seg = (segs.data ?? []).find((s) => s.available)
    assert(seg, '这篇文档没有可用块')
    await api('PATCH', `/documents/${doc.id}/segments/${seg.id}`, { params: { available: false } })
    disabled = { docName: doc.name, segmentId: seg.id }
    return `${doc.name} #${seg.id}`
  })

  await check('DELETE 掉该库全部文档', async () => {
    const env = await api('GET', '/documents', { params: { kbId, limit: 200 } })
    for (const row of env.data ?? []) await api('DELETE', `/documents/${row.id}`)
    const after = await api('GET', '/documents', { params: { kbId, limit: 200 } })
    assert((after.data ?? []).length === 0, `删除后列表还剩 ${(after.data ?? []).length} 行`)
    return `删了 ${(env.data ?? []).length} 行`
  })

  let summary = null
  await check('POST /import?rebuild=false → CARRIED 且 0 排队', async () => {
    const form = new FormData()
    form.append('file', new Blob([zip], { type: 'application/zip' }), 'smoke-kb.zip')
    const env = await api('POST', `/knowledge-bases/${kbId}/import`, { params: { rebuild: false }, form })
    summary = env.data
    assert(summary.mode === 'CARRIED', `mode=${summary.mode}：dim/模型都对得上，本该免重算`)
    assert(summary.queued === 0, `queued=${summary.queued}：CARRIED 路径不该排一次入库`)
    assert(summary.imported === exportedDocs.length,
      `imported=${summary.imported}，导出时是 ${exportedDocs.length} 篇`)
    assert(summary.failed === 0, `failed=${summary.failed}：${(summary.notes ?? []).join(' / ')}`)
    assert(summary.segmentsCarried > 0, `segmentsCarried=${summary.segmentsCarried}，向量没搬过来？`)
    return `${env.message} ｜ ${summary.imported} 篇 / ${summary.segmentsCarried} 块 / 原件 ${summary.originalsRestored}`
  })
  await check('导入后检索立刻可用（没等重算）', async () => {
    const env = await api('POST', '/search', { json: { query: 'LEDGER-7731', kbIds: [kbId], mode: 'keyword', topK: 5 } })
    assert(env.data.hits.length > 0, '导入后关键词检索 0 命中：tsvector 没跟着导入')
    return `${env.data.hits.length} 命中`
  })
  await check('人工关掉的块仍然是关掉的', async () => {
    assert(disabled, '上一步没拿到关掉的块')
    const env = await api('GET', '/documents', { params: { kbId, limit: 200 } })
    const restored = (env.data ?? []).find((d) => d.name === disabled.docName && d.status === 'READY')
    assert(restored, `《${disabled.docName}》没在导入后回到 READY`)
    const segs = await api('GET', `/documents/${restored.id}/segments`, { params: { onlySearchable: false, limit: 200 } })
    const off = (segs.data ?? []).filter((s) => !s.available)
    assert(off.length > 0, '导入把 available=false 洗回了 true：人工意图是数据的一部分（ADR-018）')
    return `${restored.name} 里 ${off.length} 块仍为禁用`
  })
}

/**
 * 第 10 步：把整轮跑完的「安静失败」计数拿出来对账。
 * vectorDegraded>0 意味着前面第 5 步那批 rrf 分数是关键词路给的，整批检索结论都不作数——
 * 这一条放在最后，是因为它判的是「刚才那 9 步可不可信」。
 */
async function stepDiagnostics() {
  step('10 运行诊断对账')
  let view = null
  await check('GET /diagnostics 形状', async () => {
    let env
    try {
      env = await api('GET', '/diagnostics')
    } catch (err) {
      if (err instanceof ApiError && (err.code === 404 || err.code === 405)) {
        return skip('GET /diagnostics', '这个后端没有 /api/diagnostics（真后端 2.0 起才有；mock 没有）')
      }
      throw err
    }
    view = env.data
    assert(view.run?.counters, `缺 run.counters：${JSON.stringify(view).slice(0, 120)}`)
    for (const key of ['vectorDegraded', 'contextTruncated', 'refused', 'embeddingFailures']) {
      assert(typeof view.run.counters[key] === 'number', `counters.${key} 不是数字`)
    }
    for (const key of ['mode', 'topK', 'embeddingDim', 'contextBudgetChars', 'nCtx']) {
      assert(view.effective?.[key] !== undefined, `effective 缺 ${key}`)
    }
    assert(Array.isArray(view.warnings), 'warnings 不是数组')
    return `uptime ${view.run.uptimeSeconds}s · 生效 ${view.effective.mode}/topK=${view.effective.topK}` +
      ` · 预算 ${view.effective.contextBudgetChars} 字`
  })
  if (!view) return
  await check('这一轮没有偷偷走降级路（vectorDegraded=0）', () => {
    const c = view.run.counters
    assert(c.vectorDegraded === 0,
      `向量路降级 ${c.vectorDegraded} 次，最后一次：${view.run.lastVectorFailure?.reason ?? '未记录'}` +
      '｜ 第 5 步的 rrf 分数就不是混合检索给的，这一批检索结论别要')
    assert(c.embeddingFailures === 0,
      `embedding 调用失败 ${c.embeddingFailures} 次，最后一次：${view.run.lastEmbeddingFailure?.reason ?? '未记录'}`)
    return `降级 0 / 截断 ${c.contextTruncated} / 拒答 ${c.refused}`
  })
  await check('SSE meta 与计数端点说的是同一件事', () => {
    if (!lastChatMeta) return skip('SSE meta 对账', '第 7 步没拿到 meta 帧')
    if (lastChatMeta.contextTruncated === true) {
      assert(view.run.counters.contextTruncated >= 1,
        'meta 说上下文截断了，diagnostics 却记 0：截断这条路径没接到计数器')
    }
    if (lastChatMeta.degraded === true) {
      assert(view.run.counters.vectorDegraded >= 1,
        `meta 说 degraded=true（${lastChatMeta.vectorFailure ?? ''}），diagnostics 却记 0：降级没计数`)
    }
    return `meta truncated=${lastChatMeta.contextTruncated} degraded=${lastChatMeta.degraded} ｜ 一致`
  })
  await check('参数自检 warnings 为空', () => {
    assert(view.warnings.length === 0, `后端自己认为配置有问题：${view.warnings.join(' ／ ')}`)
    return '无'
  })
}

main().catch((err) => {
  if (err instanceof ApiError) {
    console.error(`\n后端不可用或契约不符，冒烟中止：${err.message}`)
    process.exit(report.failed > 0 ? 1 : 2)
  }
  console.error('\n脚本自身出错：', err)
  process.exit(1)
})
