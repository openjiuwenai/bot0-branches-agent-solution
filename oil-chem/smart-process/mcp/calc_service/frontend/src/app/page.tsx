'use client'

import React, { useState, useEffect, useMemo } from 'react'
import {
  Play, Loader2, Cpu, AlertTriangle, Clock,
  Calendar, Gauge, TrendingUp as TrendIcon, Database, AlertCircle,
  CheckCircle, Layers, Ship, Fuel,
} from 'lucide-react'
import {
  fNum, defaultCrudeName, normalizeMonth, KpiCard, CardHead,
} from '@/components/SolveResult'
import EChart, { CHART_COLORS } from '@/components/EChart'

type CrudeMap = Record<string, { id: string; name: string; cost: number }>

// generate_plan 返回的按天明细（to_dict：blend_detail / crude_stock_status 为 JSON 字符串）
type PlanDetail = {
  day_of_month: number
  daily_input: number
  blend_detail: string
  crude_stock_status: string
  device_load_rate: number
  hours: number
}
type GenResp = {
  success: boolean
  plan_id: string
  details: PlanDetail[]
  message: string
  solver?: string
  extra_info?: {
    waiting_time?: WaitingItem[]
    total_waiting_days?: number
    message?: string
    logistics_warnings?: string[]
  }
  viz_input?: VizInput
  viz_gantt?: VizGanttBatch[]
  viz_inventory?: Record<string, number[]>
}
type WaitingItem = {
  arrival_date: string
  crude_type: string
  target_tank: string
  waiting_days: number
  status: string
}

// ── CP-SAT 求解进度可视化类型 ──────────────────────────────────────────
// 进度事件（后端 progress_callback → _tasks["progress"] → 轮询响应）
type ProgressEvent = {
  phase: 'blend_built' | 'phase0_done' | 'round_start' | 'cp_solving' |
         'round_done' | 'round_filled' | 'round_failed' | 'early_stop'
  n_ships?: number
  n_constraints?: number
  n_windows?: number
  viz_input?: VizInput
  round?: number
  n_rounds?: number
  seed?: number
  time_limit?: number
  cp_status?: string
  cp_time?: number
  nb_batches?: number
  compliant?: number
  n_judged?: number
  dur_pct?: number
  switches?: number
  fill_time?: number
  n_blend_units?: number
  n_blend_combos?: number
  n_single_crudes?: number
  n_blend_only?: number
  preferred_count?: number
  n_preferred?: number
  reason?: string
}

type VizInput = {
  year: number
  month: number
  days: number
  ships: { crude: string; ton: number; berth_day: number }[]
  tanks: { tank_id: string; type: string; capacity: number; initial_crude: string; initial_ton: number; heel: number }[]
  proc_plan: { crude: string; ton: number; origin: string }[]
}

type VizGanttBatch = {
  batch_idx: number
  unit: string
  crude: string
  crudes: string[]
  start_h: number
  dur_h: number
  end_h: number
  start_day: number
  end_day: number
  tons: number
}

type RoundInfo = {
  round: number
  seed?: number
  time_limit?: number
  cp_status?: string
  cp_time?: number
  nb_batches?: number
  compliant?: number
  n_judged?: number
  dur_pct?: number
  switches?: number
  fill_time?: number
  is_preferred?: boolean
  solving?: boolean   // 正在 CP-SAT 求解中
}

const FALLBACK_MONTHS = [
  { key: '2026-01', label: '2026年1月' },
  { key: '2026-02', label: '2026年2月' },
  { key: '2026-03', label: '2026年3月' },
]

const safeNum = (n: number) => (typeof n === 'number' && !isNaN(n) ? n : 0)

// blend_detail / crude_stock_status 在 to_dict 里是 JSON 字符串，前端展示需 parse
function parseDict(raw: unknown): Record<string, number> {
  if (typeof raw === 'string') {
    try { return JSON.parse(raw) } catch { return {} }
  }
  if (raw && typeof raw === 'object') return raw as Record<string, number>
  return {}
}

// ── 求解阶段流水线组件 ──────────────────────────────────────────────────
function StageStep({ icon, label, status, sub }: {
  icon: React.ReactNode; label: string; status: 'done' | 'active' | 'pending'; sub?: string
}) {
  const color = status === 'done' ? 'text-green-500' : status === 'active' ? 'text-blue-500' : 'text-slate-300'
  const textColor = status === 'done' ? 'text-slate-600' : status === 'active' ? 'text-blue-600 font-medium' : 'text-slate-400'
  return (
    <div className="flex items-center gap-1.5">
      <span className={color}>{icon}</span>
      <div>
        <div className={`text-xs ${textColor}`}>{label}</div>
        {sub && <div className="text-[10px] text-slate-400">{sub}</div>}
      </div>
    </div>
  )
}
function StageArrow() {
  return <span className="text-slate-300 mx-0.5 text-xs">›</span>
}

export default function SolverPage() {
  const [month, setMonth] = useState('2026-02')
  const [months, setMonths] = useState(FALLBACK_MONTHS)
  const [solver, setSolver] = useState<'colleague' | 'mine'>('colleague')
  const [crudeMap, setCrudeMap] = useState<CrudeMap>({})
  const [serviceDown, setServiceDown] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<GenResp | null>(null)
  const [elapsedMs, setElapsedMs] = useState(0)
  const [progress, setProgress] = useState<ProgressEvent | null>(null)
  const [vizInput, setVizInput] = useState<VizInput | null>(null)
  const [rounds, setRounds] = useState<RoundInfo[]>([])
  const [blendInfo, setBlendInfo] = useState<{ units: number; combos: number; single: number; blendOnly: number } | null>(null)
  const [earlyStopReason, setEarlyStopReason] = useState<string | null>(null)

  // 挂载时拉原油映射 + 月份列表
  useEffect(() => {
    let cancelled = false
    setServiceDown(false)

    fetch('/api/scheduling/data', { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(d => {
        if (cancelled || !d?.success) return
        if (d.data?.crude_types) setCrudeMap(d.data.crude_types)
      })
      .catch(() => {})

    fetch('/api/scheduling/plans', { cache: 'no-store' })
      .then(async r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json() })
      .then(d => {
        if (cancelled) return
        const plans: { plan_id: string; plan_month?: string }[] = d?.plans || []
        const list = plans.map(p => normalizeMonth(p.plan_month || p.plan_id))
          .filter((m): m is { key: string; label: string } => m !== null)
          .sort((a, b) => a.key.localeCompare(b.key))
        if (list.length) {
          setMonths(list)
          if (!list.some(m => m.key === month)) setMonth(list[0].key)
        }
      })
      .catch(() => { if (!cancelled) setServiceDown(true) })
    return () => { cancelled = true }
  }, [])

  // ── 触发排产求解（CP-SAT 异步轮询）──
  async function solve() {
    setLoading(true); setError(null); setResult(null)
    setElapsedMs(0)
    setProgress(null); setVizInput(null); setRounds([])
    setBlendInfo(null); setEarlyStopReason(null)
    const startedAt = Date.now()

    // 计时器：实时更新已耗时
    const timer = setInterval(() => setElapsedMs(Date.now() - startedAt), 1000)

    try {
      // 1. 启动排产任务（POST 立即返回 task_id）
      const startResp = await fetch('/api/scheduling/generate_plan', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ plan_month: month, solver }),
      })
      const startBody = await startResp.json().catch(() => ({}))
      if (!startResp.ok || !startBody.task_id) {
        throw new Error(startBody?.message || startBody?.detail || `启动排产失败 HTTP ${startResp.status}`)
      }
      const taskId = startBody.task_id

      // 2. 轮询任务状态（每 3 秒），同步更新求解进度
      while (true) {
        await new Promise(resolve => setTimeout(resolve, 3000))
        const statusResp = await fetch(`/api/scheduling/generate_plan_status/${taskId}`)
        const statusBody = await statusResp.json().catch(() => ({}))

        if (statusBody.status === 'done') {
          setResult(statusBody.result as GenResp)
          break
        }
        if (statusBody.status === 'failed') {
          throw new Error(statusBody.error || '排产求解失败')
        }
        // running → 从完整事件列表重建状态（避免3秒轮询窗口内事件被覆盖丢失）
        const events = (statusBody.progress_events ?? []) as ProgressEvent[]
        const latest = (statusBody.progress ?? events[events.length - 1] ?? null) as ProgressEvent | null
        if (latest) setProgress(latest)

        // 遍历所有累积事件，幂等重建 rounds / blendInfo / vizInput / earlyStop
        const roundsMap = new Map<number, RoundInfo>()
        let blendBuilt: { units: number; combos: number; single: number; blendOnly: number } | null = null
        let stopReason: string | null = null
        let vizIn: VizInput | null = null

        for (const p of events) {
          switch (p.phase) {
            case 'blend_built':
              blendBuilt = {
                units: p.n_blend_units ?? 0,
                combos: p.n_blend_combos ?? 0,
                single: p.n_single_crudes ?? 0,
                blendOnly: p.n_blend_only ?? 0,
              }
              break
            case 'phase0_done':
              if (p.viz_input) vizIn = p.viz_input
              break
            case 'round_start': {
              const r = p.round!
              if (!roundsMap.has(r)) {
                roundsMap.set(r, { round: r, seed: p.seed, solving: true })
              }
              break
            }
            case 'cp_solving': {
              const r = p.round!
              if (!roundsMap.has(r)) {
                roundsMap.set(r, { round: r, seed: p.seed, solving: true })
              }
              break
            }
            case 'round_done': {
              const r = p.round!
              const prev = roundsMap.get(r) ?? { round: r, solving: true }
              roundsMap.set(r, {
                ...prev, solving: false,
                cp_status: p.cp_status, cp_time: p.cp_time,
                nb_batches: p.nb_batches, seed: p.seed, time_limit: p.time_limit,
              })
              break
            }
            case 'round_filled': {
              const r = p.round!
              const prev = roundsMap.get(r) ?? { round: r }
              roundsMap.set(r, {
                ...prev,
                compliant: p.compliant, n_judged: p.n_judged,
                dur_pct: p.dur_pct, switches: p.switches, fill_time: p.fill_time,
                is_preferred: p.compliant === p.n_judged,
              })
              break
            }
            case 'round_failed': {
              const r = p.round!
              const prev = roundsMap.get(r) ?? { round: r }
              roundsMap.set(r, {
                ...prev, solving: false,
                cp_status: p.cp_status, cp_time: p.cp_time,
              })
              break
            }
            case 'early_stop':
              stopReason = p.reason ?? '提前结束'
              break
          }
        }

        // 提取最新的 round_start/cp_solving 但还没 round_done 的轮次，标记为 solving
        // （上面已处理）

        setRounds(Array.from(roundsMap.values()).sort((a, b) => a.round - b.round))
        if (blendBuilt) setBlendInfo(blendBuilt)
        if (vizIn) setVizInput(vizIn)
        if (stopReason) setEarlyStopReason(stopReason)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '排产求解失败，请确认 solve_v1 服务已启动（:5081）')
    } finally {
      clearInterval(timer)
      setElapsedMs(Date.now() - startedAt)
      setLoading(false)
    }
  }

  const crudeName = (id: string) => defaultCrudeName(id, crudeMap)

  // 结果统计
  const details = result?.details ?? []
  const totalInput = details.reduce((s, d) => s + safeNum(d.daily_input), 0)
  const avgLoad = details.length ? details.reduce((s, d) => s + safeNum(d.device_load_rate), 0) / details.length : 0

  // CP-SAT 物流告警
  const logisticsWarnings = result?.extra_info?.logistics_warnings ?? []

  // 按天加工量 + 装置负荷率趋势图 option（柱+线双轴）
  const trendOption = useMemo(() => {
    if (!details.length) return null
    const days = details.map(d => `${d.day_of_month}日`)
    const inputs = details.map(d => safeNum(d.daily_input))
    const loads = details.map(d => Number(safeNum(d.device_load_rate).toFixed(1)))
    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross', label: { backgroundColor: '#64748b' } },
        backgroundColor: '#fff', borderColor: CHART_COLORS.tooltipBorder,
        borderWidth: 1, textStyle: { color: '#334155', fontSize: 12 },
        extraCssText: `box-shadow:${CHART_COLORS.tooltipShadow};border-radius:8px;`,
      },
      legend: {
        data: ['日加工量(吨)', '装置负荷率(%)'], top: 4,
        textStyle: { fontSize: 11, color: CHART_COLORS.label },
      },
      grid: { left: 8, right: 16, bottom: 8, top: 40, containLabel: true },
      xAxis: {
        type: 'category', data: days, boundaryGap: true,
        axisLabel: { fontSize: 10, color: CHART_COLORS.labelDim, interval: 1 },
        axisTick: { show: false },
        axisLine: { lineStyle: { color: CHART_COLORS.axisLine } },
      },
      yAxis: [
        { type: 'value', name: '加工量(吨)', nameTextStyle: { fontSize: 10, color: CHART_COLORS.labelDim },
          axisLabel: { fontSize: 10, color: CHART_COLORS.labelDim },
          splitLine: { lineStyle: { color: CHART_COLORS.splitLine } },
        },
        { type: 'value', name: '负荷率(%)', nameTextStyle: { fontSize: 10, color: CHART_COLORS.labelDim },
          axisLabel: { fontSize: 10, color: CHART_COLORS.labelDim, formatter: '{value}%' },
          splitLine: { show: false },
        },
      ],
      series: [
        { name: '日加工量(吨)', type: 'bar', data: inputs,
          itemStyle: { color: CHART_COLORS.blue, borderRadius: [3, 3, 0, 0] },
          barWidth: '60%',
        },
        { name: '装置负荷率(%)', type: 'line', yAxisIndex: 1, data: loads,
          smooth: true, symbol: 'circle', symbolSize: 5,
          lineStyle: { width: 2, color: CHART_COLORS.amber },
          itemStyle: { color: CHART_COLORS.amber },
        },
      ],
    }
  }, [details])

  // 批次甘特图 option（custom series 渲染横向条形，直观展示各批次时序）
  const ganttOption = useMemo(() => {
    const gantt = result?.viz_gantt ?? []
    if (!gantt.length) return null
    const units = [...new Set(gantt.map(b => b.unit))]
    const days = result?.viz_input?.days ?? 30
    // 油种 → 颜色
    const PALETTE = ['#3b82f6', '#22c55e', '#8b5cf6', '#f59e0b', '#ef4444', '#06b6d4', '#ec4899', '#14b8a6']
    const crudeColor: Record<string, string> = {}
    let ci = 0
    for (const b of gantt) {
      if (!crudeColor[b.crude]) { crudeColor[b.crude] = PALETTE[ci % PALETTE.length]; ci++ }
    }
    return {
      tooltip: {
        formatter: (params: any) => {
          const b = gantt[params.dataIndex]
          if (!b) return ''
          return `<b>批次${b.batch_idx}: ${b.crude}</b><br/>`
            + `装置: ${b.unit}<br/>`
            + `开始: 第${b.start_day}天 (${b.start_h}h)<br/>`
            + `持续: ${b.dur_h}h<br/>`
            + `加工量: ${fNum(b.tons)} 吨`
        },
      },
      grid: { left: 8, right: 16, bottom: 8, top: 16, containLabel: true },
      xAxis: {
        type: 'value', name: '小时', min: 0, max: days * 24,
        nameTextStyle: { fontSize: 10, color: CHART_COLORS.labelDim },
        axisLabel: { fontSize: 10, color: CHART_COLORS.labelDim },
        splitLine: { lineStyle: { color: CHART_COLORS.splitLine } },
      },
      yAxis: {
        type: 'category', data: units,
        axisLabel: { fontSize: 11, color: CHART_COLORS.label },
        axisTick: { show: false },
        axisLine: { lineStyle: { color: CHART_COLORS.axisLine } },
      },
      series: [{
        type: 'custom',
        renderItem: (_params: unknown, api: any) => {
          const catIdx = api.value(0)
          const start = api.coord([api.value(1), catIdx])
          const end = api.coord([api.value(2), catIdx])
          const h = api.size([0, 1])[1] * 0.6
          return {
            type: 'rect',
            shape: { x: start[0], y: start[1] - h / 2, width: Math.max(end[0] - start[0], 3), height: h },
            style: { fill: api.value(3), stroke: '#fff', lineWidth: 1 },
          }
        },
        encode: { x: [1, 2], y: 0 },
        data: gantt.map(b => [units.indexOf(b.unit), b.start_h, b.end_h, crudeColor[b.crude] || '#64748b']),
      }],
    }
  }, [result])

  // 罐区库存曲线 option（各罐每日库存变化趋势）
  const inventoryOption = useMemo(() => {
    const inv = result?.viz_inventory
    if (!inv || !Object.keys(inv).length) return null
    const days = result?.viz_input?.days ?? 30
    const tankIds = Object.keys(inv)
    const dayLabels = Array.from({ length: days }, (_, i) => `${i + 1}日`)
    return {
      tooltip: {
        trigger: 'axis',
        backgroundColor: '#fff', borderColor: CHART_COLORS.tooltipBorder,
        borderWidth: 1, textStyle: { color: '#334155', fontSize: 12 },
        extraCssText: `box-shadow:${CHART_COLORS.tooltipShadow};border-radius:8px;`,
      },
      legend: {
        data: tankIds, top: 4,
        textStyle: { fontSize: 11, color: CHART_COLORS.label },
      },
      grid: { left: 8, right: 16, bottom: 8, top: 40, containLabel: true },
      xAxis: {
        type: 'category', data: dayLabels, boundaryGap: true,
        axisLabel: { fontSize: 10, color: CHART_COLORS.labelDim, interval: 1 },
        axisTick: { show: false },
        axisLine: { lineStyle: { color: CHART_COLORS.axisLine } },
      },
      yAxis: {
        type: 'value', name: '库存(吨)',
        nameTextStyle: { fontSize: 10, color: CHART_COLORS.labelDim },
        axisLabel: { fontSize: 10, color: CHART_COLORS.labelDim },
        splitLine: { lineStyle: { color: CHART_COLORS.splitLine } },
      },
      series: tankIds.map(tid => ({
        name: tid, type: 'line', data: inv[tid],
        smooth: true, symbol: 'none', lineStyle: { width: 1.5 },
      })),
    }
  }, [result])

  return (
    <div className="space-y-4">
      {/* 头部：标题 + 月份选择 + 算法选择 + 求解按钮 */}
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-xl font-bold text-slate-900 flex items-center gap-2">
            <Cpu className="w-5 h-5 text-blue-600" />排产求解
          </h1>
          <p className="text-sm text-slate-500 mt-0.5">
            选择月份与算法 → 调用 CP-SAT 约束规划求解 → 输出按天排产明细（含罐区物流仿真）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-500">月份</span>
          <select value={month} onChange={e => setMonth(e.target.value)} disabled={loading}
            className="h-9 rounded-md border border-slate-200 bg-white px-3 text-sm text-slate-700 disabled:bg-slate-50">
            {months.map(m => <option key={m.key} value={m.key}>{m.label}</option>)}
          </select>
          {/* 算法选择器 */}
          <div className="flex items-center rounded-md border border-slate-200 bg-white overflow-hidden">
            <button
              onClick={() => setSolver('colleague')}
              disabled={loading}
              className={`h-9 px-3 text-xs font-medium transition-colors ${
                solver === 'colleague'
                  ? 'bg-blue-600 text-white'
                  : 'text-slate-600 hover:bg-slate-50'
              } disabled:opacity-50`}
            >
              同事算法
            </button>
            <button
              onClick={() => setSolver('mine')}
              disabled={loading}
              className={`h-9 px-3 text-xs font-medium transition-colors ${
                solver === 'mine'
                  ? 'bg-purple-600 text-white'
                  : 'text-slate-600 hover:bg-slate-50'
              } disabled:opacity-50`}
            >
              我的算法
            </button>
          </div>
          <button onClick={solve} disabled={loading || serviceDown}
            className={`inline-flex items-center h-9 px-6 rounded-md text-white text-sm font-medium disabled:opacity-50 ${
              solver === 'mine' ? 'bg-purple-600 hover:bg-purple-700' : 'bg-blue-600 hover:bg-blue-700'
            }`}>
            {loading ? <><Loader2 className="w-4 h-4 mr-1.5 animate-spin" />求解中…</> : <><Play className="w-4 h-4 mr-1.5" />排产求解</>}
          </button>
        </div>
      </div>

      {/* 算法说明 */}
      <div className="text-xs text-slate-400 -mt-1">
        {solver === 'colleague'
          ? '同事算法：两层架构（CP-SAT 批次排程 + 罐区物流仿真），可能产生罐容告警'
          : '我的算法：统一 CP-SAT（日粒度 + 内嵌罐容约束 + 掺炼配方 + 多轮求解 + 管道排期），一次求解保证物流可行'}
      </div>

      {serviceDown && (
        <div className="p-4 rounded-xl border border-red-300 bg-red-50/60">
          <div className="flex items-center gap-2 text-sm text-red-700">
            <AlertTriangle className="w-4 h-4" />
            <span className="font-bold">solve_v1 服务未启动</span>
            <span className="text-red-600">— 请先运行 <code className="bg-white/70 px-1.5 py-0.5 rounded border border-red-200 font-mono text-xs">python -m calc_service.backend.app</code>（端口 5081）</span>
          </div>
        </div>
      )}

      {error && (
        <div className="p-4 rounded-xl border border-red-200 bg-red-50/40">
          <div className="flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 text-red-500 mt-0.5 shrink-0" />
            <div className="text-xs text-red-700 font-mono whitespace-pre-wrap break-all">{error}</div>
          </div>
        </div>
      )}

      {/* CP-SAT 算法说明卡片 */}
      <div className="p-4 rounded-xl border border-blue-100 bg-blue-50/40">
        <div className="flex items-start gap-2">
          <Database className="w-4 h-4 text-blue-500 mt-0.5 shrink-0" />
          <div className="text-xs text-slate-600">
            <span className="font-semibold text-slate-800">CP-SAT 约束规划排产</span>
            ：三阶段架构（Phase 0 到港预处理 → Phase 1 CP-SAT 求解 → Phase 2 罐区物流仿真），
            输入为同事的 Excel 模板（含到港计划/罐参数/掺炼配方），输出按天排产明细。
            <span className="text-blue-600 ml-1">已替代旧 LP 算法。</span>
          </div>
        </div>
      </div>

      {/* 求解中：分阶段进度展示（替代纯转圈等待） */}
      {loading && (
        <div className="space-y-4">
          {/* 顶部状态栏 */}
          <div className="flex items-center gap-3 p-4 rounded-xl border border-blue-100 bg-blue-50/40">
            <Loader2 className="w-5 h-5 animate-spin text-blue-500 shrink-0" />
            <div className="min-w-0 flex-1">
              <div className="font-medium text-slate-800 flex items-center gap-2">
                CP-SAT 排产求解中…
                <span className="text-xs font-mono text-slate-400">已耗时 {(elapsedMs / 1000).toFixed(0)}s</span>
                {earlyStopReason && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-700 font-medium">✓ {earlyStopReason}</span>
                )}
              </div>
              <div className="text-xs text-slate-500 mt-0.5">
                {!progress && '正在读取 Excel 模板并解析输入参数…'}
                {progress?.phase === 'blend_built' && solver === 'mine' &&
                  `掺炼单元构建完成：${progress.n_blend_units} 个掺炼单元（${progress.n_blend_combos} 组负荷候选），${progress.n_single_crudes} 种单炼 + ${progress.n_blend_only} 种掺炼专用。正在预估批次优先级…`}
                {progress?.phase === 'phase0_done' && `Phase 0 到港预处理完成：${progress.n_ships} 艘船预分配罐位，${progress.n_constraints} 条腾容约束，${progress.n_windows} 条卸油停产窗口`}
                {progress?.phase === 'round_start' && `第 ${progress.round}/${progress.n_rounds} 轮求解启动（seed=${progress.seed}）${progress.preferred_count !== undefined && progress.preferred_count > 0 ? `· 已有 ${progress.preferred_count}/${progress.n_preferred} 个优选解` : ''}`}
                {progress?.phase === 'cp_solving' && `第 ${progress.round}/${progress.n_rounds} 轮 CP-SAT 约束规划求解中（时限 ${progress.time_limit}s，seed=${progress.seed}）…`}
                {progress?.phase === 'round_done' && `第 ${progress.round} 轮 CP-SAT 求解完成（${progress.cp_status}，${progress.cp_time}s，${progress.nb_batches} 批次），正在提取解 + 评分…`}
                {progress?.phase === 'round_filled' && `第 ${progress.round} 轮评分完成：达标 ${progress.compliant}/${progress.n_judged}，优选时长 ${progress.dur_pct}%，换油 ${progress.switches} 次${progress.compliant === progress.n_judged ? ' ★ 全达标' : ''}`}
                {progress?.phase === 'round_failed' && `第 ${progress.round} 轮求解未完成（${progress.cp_status}，${progress.cp_time}s），跳过该轮`}
                {progress?.phase === 'early_stop' && `多轮搜索提前结束：${progress.reason}`}
              </div>
            </div>
          </div>

          {/* 分阶段流水线指示器 */}
          <div className="rounded-xl border border-slate-200 bg-white p-4">
            <div className="flex items-center gap-1 flex-wrap">
              {/* 阶段1: 输入解析 */}
              <StageStep icon={<CheckCircle className="w-4 h-4" />} label="输入解析" status="done" sub="Excel模板" />
              <StageArrow />

              {/* 阶段2: 掺炼构建（仅我的算法） */}
              {solver === 'mine' && (
                <>
                  <StageStep
                    icon={blendInfo ? <CheckCircle className="w-4 h-4" /> : <Loader2 className="w-4 h-4 animate-spin" />}
                    label="掺炼构建" status={blendInfo ? 'done' : 'active'}
                    sub={blendInfo ? `${blendInfo.units}单元` : undefined}
                  />
                  <StageArrow />
                </>
              )}

              {/* 阶段3: Phase 0 到港预处理 */}
              <StageStep
                icon={progress && ['phase0_done','round_start','cp_solving','round_done','round_filled','early_stop'].includes(progress.phase)
                  ? <CheckCircle className="w-4 h-4" /> : (progress ? <Loader2 className="w-4 h-4 animate-spin" /> : <span className="w-4 h-4 rounded-full border-2 border-slate-200 inline-block" />)}
                label="Phase 0 预处理" status={progress && ['phase0_done','round_start','cp_solving','round_done','round_filled','early_stop'].includes(progress.phase) ? 'done' : (progress ? 'active' : 'pending')}
                sub={progress?.n_ships !== undefined ? `${progress.n_ships}船` : undefined}
              />
              <StageArrow />

              {/* 阶段4: 多轮 CP-SAT 求解 */}
              <StageStep
                icon={rounds.length > 0 && rounds.some(r => r.compliant !== undefined)
                  ? (earlyStopReason || rounds.length >= (progress?.n_rounds ?? 4) ? <CheckCircle className="w-4 h-4" /> : <Loader2 className="w-4 h-4 animate-spin" />)
                  : (progress && ['round_start','cp_solving','round_done','round_filled'].includes(progress.phase) ? <Loader2 className="w-4 h-4 animate-spin" /> : <span className="w-4 h-4 rounded-full border-2 border-slate-200 inline-block" />)}
                label="多轮 CP-SAT" status={earlyStopReason ? 'done' : (rounds.length > 0 ? 'active' : 'pending')}
                sub={rounds.length > 0 ? `${rounds.length}/${progress?.n_rounds ?? '?'}轮` : undefined}
              />
              <StageArrow />

              {/* 阶段5: 完成 */}
              <StageStep
                icon={<span className="w-4 h-4 rounded-full border-2 border-slate-200 inline-block" />}
                label="完成" status="pending"
              />
            </div>
          </div>

          {/* 掺炼单元信息卡片 */}
          {blendInfo && solver === 'mine' && (
            <div className="rounded-xl border border-purple-100 bg-purple-50/30 p-4">
              <div className="flex items-center gap-2 mb-3">
                <Fuel className="w-4 h-4 text-purple-500" />
                <span className="text-sm font-medium text-slate-700">掺炼配方解析</span>
              </div>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <div className="text-center">
                  <div className="text-2xl font-bold text-purple-600">{blendInfo.units}</div>
                  <div className="text-xs text-slate-500">掺炼单元</div>
                </div>
                <div className="text-center">
                  <div className="text-2xl font-bold text-purple-600">{blendInfo.combos}</div>
                  <div className="text-xs text-slate-500">负荷组合</div>
                </div>
                <div className="text-center">
                  <div className="text-2xl font-bold text-blue-600">{blendInfo.single}</div>
                  <div className="text-xs text-slate-500">单炼油种</div>
                </div>
                <div className="text-center">
                  <div className="text-2xl font-bold text-amber-600">{blendInfo.blendOnly}</div>
                  <div className="text-xs text-slate-500">掺炼专用油种</div>
                </div>
              </div>
            </div>
          )}

          {/* 各轮次求解结果（随轮次完成逐步填充） */}
          {rounds.length > 0 && (
            <div className="rounded-xl border border-slate-200 bg-white overflow-hidden">
              <div className="px-4 py-2.5 border-b border-slate-100 bg-slate-50/60 flex items-center gap-2">
                <Layers className="w-4 h-4 text-purple-500" />
                <span className="text-sm font-medium text-slate-700">多轮求解进度</span>
                <span className="text-xs text-slate-400">
                  {rounds.filter(r => r.is_preferred).length} 个优选 ·
                  {' '}{rounds.filter(r => r.compliant !== undefined).length}/{rounds.length} 已完成
                </span>
              </div>
              <div className="divide-y divide-slate-50">
                {rounds.map(r => (
                  <div key={r.round} className="flex items-center gap-3 px-4 py-2.5 text-sm flex-wrap">
                    <span className="font-mono text-xs text-slate-400 w-14">第 {r.round} 轮</span>
                    {r.solving && !r.cp_status ? (
                      <>
                        <Loader2 className="w-3 h-3 animate-spin text-blue-500" />
                        <span className="text-xs text-blue-600">CP-SAT 求解中…</span>
                        <span className="text-xs text-slate-400">seed={r.seed}</span>
                      </>
                    ) : r.cp_status ? (
                      <>
                        <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${r.cp_status === 'OPTIMAL' ? 'bg-green-50 text-green-600' : 'bg-blue-50 text-blue-600'}`}>
                          {r.cp_status}
                        </span>
                        <span className="text-xs text-slate-500">seed={r.seed}</span>
                        <span className="text-xs text-slate-400">{r.cp_time}s</span>
                        {r.nb_batches !== undefined && <span className="text-xs text-slate-500">{r.nb_batches}批</span>}
                        {r.compliant !== undefined ? (
                          <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${r.compliant === r.n_judged ? 'bg-green-50 text-green-600' : 'bg-amber-50 text-amber-600'}`}>
                            达标 {r.compliant}/{r.n_judged}
                          </span>
                        ) : (
                          <span className="text-xs text-slate-400">评分中…</span>
                        )}
                        {r.dur_pct !== undefined && <span className="text-xs text-slate-500">优选 {r.dur_pct}%</span>}
                        {r.switches !== undefined && <span className="text-xs text-slate-500">换油 {r.switches}</span>}
                        {r.is_preferred && <span className="text-xs px-1.5 py-0.5 rounded bg-green-100 text-green-700 font-medium">★优选</span>}
                      </>
                    ) : (
                      <span className="text-xs text-slate-400">等待中…</span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Phase 0 可视化：船舶到港 + 罐区初始状态 */}
          {vizInput && (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* 船舶到港计划 */}
              {vizInput.ships.length > 0 && (
                <div className="rounded-xl border border-slate-200 bg-white overflow-hidden">
                  <div className="px-4 py-2.5 border-b border-slate-100 bg-slate-50/60 flex items-center gap-2">
                    <Ship className="w-4 h-4 text-blue-500" />
                    <span className="text-sm font-medium text-slate-700">船舶到港计划</span>
                    <span className="text-xs text-slate-400">{vizInput.ships.length} 艘</span>
                  </div>
                  <div className="p-3 space-y-2">
                    {vizInput.ships.map((s, i) => (
                      <div key={i} className="flex items-center gap-2 text-sm">
                        <span className="text-xs text-slate-400 w-8">船{i + 1}</span>
                        <span className="px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 text-xs font-mono">{s.crude}</span>
                        <span className="text-slate-600 font-mono text-xs">{fNum(s.ton)}t</span>
                        <span className="text-slate-300">›</span>
                        <span className="text-slate-600 text-xs">第 {s.berth_day} 天</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
              {/* 罐区初始状态 */}
              {vizInput.tanks.length > 0 && (
                <div className="rounded-xl border border-slate-200 bg-white overflow-hidden">
                  <div className="px-4 py-2.5 border-b border-slate-100 bg-slate-50/60 flex items-center gap-2">
                    <Fuel className="w-4 h-4 text-amber-500" />
                    <span className="text-sm font-medium text-slate-700">罐区初始状态</span>
                    <span className="text-xs text-slate-400">{vizInput.tanks.length} 个</span>
                  </div>
                  <div className="p-3 grid grid-cols-2 gap-2">
                    {vizInput.tanks.map(t => (
                      <div key={t.tank_id} className="p-2 rounded-lg border border-slate-100 bg-slate-50/40">
                        <div className="flex items-center gap-1">
                          <span className="font-mono text-xs font-medium text-slate-700">{t.tank_id}</span>
                          <span className={`text-[10px] px-1 rounded ${t.type === 'G' ? 'bg-blue-100 text-blue-600' : 'bg-amber-100 text-amber-600'}`}>
                            {t.type}
                          </span>
                        </div>
                        <div className="text-[11px] text-slate-500 mt-0.5">容量 {fNum(t.capacity)}</div>
                        {t.initial_crude && (
                          <div className="text-[11px] text-slate-600">
                            <span className="font-mono">{t.initial_crude}</span> {fNum(t.initial_ton)}t
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}
      {result && !result.success && (
        <div className="p-5 rounded-xl border border-amber-200 bg-amber-50/40">
          <div className="flex items-center gap-2 text-amber-800">
            <AlertTriangle className="w-5 h-5" /><span className="font-semibold">排产求解失败</span>
          </div>
          <p className="text-sm text-amber-700 mt-2">{result.message}</p>
        </div>
      )}
      {result && result.success && (
        <div className="space-y-4">
          {/* KPI */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <KpiCard icon={TrendIcon} label="月总加工量" value={`${fNum(totalInput)} 吨`} sub={`计划 ${result.plan_id}`} accent="from-blue-500 to-blue-600" />
            <KpiCard icon={Calendar} label="排产天数" value={`${details.length} 天`} sub="按日明细" accent="from-purple-500 to-purple-600" />
            <KpiCard icon={Gauge} label="平均装置负荷率" value={`${avgLoad.toFixed(1)}%`} sub="常减压 cjy_01" accent="from-emerald-500 to-emerald-600" />
            <KpiCard icon={Clock} label="求解耗时" value={`${(elapsedMs / 1000).toFixed(1)} 秒`} sub={result.solver === 'mine' ? '统一CP-SAT' : '同事CP-SAT'} accent="from-amber-500 to-orange-500" />
          </div>

          {/* CP-SAT 物流告警 */}
          {logisticsWarnings.length > 0 && (
            <div className="rounded-xl border border-amber-200 bg-amber-50/40 overflow-hidden">
              <div className="px-4 py-3 border-b border-amber-100 bg-amber-50/60">
                <CardHead icon={AlertCircle} title="罐区物流告警" accent="from-amber-500 to-orange-500"
                  hint={`${logisticsWarnings.length} 条告警 · 供料不足/超容/边供边受等`} />
              </div>
              <div className="p-3 space-y-1.5 max-h-48 overflow-y-auto">
                {logisticsWarnings.map((w, i) => (
                  <div key={i} className="flex items-start gap-2 text-[12px] text-amber-800">
                    <AlertCircle className="w-3 h-3 mt-0.5 shrink-0 text-amber-500" />
                    <span className="font-mono">{w}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 批次甘特图（CP-SAT 求解的批次时序） */}
          {ganttOption && (
            <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm">
              <CardHead icon={Layers} title="批次加工甘特图" accent="from-purple-500 to-purple-600"
                hint="每个色块=一个加工批次 · 横轴=月内小时 · 颜色按油种区分" />
              <div className="mt-2">
                <EChart option={ganttOption} height={280} />
              </div>
            </div>
          )}

          {/* 罐区库存曲线（各罐每日库存变化） */}
          {inventoryOption && (
            <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm">
              <CardHead icon={Fuel} title="罐区库存趋势" accent="from-amber-500 to-orange-500"
                hint="各罐每日可用库存 · 反映卸油/加工/转输的动态变化" />
              <div className="mt-2">
                <EChart option={inventoryOption} height={300} />
              </div>
            </div>
          )}

          {/* 按天加工量趋势图（柱=日加工量 线=装置负荷率） */}
          {trendOption && (
            <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm">
              <CardHead icon={TrendIcon} title="按天加工量趋势" accent="from-blue-500 to-blue-600"
                hint="日加工量(柱) · 装置负荷率(线)" />
              <div className="mt-2">
                <EChart option={trendOption} height={300} />
              </div>
            </div>
          )}

          {/* 按天排产明细 */}
          <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
            <div className="px-4 py-3 border-b border-slate-100 bg-slate-50/60">
              <CardHead icon={Calendar} title="按天排产明细" accent="from-purple-500 to-purple-600"
                hint="日加工量 / 油种配比 / 期末库存 / 装置负荷率" />
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
                    <th className="text-center font-medium px-3 py-2 w-12">日</th>
                    <th className="text-right font-medium px-3 py-2">日加工量(吨)</th>
                    <th className="text-center font-medium px-3 py-2 w-16">工时(h)</th>
                    <th className="text-left font-medium px-3 py-2">油种配比</th>
                    <th className="text-left font-medium px-3 py-2">期末库存</th>
                    <th className="text-right font-medium px-3 py-2 w-20">负荷率</th>
                  </tr>
                </thead>
                <tbody>
                  {details.map(d => {
                    const blend = parseDict(d.blend_detail)
                    const stock = parseDict(d.crude_stock_status)
                    return (
                      <tr key={d.day_of_month} className="border-b border-slate-50 last:border-0 hover:bg-slate-50/40">
                        <td className="px-3 py-2 text-center text-slate-400 font-mono text-xs">{d.day_of_month}</td>
                        <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(safeNum(d.daily_input))}</td>
                        <td className="px-3 py-2 text-center font-mono text-xs text-slate-600">{d.hours}</td>
                        <td className="px-3 py-2">
                          <div className="flex flex-wrap gap-1">
                            {Object.entries(blend).map(([cid, tons]) => tons > 0 && (
                              <span key={cid} className="inline-block px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 text-[11px] font-mono">
                                {crudeName(cid)} {fNum(tons)}
                              </span>
                            ))}
                          </div>
                        </td>
                        <td className="px-3 py-2">
                          <div className="flex flex-wrap gap-1">
                            {Object.entries(stock).map(([cid, tons]) => (
                              <span key={cid} className="inline-block px-1.5 py-0.5 rounded bg-slate-100 text-slate-600 text-[11px] font-mono">
                                {crudeName(cid)} {fNum(tons)}
                              </span>
                            ))}
                          </div>
                        </td>
                        <td className="px-3 py-2 text-right font-mono text-slate-700">{safeNum(d.device_load_rate).toFixed(1)}%</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
