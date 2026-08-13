'use client'

import { useState, useEffect, useMemo, useRef, type PointerEvent as RPointerEvent } from 'react'
import {
  Play, Loader2, GitBranch, AlertTriangle, Layers, Network, Shuffle, Calendar,
  ChevronDown, ChevronRight as ChevRight,
} from 'lucide-react'
import {
  fNum, defaultCrudeName, MODE_CN, MODE_SHORT, normalizeMonth, KpiCard, CardHead,
} from '@/components/SolveResult'
import ShutdownPanel, { ShutdownItem, ShutdownInfo } from '@/components/ShutdownPanel'

type CrudeMap = Record<string, { id: string; name: string; cost: number }>

// enumerate_switches 返回的批次（switch_planner.identify_batches 产出）
type SwitchBatch = {
  batch_id: number; start_day: number; end_day: number
  crude_type: string; total_input: number; daily_inputs: number[]; days: number
  shutdown_intervals?: Record<string, unknown>
}
// generate_switch_combinations 产出：每月最多 1 次阀门切换，2n 种组合
// feasible/bottleneck/infeasible_summary/device_notes/overload_details/monthly_load 由
// enumerate_switches 的轻量容量校验追加（capacity_only，不算效益）
type InputSubSource = {
  conn_id: string
  from_device_name: string
  from_product_name: string
  yield_rate: number
  special_var?: string | null
  special_var_note?: string
  flow: number
  is_switched_off: boolean
}
type InputSource = {
  conn_id: string
  from_device_id: string
  from_device_name: string
  from_product_name: string
  yield_rate: number
  special_var?: string | null
  special_var_note?: string
  flow: number
  is_switched_off: boolean
  sub_sources: InputSubSource[]
}
type OverloadDevice = {
  device_id: string; name: string; note: string
  monthly_input: number; monthly_capacity: number; monthly_util: number; is_overloaded: boolean
  input_sources?: InputSource[]
}
type OverloadDetail = {
  batch_id: number; crude_type: string; mode: string
  daily_input: number; total_input: number
  jian1_to_diesel: number; jian1_to_wax: number
  devices: OverloadDevice[]
  flow_diagram?: FlowDiagram
  feasible?: boolean
}
// 全装置流程图（数字孪生）：节点 + 连线，供分层流程图渲染
type FlowNode = {
  device_id: string; name: string; type: string  // start | normal | tank
  input: number
  // 加工装置额外字段（取自 monthly_load，用于超容高亮+利用率展示）
  monthly_util?: number; monthly_capacity?: number; is_overloaded?: boolean
}
type FlowEdge = {
  conn_id: string
  from_device_id: string; to_device_id: string
  from_device_name: string; to_device_name: string
  product_name: string
  special_var?: string | null
  flow: number
  is_switched_off: boolean
}
type FlowDiagram = { nodes: FlowNode[]; edges: FlowEdge[] }
type SwitchCombo = {
  combination_id: number; switches: Record<string, string>
  description: string; switch_position: number; initial_mode: string; switch_desc?: string
  feasible?: boolean | null      // true可行 / false不可行 / null未校验
  infeasible_summary?: string
  bottleneck?: Array<{ device_id: string; device_name: string; input_amount: number; effective_capacity: number; excess: number }>
  device_notes?: Record<string, string>   // 装置名 → note（阈值调整说明）
  overload_details?: OverloadDetail[]     // 月度负荷计算链（供展开看负荷分布）
  monthly_load?: { total_days: number; overload_count: number; summary: string; devices: Array<{device_id: string; name: string; monthly_util: number; is_overloaded: boolean}> }
}
type EnumResp = {
  success: boolean; plan_id: string; plan_month: string
  batches: SwitchBatch[]; combinations: SwitchCombo[]
  total_combinations: number; message: string
  shutdown?: ShutdownInfo
}

const FALLBACK_MONTHS = [
  { key: '2026-01', label: '2026年1月' },
  { key: '2026-02', label: '2026年2月' },
  { key: '2026-03', label: '2026年3月' },
]

// 批次时间轴油种配色（按出现顺序分配）
const BATCH_COLORS = [
  'bg-blue-500', 'bg-purple-500', 'bg-emerald-500',
  'bg-amber-500', 'bg-rose-500', 'bg-cyan-500',
]

export default function BatchesPage() {
  const [month, setMonth] = useState('2026-01')
  const [months, setMonths] = useState(FALLBACK_MONTHS)
  const [crudeMap, setCrudeMap] = useState<CrudeMap>({})
  const [serviceDown, setServiceDown] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<EnumResp | null>(null)
  const [elapsedMs, setElapsedMs] = useState(0)
  const [expandedBatch, setExpandedBatch] = useState<number | null>(null)
  const [expandedOverload, setExpandedOverload] = useState<number | null>(null)  // 展开的不可行组合（看超容计算链）
  const [shutdown, setShutdown] = useState<ShutdownItem[]>([])

  useEffect(() => {
    let cancelled = false
    setServiceDown(false)
    fetch('/api/scheduling/data', { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(d => { if (!cancelled && d?.success && d.data?.crude_types) setCrudeMap(d.data.crude_types) })
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

  // ── 触发批次划分与组合识别（流程②，不评估效益）──
  async function run() {
    setLoading(true); setError(null); setResult(null); setExpandedBatch(null)
    const startedAt = Date.now()
    try {
      const r = await fetch('/api/scheduling/enumerate_switches', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ plan_month: month, shutdown_config: shutdown }),
      })
      const body = await r.json().catch(() => ({}))
      if (!r.ok) throw new Error(body?.message || body?.detail || `HTTP ${r.status}`)
      setResult(body as EnumResp)
    } catch (e) {
      setError(e instanceof Error ? e.message : '识别失败，请确认 solve_v1 服务已启动（:5081）')
    } finally {
      setElapsedMs(Date.now() - startedAt)
      setLoading(false)
    }
  }

  const crudeName = (id: string) => defaultCrudeName(id, crudeMap)

  // 油种 → 颜色映射（按批次出现顺序）
  const crudeColor = useMemo(() => {
    const map: Record<string, string> = {}
    let ci = 0
    for (const b of result?.batches ?? []) {
      if (!(b.crude_type in map)) { map[b.crude_type] = BATCH_COLORS[ci % BATCH_COLORS.length]; ci++ }
    }
    return map
  }, [result])

  const batches = result?.batches ?? []
  const combos = result?.combinations ?? []
  const maxEnd = batches.length ? Math.max(...batches.map(b => b.end_day)) : 0

  return (
    <div className="space-y-5 animate-fade-in-up">
      {/* 标题 + 月份 + 识别按钮 */}
      <div className="flex items-end justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900 flex items-center gap-2">
            <GitBranch className="w-5 h-5 text-blue-600" />批次划分与切换组合
          </h1>
          <p className="text-sm text-slate-500 mt-0.5">
            选取月份 → 基于已落盘排产明细识别连续同油种批次 → 枚举减一线阀门切换组合（每月最多 1 次切换，共 2n 种）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-500">月份</span>
          <select value={month} onChange={e => setMonth(e.target.value)} disabled={loading}
            className="h-9 rounded-md border border-slate-200 bg-white px-3 text-sm text-slate-700 disabled:bg-slate-50">
            {months.map(m => <option key={m.key} value={m.key}>{m.label}</option>)}
          </select>
          <button onClick={run} disabled={loading || serviceDown}
            className="inline-flex items-center h-9 px-6 rounded-md bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium disabled:opacity-50">
            {loading ? <><Loader2 className="w-4 h-4 mr-1.5 animate-spin" />识别中…</> : <><Play className="w-4 h-4 mr-1.5" />开始识别</>}
          </button>
        </div>
      </div>

      {/* 装置停工声明面板 */}
      <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm">
        <ShutdownPanel value={shutdown} onChange={setShutdown} disabled={loading} month={month} />
      </div>

      {result?.shutdown?.conflicts && result.shutdown.conflicts.length > 0 && (
        <div className="p-4 rounded-xl border border-red-200 bg-red-50/40">
          <div className="flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 text-red-500 mt-0.5 shrink-0" />
            <div className="text-xs text-red-700 space-y-1">
              <div className="font-semibold">停工冲突：柴加与蜡加同日停工，减一线无去向</div>
              {result.shutdown.conflicts.map((c, i) => (
                <div key={i}>第{c.day}天 — {c.reason}</div>
              ))}
              <div>请调整起止日，避免两台装置停工窗口重叠。</div>
            </div>
          </div>
        </div>
      )}

      {serviceDown && (
        <div className="p-4 rounded-xl border border-red-300 bg-red-50/60">
          <div className="flex items-center gap-2 text-sm text-red-700">
            <AlertTriangle className="w-4 h-4" />
            <span className="font-bold">solve_v1 服务未启动</span>
            <span className="text-red-600">— 请先运行 <code className="bg-white/70 px-1.5 py-0.5 rounded border border-red-200 font-mono text-xs">python -m solve_v1.app</code>（端口 5081）</span>
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

      {loading && (
        <div className="flex items-center justify-center py-16 text-slate-400">
          <Loader2 className="w-5 h-5 animate-spin mr-2" />批次划分与组合识别中…
        </div>
      )}

      {result && !result.success && (
        <div className="p-5 rounded-xl border border-amber-200 bg-amber-50/40">
          <div className="flex items-center gap-2 text-amber-800">
            <AlertTriangle className="w-5 h-5" /><span className="font-semibold">识别失败</span>
          </div>
          <p className="text-sm text-amber-700 mt-2">{result.message}</p>
        </div>
      )}

      {result && result.success && (
        <div className="space-y-4">
          {/* KPI */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <KpiCard icon={Network} label="加工批次" value={`${batches.length} 个`} sub="连续同油种聚合" accent="from-emerald-500 to-emerald-600" />
            <KpiCard icon={Shuffle} label="切换组合数" value={`${result.total_combinations} 种`} sub="2 × 批次数" accent="from-purple-500 to-purple-600" />
            <KpiCard icon={Calendar} label="覆盖天数" value={`${maxEnd} 天`} sub={result.plan_id} accent="from-blue-500 to-blue-600" />
            <KpiCard icon={Layers} label="识别耗时" value={`${(elapsedMs / 1000).toFixed(2)} 秒`} sub="流程②" accent="from-amber-500 to-orange-500" />
          </div>

          {/* ① 批次时间轴（可视化连续同油种聚合） */}
          <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm">
            <CardHead icon={Network} title="批次划分时间轴" accent="from-emerald-500 to-emerald-600"
              hint="同一色块 = 同一油种连续加工批次" />
            <div className="mt-3">
            {batches.length > 0 && (
              <>
                <div className="flex h-9 rounded-md overflow-hidden border border-slate-200">
                  {batches.map(b => (
                    <div key={b.batch_id}
                      className={`${crudeColor[b.crude_type] || 'bg-slate-400'} grid place-items-center text-white text-[11px] font-medium transition-all hover:brightness-110 cursor-default`}
                      style={{ flexGrow: b.days, flexBasis: 0 }}
                      title={`${crudeName(b.crude_type)}：第${b.start_day}–${b.end_day}天，${fNum(b.total_input)}吨`}>
                      <span className="truncate px-1">{crudeName(b.crude_type)} · {b.start_day}-{b.end_day}天</span>
                    </div>
                  ))}
                </div>
                {/* 图例 */}
                <div className="flex flex-wrap gap-3 mt-2">
                  {Object.entries(crudeColor).map(([cid, color]) => (
                    <span key={cid} className="inline-flex items-center gap-1.5 text-[11px] text-slate-600">
                      <span className={`w-3 h-3 rounded-sm ${color}`} />{crudeName(cid)}
                    </span>
                  ))}
                </div>
              </>
            )}
            </div>
          </div>

          {/* ② 批次明细表（可展开按天） */}
          <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
            <div className="px-4 py-3 border-b border-slate-100 bg-slate-50/60">
              <CardHead icon={Network} title="批次明细" accent="from-emerald-500 to-emerald-600"
                hint="点击行展开按天加工量" />
            </div>
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
                  <th className="text-center font-medium px-3 py-2 w-12">批次</th>
                  <th className="text-left font-medium px-3 py-2">主力油种</th>
                  <th className="text-center font-medium px-3 py-2">起止天</th>
                  <th className="text-center font-medium px-3 py-2">天数</th>
                  <th className="text-right font-medium px-3 py-2">加工量(吨)</th>
                  <th className="text-right font-medium px-3 py-2">日均(吨)</th>
                </tr>
              </thead>
              <tbody>
                {batches.map(b => {
                  const isExp = expandedBatch === b.batch_id
                  return (
                    <FragmentRow key={b.batch_id}>
                      <tr className={`border-b border-slate-50 last:border-0 cursor-pointer hover:bg-slate-50/40 ${isExp ? 'bg-emerald-50/40' : ''}`}
                        onClick={() => setExpandedBatch(isExp ? null : b.batch_id)}>
                        <td className="px-3 py-2 text-center">
                          <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-emerald-100 text-emerald-700 text-xs font-bold">{b.batch_id}</span>
                          {b.shutdown_intervals && Object.keys(b.shutdown_intervals).length > 0 && (
                            <span className="block mt-1 text-[9px] text-red-500 leading-tight">停工</span>
                          )}
                        </td>
                        <td className="px-3 py-2 text-slate-700">
                          <span className="inline-flex items-center gap-1.5">
                            <span className={`w-2.5 h-2.5 rounded-sm ${crudeColor[b.crude_type] || 'bg-slate-400'}`} />
                            {crudeName(b.crude_type)}
                          </span>
                        </td>
                        <td className="px-3 py-2 text-center font-mono text-xs text-slate-600">{b.start_day}–{b.end_day}</td>
                        <td className="px-3 py-2 text-center text-slate-600">{b.days}</td>
                        <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(b.total_input)}</td>
                        <td className="px-3 py-2 text-right font-mono text-slate-500">{fNum(b.total_input / b.days)}</td>
                      </tr>
                      {isExp && (
                        <tr className="bg-slate-50/50">
                          <td colSpan={6} className="px-6 py-3">
                            <div className="text-[11px] text-slate-500 mb-2">按天加工明细（{b.days} 天）</div>
                            <div className="grid grid-cols-7 md:grid-cols-12 gap-1.5">
                              {b.daily_inputs.map((tons, i) => (
                                <div key={i} className="rounded border border-slate-200 bg-white px-2 py-1 text-center">
                                  <div className="text-[10px] text-slate-400">第{b.start_day + i}天</div>
                                  <div className="text-[11px] font-mono text-slate-700">{fNum(tons)}</div>
                                </div>
                              ))}
                            </div>
                          </td>
                        </tr>
                      )}
                    </FragmentRow>
                  )
                })}
              </tbody>
            </table>
          </div>

          {/* ③ 切换组合表（2n 种，可视化各组合的批次方向 + 容量约束校验） */}
          <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
            <div className="px-4 py-3 border-b border-slate-100 bg-slate-50/60">
              <CardHead icon={Shuffle} title="减一线切换组合枚举" accent="from-purple-500 to-purple-600"
                hint={shutdown.length ? '停工段强制方向已过滤 · 每月最多 1 次切换' : '每月最多 1 次切换 · X_ZERO 全去蜡油加氢 / Y_ZERO 全去柴油加氢'} />
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
                    <th className="text-center font-medium px-3 py-2 w-12">组合</th>
                    <th className="text-center font-medium px-3 py-2 w-24">初始方向</th>
                    <th className="text-center font-medium px-3 py-2 w-20">切换位置</th>
                    <th className="text-left font-medium px-3 py-2">各批次减一线方向</th>
                    <th className="text-center font-medium px-3 py-2 w-20">可行性</th>
                    <th className="text-left font-medium px-3 py-2">瓶颈装置 / 超容原因</th>
                    <th className="text-left font-medium px-3 py-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {combos.map(c => {
                    const isFeas = c.feasible === true
                    const isInfeas = c.feasible === false
                    const canExpand = (c.overload_details?.length ?? 0) > 0
                    const isExpOv = expandedOverload === c.combination_id
                    return (
                      <FragmentRow key={c.combination_id}>
                        <tr
                          className={`border-b border-slate-50 last:border-0 hover:bg-slate-50/40 ${isInfeas ? 'bg-slate-50/40 text-slate-400' : ''} ${canExpand ? 'cursor-pointer' : ''}`}
                          onClick={() => canExpand && setExpandedOverload(isExpOv ? null : c.combination_id)}
                        >
                          <td className="px-3 py-2 text-center text-slate-400 font-mono text-xs">
                            {canExpand && (isExpOv
                              ? <ChevronDown className="w-3 h-3 inline text-slate-400 mr-0.5" />
                              : <ChevRight className="w-3 h-3 inline text-slate-300 rotate-90 mr-0.5" />)}
                            #{c.combination_id}
                          </td>
                          <td className="px-3 py-2 text-center">
                            <span className={`inline-block px-2 py-0.5 rounded text-[11px] font-medium ${c.initial_mode === 'X_ZERO' ? 'bg-purple-50 text-purple-700' : 'bg-blue-50 text-blue-700'}`}>
                              {MODE_SHORT[c.initial_mode] || c.initial_mode}
                            </span>
                          </td>
                          <td className="px-3 py-2 text-center font-mono text-xs text-slate-600">
                            {c.switch_position === 0 ? '不切换' : `第${c.switch_position}批后`}
                          </td>
                          <td className="px-3 py-2">
                            <div className="flex items-center gap-1">
                              {batches.map(b => {
                                const mode = c.switches[String(b.batch_id)] || c.switches[b.batch_id]
                                return (
                                  <span key={b.batch_id}
                                    className={`inline-flex items-center justify-center w-7 h-7 rounded text-[10px] font-bold text-white ${mode === 'X_ZERO' ? 'bg-purple-500' : 'bg-blue-500'}`}
                                    title={`批次${b.batch_id}: ${MODE_CN[mode] || mode}`}>
                                    {b.batch_id}{mode === 'X_ZERO' ? '蜡' : '柴'}
                                  </span>
                                )
                              })}
                            </div>
                          </td>
                          <td className="px-3 py-2 text-center">
                            {isFeas
                              ? <span className="inline-flex items-center gap-0.5 text-[11px] text-emerald-600 font-medium">✓ 可行</span>
                              : isInfeas
                              ? <span className="inline-flex items-center gap-0.5 text-[11px] text-rose-500 font-medium">✗ 不可行</span>
                              : <span className="text-[11px] text-slate-400">—</span>}
                          </td>
                          <td className="px-3 py-2 text-xs">
                            {c.monthly_load?.devices ? (
                              <span className="inline-flex flex-wrap items-center gap-1">
                                {c.monthly_load.devices.map(d => (
                                  <span key={d.device_id} className={`inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] ${
                                    d.is_overloaded ? 'bg-rose-50 text-rose-600' :
                                    d.monthly_util >= 80 ? 'bg-amber-50 text-amber-600' :
                                    'bg-emerald-50 text-emerald-600'
                                  }`}>
                                    {d.name}
                                    <span className="font-mono">{d.monthly_util.toFixed(1)}%</span>
                                    {d.is_overloaded && <span className="text-[8px]">超</span>}
                                  </span>
                                ))}
                                {canExpand && <span className="ml-1 text-slate-400 text-[10px]">{isExpOv ? '收起' : '查看负荷链 ›'}</span>}
                              </span>
                            ) : isFeas ? (
                              <span className="text-slate-500">
                                各装置正常
                                {canExpand && <span className="ml-1 text-slate-400 text-[10px]">{isExpOv ? '收起' : '查看负荷链 ›'}</span>}
                              </span>
                            ) : (
                              <span className="text-slate-300">—</span>
                            )}
                          </td>
                          <td className="px-3 py-2 text-xs text-slate-600">{c.switch_desc || c.description}</td>
                        </tr>
                        {/* 展开：可行=装置负荷计算链 / 不可行=超容计算链（均含数字孪生） */}
                        {isExpOv && canExpand && (
                          <tr className={isInfeas ? 'bg-rose-50/30' : 'bg-slate-50/40'}>
                            <td colSpan={7} className="px-6 py-4">
                              <MonthlyLoadChain detail={c.overload_details![0]} crudeName={crudeName} />
                            </td>
                          </tr>
                        )}
                      </FragmentRow>
                    )
                  })}
                </tbody>
              </table>
            </div>
            {/* 阈值调整说明：device_notes 汇总各装置 note，解释"安全库存为何被提高" */}
            {combos.length > 0 && combos[0].device_notes && Object.keys(combos[0].device_notes).length > 0 && (
              <div className="px-4 py-2.5 border-t border-slate-100 bg-amber-50/40">
                <div className="flex items-start gap-2">
                  <span className="text-[11px] text-amber-700 font-medium shrink-0 mt-0.5">⚙ 阈值已调整</span>
                  <div className="flex flex-wrap gap-x-3 gap-y-1">
                    {Object.entries(combos[0].device_notes).map(([name, note]) => (
                      <span key={name} className="text-[11px] text-amber-700">
                        <span className="text-slate-600">{name}</span>：<span className="font-medium">{note}</span>
                      </span>
                    ))}
                  </div>
                </div>
                <div className="text-[10px] text-slate-400 mt-1">
                  安全库存上限（safety_stock_thrd）经人工调高，对应装置有效能力上限提升——解释"为何阈值被提高后仍不超负荷"
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

// 月度负荷计算链：图文展示各装置月度负荷分布。
// 顶部全装置数字孪生流程图（节点标月度进料 + 连线月度流量），
// 下方超载装置展开进料来源拆解 + 逐装置月度负荷明细表。
function MonthlyLoadChain({ detail, crudeName }: {
  detail: OverloadDetail
  crudeName: (id: string) => string
}) {
  const mode = detail.mode
  const isX = mode === 'X_ZERO'
  const overloads = detail.devices.filter(d => d.is_overloaded)
  const multiOverload = overloads.length > 1
  const ok = detail.feasible !== false && overloads.length === 0
  const headColor = ok ? 'text-emerald-700' : 'text-rose-700'
  return (
    <div className="space-y-3">
      <div className={`flex items-center gap-1.5 text-[12px] font-semibold ${headColor}`}>
        {ok ? <Network className="w-4 h-4" /> : <AlertTriangle className="w-4 h-4" />}
        {ok ? '装置月度负荷链' : '月度超容计算链'} · {crudeName(detail.crude_type)}
        <span className={`ml-1 inline-block px-1.5 py-0.5 rounded text-[10px] font-medium ${isX ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'}`}>
          {MODE_CN[mode] || mode}
        </span>
        {ok ? (
          <span className="ml-1 inline-block px-1.5 py-0.5 rounded text-[10px] font-medium bg-emerald-100 text-emerald-700">
            月度全部正常
          </span>
        ) : multiOverload && (
          <span className="ml-1 inline-block px-1.5 py-0.5 rounded text-[10px] font-medium bg-rose-100 text-rose-700">
            {overloads.length} 个装置月度超载
          </span>
        )}
      </div>

      {/* 全装置数字孪生流程图：分层布局，节点标进料+连线流量 */}
      {detail.flow_diagram && detail.flow_diagram.nodes.length > 0 && (
        <FlowDiagramView diagram={detail.flow_diagram} />
      )}

      {/* 每个超载装置独立展开：结论横幅 + 进料来源拆解 */}
      {overloads.map((d, idx) => (
        <div key={d.device_id} className={`rounded-lg border ${multiOverload ? 'border-rose-200' : 'border-transparent'} ${multiOverload ? 'bg-rose-50/30' : ''} p-2.5 space-y-2`}>
          {multiOverload && (
            <div className="flex items-center gap-1.5 text-[11px] font-semibold text-rose-700">
              <span className="inline-flex items-center justify-center w-4 h-4 rounded-full bg-rose-500 text-white text-[9px]">{idx + 1}</span>
              {d.name}
              <span className="text-rose-500 font-mono">超载 {fNum(d.monthly_input - d.monthly_capacity)} 吨</span>
            </div>
          )}
          {/* 单装置超载结论横幅 */}
          <div className="flex items-center gap-2 rounded-lg border border-rose-200 bg-rose-50/60 px-3 py-2 text-[12px]">
            <AlertTriangle className="w-4 h-4 text-rose-500 shrink-0" />
            <span className="text-rose-700">
              <span className="font-bold">{d.name}</span> 月度进料
              <span className="font-mono font-bold text-rose-600"> {fNum(d.monthly_input)} </span>吨
              ＞ 月度能力
              <span className="font-mono font-bold text-rose-600"> {fNum(d.monthly_capacity)} </span>吨，
              超载 <span className="font-mono font-bold text-rose-600">{fNum(d.monthly_input - d.monthly_capacity)}</span> 吨 → 不可行
            </span>
            {d.note && (
              <span className="ml-auto inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 text-[10px] shrink-0">
                ⚙ {d.note}
              </span>
            )}
          </div>
          {/* 进料来源拆解：回答"该超载装置的进料从哪来"，暴露 X/Y 切换效果 */}
          {d.input_sources && d.input_sources.length > 0 && (
            <InputSourceBreakdown
              deviceName={d.name} input={d.monthly_input}
              sources={d.input_sources} mode={mode}
              jian1Rerouted={isX ? detail.jian1_to_wax : detail.jian1_to_diesel}
            />
          )}
        </div>
      ))}

      {/* 逐装置月度负荷明细表 */}
      <div className="overflow-x-auto rounded border border-slate-100">
        <table className="w-full text-[11px]">
          <thead>
            <tr className="bg-slate-50 text-slate-500">
              <th className="text-left font-medium px-2 py-1.5">装置</th>
              <th className="text-right font-medium px-2 py-1.5">月度进料(吨)</th>
              <th className="text-right font-medium px-2 py-1.5">月度能力(吨)</th>
              <th className="text-right font-medium px-2 py-1.5">月度负荷率</th>
              <th className="text-center font-medium px-2 py-1.5">状态</th>
            </tr>
          </thead>
          <tbody>
            {detail.devices.map(d => {
              const over = d.is_overloaded
              const util = d.monthly_util ?? 0
              return (
                <tr key={d.device_id} className={`border-t border-slate-100 ${over ? 'bg-rose-50/50' : ''}`}>
                  <td className="px-2 py-1.5 text-slate-700 font-medium">{d.name}</td>
                  <td className={`px-2 py-1.5 text-right font-mono ${over ? 'text-rose-600 font-bold' : 'text-slate-600'}`}>{fNum(d.monthly_input ?? 0)}</td>
                  <td className="px-2 py-1.5 text-right font-mono text-slate-600">{fNum(d.monthly_capacity ?? 0)}</td>
                  <td className={`px-2 py-1.5 text-right font-mono ${over ? 'text-rose-600 font-bold' : util >= 80 ? 'text-amber-600' : 'text-emerald-600'}`}>{util.toFixed(1)}%</td>
                  <td className="px-2 py-1.5 text-center">
                    {over
                      ? <span className="inline-block px-1.5 py-0.5 rounded text-[9px] text-white bg-rose-500">超载</span>
                      : <span className="inline-block px-1.5 py-0.5 rounded text-[9px] text-emerald-700 bg-emerald-50">✓ 正常</span>}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className="text-[10px] text-slate-400 leading-relaxed">
        月度能力 = 安全库存上限(safety_stock_thrd) × 负荷率% × 有效天数；月度负荷率 = 月度进料 / 月度能力 × 100%。
      </div>
    </div>
  )
}

// ── 全装置数字孪生流程图 ──────────────────────────────────────────────
// 仿真设备外形：常减压=蒸馏塔(start) / 储罐=立式罐(tank) / 加氢=反应器(normal)。
// 管道三层描边（管壁+物料+流动动画）连接设备端口；设备可鼠标/触摸拖拽重排，
// 管道随设备位置实时重算。超容设备红色辉光脉冲，重负荷(>500)管道红色加粗。
const EQ_SIZE: Record<string, { w: number; h: number }> = {
  start: { w: 70, h: 150 }, normal: { w: 80, h: 120 }, tank: { w: 90, h: 80 },
}
function FlowDiagramView({ diagram }: { diagram: FlowDiagram }) {
  const { nodes, edges } = diagram
  const W = 1320, H = 1280

  // 分层 & 拓扑分类（为按物理流程定位、就近排管服务）
  const { depthOf, feedersOf, isShared, col2Order } = useMemo(() => {
    const hasDown: Record<string, boolean> = {}
    edges.forEach(e => { hasDown[e.from_device_id] = true })
    const depthOf: Record<string, number> = {}
    nodes.forEach(n => {
      if (n.type === 'start') depthOf[n.device_id] = 0
      else if (n.type === 'normal') depthOf[n.device_id] = 2
      else depthOf[n.device_id] = hasDown[n.device_id] ? 1 : 3
    })
    nodes.forEach(n => { if (depthOf[n.device_id] === undefined) depthOf[n.device_id] = 3 })
    const col2 = nodes.filter(n => depthOf[n.device_id] === 2)
    const backFrom: Record<string, boolean> = {}
    edges.forEach(e => { if (depthOf[e.from_device_id] === 2 && depthOf[e.to_device_id] === 2) backFrom[e.from_device_id] = true })
    col2.sort((a, b) => (backFrom[a.device_id] ? 0 : 1) - (backFrom[b.device_id] ? 0 : 1))
    const col2Order: Record<string, number> = {}; col2.forEach((n, i) => col2Order[n.device_id] = i)
    // 每个成品罐的进料者集合，及是否被多装置同时喂
    const feedersOf: Record<string, string[]> = {}
    const isShared: Record<string, boolean> = {}
    nodes.filter(n => depthOf[n.device_id] === 3).forEach(n => {
      const fs = edges.filter(e => e.to_device_id === n.device_id).map(e => e.from_device_id)
      feedersOf[n.device_id] = Array.from(new Set(fs))
      isShared[n.device_id] = new Set(fs).size > 1
    })
    return { depthOf, feedersOf, isShared, col2Order }
  }, [nodes, edges])

  const nodeById = useMemo(() => {
    const m: Record<string, FlowNode> = {}; nodes.forEach(n => { m[n.device_id] = n }); return m
  }, [nodes])

  // 默认坐标：按实际物理流程拓扑摆放（坐标已离线校验全部在画布内）
  //   列0 常减压(左,居中)
  //   列1 常减压直系下游罐(全部并排) = 进料罐(工业燃料油/HC) + 直供成品罐(顶部综合/250#/减压渣油)
  //        —— 这些都是常减压的直接产物，物理上同一层，按产出顺序纵向排列
  //   列2 加氢装置 柴加(上,对齐工业燃料油罐) / 蜡加(下,对齐HC罐)
  //   列3 加氢产物罐(柴加独有→共享→蜡加独有，按产出顺序续接)
  const defaultPos = useMemo(() => {
    const p: Record<string, { x: number; y: number }> = {}
    const start = nodes.find(n => n.type === 'start')
    const units = nodes.filter(n => depthOf[n.device_id] === 2)
      .sort((a, b) => (col2Order[a.device_id] ?? 9) - (col2Order[b.device_id] ?? 9))
    const prods = nodes.filter(n => depthOf[n.device_id] === 3)
    const feeds = nodes.filter(n => depthOf[n.device_id] === 1)

    // 列0 常减压：左侧居中
    if (start) p[start.device_id] = { x: 40, y: (H - EQ_SIZE.start.h) / 2 }

    // 列1 常减压直系下游罐：进料罐在前(按下游装置顺序)，直供成品罐在后
    //   全部 x=220 并排，纵向等距排列，整体居中
    const directTanks = prods.filter(n => (feedersOf[n.device_id] || []).some(f => depthOf[f] === 0))
    const col1 = [...feeds, ...directTanks]
    const gap1 = 110
    const total1 = col1.length * EQ_SIZE.tank.h + (col1.length - 1) * gap1
    let y1 = (H - total1) / 2
    col1.forEach(n => { p[n.device_id] = { x: 220, y: y1 }; y1 += EQ_SIZE.tank.h + gap1 })

    // 列2 加氢装置：x=480，对齐到各自进料罐(柴加←工业燃料油罐，蜡加←HC罐)
    const unitX = 480
    units.forEach(n => {
      const feedId = edges.find(e => e.to_device_id === n.device_id)?.from_device_id
      const fy = feedId && p[feedId] ? p[feedId].y : (H - EQ_SIZE.normal.h) / 2
      p[n.device_id] = { x: unitX, y: fy + (EQ_SIZE.tank.h - EQ_SIZE.normal.h) / 2 }
    })

    // 列3 加氢产物罐：x=730，按 柴加独有→共享→蜡加独有 顺序纵向续接，起点对齐柴加
    const dieselOnly: FlowNode[] = [], shared: FlowNode[] = [], waxOnly: FlowNode[] = []
    prods.filter(n => !(feedersOf[n.device_id] || []).some(f => depthOf[f] === 0)).forEach(n => {
      const fs = feedersOf[n.device_id] || []
      if (isShared[n.device_id]) shared.push(n)
      else if (fs.some(f => col2Order[f] === 0)) dieselOnly.push(n)
      else waxOnly.push(n)
    })
    const cyTop = units[0] && p[units[0].device_id] ? p[units[0].device_id].y : 200
    let y3 = cyTop - 30
    ;[dieselOnly, shared, waxOnly].flat().forEach(n => { p[n.device_id] = { x: 730, y: y3 }; y3 += EQ_SIZE.tank.h + 55 })
    return p
  }, [nodes, edges, depthOf, feedersOf, isShared, col2Order])

  const [pos, setPos] = useState<Record<string, { x: number; y: number }>>(defaultPos)
  useEffect(() => { setPos(defaultPos) }, [defaultPos])
  // 拖拽用 ref 持有当前手势状态，避免 React state 异步更新导致首批 pointermove 丢失
  const dragRef = useRef<null | { id: string; sx: number; sy: number; ox: number; oy: number }>(null)
  const [dragId, setDragId] = useState<string | null>(null)

  // 设备端口坐标（相对画布）：frac 为该侧 0..1 的归一化位置（多条出/入管按比例分布）
  const port = (id: string, side: 'right' | 'left' | 'top', frac: number) => {
    const n = nodeById[id], p = pos[id]; if (!n || !p) return { x: 0, y: 0 }
    const s = EQ_SIZE[n.type] ?? EQ_SIZE.tank
    const f = Math.max(0.12, Math.min(0.92, frac))
    if (side === 'right') return { x: p.x + s.w, y: p.y + s.h * f }
    if (side === 'left') return { x: p.x, y: p.y + s.h * f }
    return { x: p.x + s.w * f, y: p.y }
  }

  // 管道几何：按物理流程拓扑选路由——回流(柴加→蜡加)走右侧母线 U 形；
  // 直供(常减压→成品罐)走顶部母线；其余走源右沿→目标左沿的贝塞尔。
  // 同源同目的的平行管在源/目标侧按比例错开端口，避免重合交叉。
  const edgeGeo = useMemo(() => {
    // 按源分组：同源的多条出向管在源右沿按比例分布端口
    const outOf: Record<string, { i: number; to: string }[]> = {}
    edges.forEach((e, i) => { (outOf[e.from_device_id] = outOf[e.from_device_id] || []).push({ i, to: e.to_device_id }) })
    // 按目标分组：同目标的多条入向管在目标左沿按比例分布端口
    const inTo: Record<string, number[]> = {}
    edges.forEach((e, i) => { (inTo[e.to_device_id] = inTo[e.to_device_id] || []).push(i) })

    return edges.map((e, i) => {
      const p = pos[e.from_device_id], q = pos[e.to_device_id]
      if (!p || !q) return null
      const fromL = depthOf[e.from_device_id], toL = depthOf[e.to_device_id]
      const back = fromL === 2 && toL === 2            // 柴加→蜡加 含硫副产回流
      // 源端口：在该源的所有出向管中按序取归一化位置
      const srcOuts = outOf[e.from_device_id]
      const sIdx = srcOuts.findIndex(o => o.i === i)
      const sFrac = srcOuts.length > 1 ? (sIdx + 1) / (srcOuts.length + 1) : 0.55
      // 目标端口：在该目标的所有入向管中按序取归一化位置
      const dstIns = inTo[e.to_device_id]
      const dIdx = dstIns.indexOf(i)
      const dFrac = dstIns.length > 1 ? (dIdx + 1) / (dstIns.length + 1) : 0.5

      let path: string, lx: number, ly: number, ax: number, ay: number, ang: number
      if (back) {
        // 回流(柴加→蜡加)：右侧母线 U 形
        const s = port(e.from_device_id, 'right', sFrac), t = port(e.to_device_id, 'right', dFrac)
        const gap = 46
        path = `M ${s.x} ${s.y} L ${s.x + gap} ${s.y} L ${s.x + gap} ${t.y} L ${t.x} ${t.y}`
        lx = s.x + gap + 4; ly = (s.y + t.y) / 2; ax = t.x; ay = t.y; ang = 180
      } else {
        // 其余全部走源右沿→目标左沿的贝塞尔（含常减压直供罐：它们已并排在右侧，直接连，不绕顶部）
        const s = port(e.from_device_id, 'right', sFrac), t = port(e.to_device_id, 'left', dFrac)
        const mid = (s.x + t.x) / 2
        path = `M ${s.x} ${s.y} C ${mid} ${s.y} ${mid} ${t.y} ${t.x} ${t.y}`
        lx = mid; ly = (s.y + t.y) / 2; ax = t.x; ay = t.y; ang = 0
      }
      return { edge: e, path, lx, ly, ax, ay, ang }
    }).filter(Boolean) as { edge: FlowEdge; path: string; lx: number; ly: number; ax: number; ay: number; ang: number }[]
  }, [edges, pos, depthOf, nodeById])

  // 拖拽：pointer capture，按屏幕位移增量更新（SVG 1:1 像素）
  const onDown = (e: RPointerEvent, id: string) => {
    e.stopPropagation()
    ;(e.currentTarget as Element).setPointerCapture(e.pointerId)
    dragRef.current = { id, sx: e.clientX, sy: e.clientY, ox: pos[id].x, oy: pos[id].y }
    setDragId(id)
  }
  const onMove = (e: RPointerEvent) => {
    const d = dragRef.current
    if (!d) return
    const nx = Math.max(0, Math.min(W - 60, d.ox + (e.clientX - d.sx)))
    const ny = Math.max(0, Math.min(H - 40, d.oy + (e.clientY - d.sy)))
    setPos(p => ({ ...p, [d.id]: { x: nx, y: ny } }))
  }
  const onUp = (e: RPointerEvent) => {
    if (dragRef.current) try { (e.currentTarget as Element).releasePointerCapture(e.pointerId) } catch { /* noop */ }
    dragRef.current = null
    setDragId(null)
  }

  return (
    <div className="rounded-lg border border-slate-200 bg-slate-50/30 p-3">
      <div className="flex items-center justify-between gap-2 mb-2">
        <div className="flex items-center gap-1.5 text-[12px] font-semibold text-slate-700">
          <Network className="w-4 h-4 text-slate-400" />
          全装置数字孪生流程图
          <span className="text-[10px] font-normal text-slate-400">（{nodes.length} 设备 · {edges.length} 管线 · 可拖拽设备重排）</span>
        </div>
        <button onClick={() => setPos(defaultPos)}
          className="text-[10px] px-2 py-0.5 rounded border border-slate-300 bg-white text-slate-600 hover:bg-slate-100">
          重置布局
        </button>
      </div>
      <div className="flex items-center gap-3 flex-wrap text-[10px] text-slate-500 mb-2">
        <span className="inline-flex items-center gap-1"><span className="w-3 h-3 rounded bg-rose-400" />超容</span>
        <span className="inline-flex items-center gap-1"><span className="w-3 h-3 rounded bg-emerald-300" />正常</span>
        <span className="inline-flex items-center gap-1"><span className="w-3 h-3 rounded bg-slate-300" />储罐</span>
        <span className="inline-flex items-center gap-1"><span className="w-4 border-t-2 border-sky-400 inline-block" />正常物流</span>
        <span className="inline-flex items-center gap-1"><span className="w-4 border-t-2 border-rose-500 inline-block" />重负荷</span>
        <span className="inline-flex items-center gap-1"><span className="w-4 border-t-2 border-dashed border-slate-300 inline-block" />阀门切走</span>
      </div>

      <div className="overflow-auto rounded-md border border-slate-200" style={{ background: 'linear-gradient(180deg,#f1f5f9,#e2e8f0)' }}>
        <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} style={{ touchAction: 'none', display: 'block' }}>
          <defs>
            <linearGradient id="cylG" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0" stopColor="#475569" /><stop offset="0.5" stopColor="#cbd5e1" /><stop offset="1" stopColor="#475569" />
            </linearGradient>
            <linearGradient id="roofG" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="#94a3b8" /><stop offset="1" stopColor="#64748b" />
            </linearGradient>
            <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
              <path d="M40 0 L0 0 0 40" fill="none" stroke="#cbd5e1" strokeWidth="0.5" opacity="0.5" />
            </pattern>
            <filter id="redGlow" x="-50%" y="-50%" width="200%" height="200%">
              <feGaussianBlur stdDeviation="3" result="b" />
              <feFlood floodColor="#ef4444" result="c" />
              <feComposite in="c" in2="b" operator="in" result="g" />
              <feMerge><feMergeNode in="g" /><feMergeNode in="SourceGraphic" /></feMerge>
            </filter>
          </defs>
          <style>{`@keyframes pflow{to{stroke-dashoffset:-32}}.pipe-flow{animation:pflow 1s linear infinite}@keyframes gpulse{0%,100%{opacity:.35}50%{opacity:.9}}.over-glow{animation:gpulse 1.4s ease-in-out infinite}`}</style>

          <rect x="0" y="0" width={W} height={H} fill="url(#grid)" />

          {/* 管道层：管壁+物料+流动动画+箭头（细管线，避免视觉拥堵） */}
          <g style={{ pointerEvents: 'none' }}>
            {edgeGeo.map(({ edge, path, ax, ay, ang }) => {
              const off = edge.is_switched_off
              const heavy = edge.flow > 500
              const fluid = off ? '#94a3b8' : heavy ? '#f43f5e' : '#0ea5e9'
              return (
                <g key={edge.conn_id}>
                  <path d={path} fill="none" stroke="#475569" strokeWidth={off ? 2.5 : 5}
                    strokeLinecap="round" strokeLinejoin="round"
                    strokeDasharray={off ? '5 6' : undefined} opacity={off ? 0.5 : 1} />
                  {!off && <>
                    <path d={path} fill="none" stroke={fluid} strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" />
                    <path d={path} fill="none" stroke={heavy ? '#fecdd3' : '#e0f2fe'} strokeWidth={3}
                      strokeLinecap="round" strokeDasharray="2 18" className="pipe-flow" opacity={0.8} />
                  </>}
                  <polygon points="-7,-3.5 0,0 -7,3.5" fill={fluid} transform={`translate(${ax} ${ay}) rotate(${ang})`} />
                </g>
              )
            })}
          </g>

          {/* 流量标签：物料全名 + 阀位 + 实际吨数（大号可读） */}
          <g style={{ pointerEvents: 'none' }}>
            {edgeGeo.map(({ edge, lx, ly }) => {
              const off = edge.is_switched_off
              const heavy = edge.flow > 500
              const name = edge.product_name.length > 6 ? edge.product_name.slice(0, 6) : edge.product_name
              const txt = `${name} ${fNum(edge.flow)}`
              const tw = txt.length * 7.5 + 14
              return (
                <g key={edge.conn_id} transform={`translate(${lx} ${ly})`}>
                  <rect x={-tw / 2} y={-11} width={tw} height={22} rx={4} fill="#ffffff" stroke="#94a3b8" strokeWidth={0.8} opacity={0.95} />
                  <text x={0} y={5} textAnchor="middle" fontSize={13} fontWeight={600}
                    fill={off ? '#94a3b8' : heavy ? '#e11d48' : '#0369a1'}>{txt}</text>
                </g>
              )
            })}
          </g>

          {/* 设备层（可拖拽） */}
          {nodes.map(n => {
            const p = pos[n.device_id]; if (!p) return null
            const over = n.is_overloaded === true
            const util = n.monthly_util ?? 0
            const s = EQ_SIZE[n.type] ?? EQ_SIZE.tank
            return (
              <g key={n.device_id} transform={`translate(${p.x} ${p.y})`}
                onPointerDown={e => onDown(e, n.device_id)} onPointerMove={onMove} onPointerUp={onUp}
                style={{ cursor: dragId === n.device_id ? 'grabbing' : 'grab', touchAction: 'none' }}>
                {/* 透明命中层：保证整个设备包围盒均可拾取拖拽，不受子图形缝隙/滤镜影响 */}
                <rect x={0} y={0} width={s.w} height={s.h} fill="transparent" pointerEvents="all" />
                {over && <rect x={-4} y={-4} width={s.w + 8} height={s.h + 8} rx={8} fill="none" stroke="#ef4444" strokeWidth={2} className="over-glow" />}
                <ellipse cx={s.w / 2} cy={s.h + 4} rx={s.w * 0.42} ry={4} fill="#000" opacity={0.12} />

                {n.type === 'start' && (
                  // 蒸馏塔（常减压）
                  <g>
                    <rect x="20" y={s.h - 10} width="30" height="8" fill="#475569" />
                    <rect x="15" y="20" width="40" height={s.h - 30} rx="4" fill="url(#cylG)" stroke="#1e293b" strokeWidth="1.5" />
                    <path d="M15 24 Q35 2 55 24 Z" fill="url(#roofG)" stroke="#1e293b" strokeWidth="1.5" />
                    {[45, 65, 85, 105, 125].map(y => <line key={y} x1="17" y1={y} x2="53" y2={y} stroke="#334155" strokeWidth="0.7" />)}
                    <rect x="3" y="80" width="13" height="6" fill="#475569" />
                    <rect x="29" y="0" width="12" height="6" fill="#475569" />
                  </g>
                )}
                {n.type === 'normal' && (
                  // 反应器（加氢）
                  <g>
                    <rect x="25" y={s.h - 6} width="30" height="6" fill="#475569" />
                    <rect x="12" y="15" width="56" height={s.h - 21} rx="6" fill="url(#cylG)" stroke="#1e293b" strokeWidth="1.5" />
                    <path d="M12 20 Q40 0 68 20 Z" fill="url(#roofG)" stroke="#1e293b" strokeWidth="1.5" />
                    <line x1="14" y1="50" x2="66" y2="50" stroke="#334155" strokeWidth="0.8" strokeDasharray="3 2" />
                    <line x1="14" y1="82" x2="66" y2="82" stroke="#334155" strokeWidth="0.8" strokeDasharray="3 2" />
                    <rect x="0" y="58" width="13" height="6" fill="#475569" />
                    <rect x="34" y="0" width="12" height="6" fill="#475569" />
                  </g>
                )}
                {n.type === 'tank' && (
                  // 立式储罐
                  <g>
                    <path d={`M8 24 L${s.w / 2} 4 L${s.w - 8} 24 Z`} fill="url(#roofG)" stroke="#1e293b" strokeWidth="1.2" />
                    <rect x="8" y="24" width={s.w - 16} height={s.h - 30} rx="3" fill="url(#cylG)" stroke="#1e293b" strokeWidth="1.5" />
                    <line x1="8" y1="30" x2={s.w - 8} y2="30" stroke="#334155" strokeWidth="0.8" />
                    <rect x="0" y={s.h * 0.5} width="10" height="6" fill="#475569" />
                  </g>
                )}

                {/* 设备名 */}
                <text x={s.w / 2} y={s.h + 18} textAnchor="middle" fontSize={14} fontWeight={700} fill={over ? '#be123c' : '#334155'}>{n.name}</text>
                {/* 进料指标 */}
                <text x={s.w / 2} y={s.h + 35} textAnchor="middle" fontSize={11} fontFamily="monospace" fill="#64748b">
                  进 {fNum(n.input)} 吨
                </text>
                {/* 月度负荷率（加工装置） */}
                {n.type !== 'tank' && n.monthly_util != null && (
                  <>
                    <text x={s.w / 2} y={s.h + 50} textAnchor="middle" fontSize={11} fontWeight={600} fontFamily="monospace"
                      fill={over ? '#ef4444' : util >= 80 ? '#f59e0b' : '#10b981'}>
                      负荷 {util.toFixed(1)}%
                    </text>
                    {/* 进度条 */}
                    <rect x={s.w / 2 - 30} y={s.h + 54} width={60} height={5} rx={2} fill="#e2e8f0" />
                    <rect x={s.w / 2 - 30} y={s.h + 54} width={60 * Math.min(util, 100) / 100} height={5} rx={2}
                      fill={over ? '#ef4444' : util > 80 ? '#f59e0b' : '#10b981'} />
                    {over && <text x={s.w / 2} y={s.h + 71} textAnchor="middle" fontSize={10} fontWeight={600} fill="#ef4444">超载</text>}
                  </>
                )}
              </g>
            )
          })}
        </svg>
      </div>
      <div className="text-[10px] text-slate-400 mt-1.5">提示：按住任意设备可拖动到任意位置，管道会跟随重排；红色脉冲为月度超载装置。</div>
    </div>
  )
}

// 进料来源拆解：回答"超容装置的进料从哪来"，暴露 X/Y 阀门切换效果
function InputSourceBreakdown({ deviceName, input, sources, mode, jian1Rerouted }: {
  deviceName: string; input: number; sources: InputSource[]; mode: string; jian1Rerouted: number
}) {
  // 切走的减一线（Y_ZERO 时蜡加侧 conn_5=0；X_ZERO 时柴加侧 conn_4=0）
  const switchedOff = sources.filter(s => s.is_switched_off)
  const active = sources.filter(s => !s.is_switched_off)
  const activeSum = active.reduce((a, s) => a + s.flow, 0)
  return (
    <div className="rounded-lg border border-slate-200 bg-slate-50/40 px-3 py-2.5">
      <div className="text-[12px] font-semibold text-slate-700 mb-2 flex items-center gap-1.5">
        <GitBranch className="w-3.5 h-3.5 text-slate-400" />
        {deviceName} 进料来源拆解
        <span className="text-[10px] font-normal text-slate-400">（合计 {fNum(input)} 吨）</span>
      </div>

      <div className="space-y-2">
        {/* 切走的支路（=0）—— 这正是"减一线已改道"的直观证据 */}
        {switchedOff.map(s => (
          <div key={s.conn_id} className="rounded border border-dashed border-slate-300 bg-slate-50 px-2.5 py-1.5">
            <div className="flex items-center gap-2 text-[11px]">
              <span className="text-slate-400 line-through">{s.from_product_name}</span>
              <span className="text-[9px] text-slate-400">({s.yield_rate}%)</span>
              {s.special_var_note && (
                <span className="inline-block px-1 py-0.5 rounded text-[9px] bg-slate-200 text-slate-500">{s.special_var_note}</span>
              )}
              <span className="ml-auto font-mono text-slate-400">0 吨</span>
              <span className="inline-block px-1.5 py-0.5 rounded text-[9px] bg-slate-200 text-slate-500">阀门切走</span>
            </div>
            {/* 罐下钻：展开减一线经 HC罐 这条线，标明同罐还有减二三四线在喂 */}
            {s.sub_sources.length > 0 && (
              <div className="mt-1 ml-3 pl-2 border-l border-slate-200 space-y-0.5">
                {s.sub_sources.map(sub => (
                  <div key={sub.conn_id} className="flex items-center gap-2 text-[10px]">
                    <span className={sub.is_switched_off ? 'text-slate-400 line-through' : 'text-slate-600'}>{sub.from_product_name}</span>
                    <span className="text-[9px] text-slate-400">({sub.yield_rate}%)</span>
                    {sub.special_var_note && (
                      <span className="inline-block px-1 py-0.5 rounded text-[9px] bg-slate-200 text-slate-500">{sub.special_var_note}</span>
                    )}
                    <span className={`ml-auto font-mono ${sub.is_switched_off ? 'text-slate-400' : 'text-slate-600'}`}>{fNum(sub.flow)} 吨</span>
                    {sub.is_switched_off && <span className="text-[9px] text-slate-400">已切走</span>}
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}

        {/* 实际进料的支路 */}
        {active.map(s => (
          <div key={s.conn_id} className="rounded border border-slate-200 bg-white px-2.5 py-1.5">
            <div className="flex items-center gap-2 text-[11px]">
              <span className="text-slate-700 font-medium">{s.from_product_name}</span>
              <span className="text-[9px] text-slate-400">({s.yield_rate}%)</span>
              {s.special_var_note && (
                <span className="inline-block px-1 py-0.5 rounded text-[9px] bg-blue-50 text-blue-600">{s.special_var_note}</span>
              )}
              <span className="text-[9px] text-slate-400">← {s.from_device_name}</span>
              <span className="ml-auto font-mono text-slate-700 font-semibold">{fNum(s.flow)} 吨</span>
            </div>
            {/* 罐下钻：HC罐收到的减一线/减二三四线逐条展开 */}
            {s.sub_sources.length > 0 && (
              <div className="mt-1 ml-3 pl-2 border-l border-slate-200 space-y-0.5">
                <div className="text-[9px] text-slate-400 mb-0.5">{s.from_device_name} 的进料（下钻）：</div>
                {s.sub_sources.map(sub => (
                  <div key={sub.conn_id} className="flex items-center gap-2 text-[10px]">
                    <span className={sub.is_switched_off ? 'text-slate-400 line-through' : 'text-slate-600'}>{sub.from_product_name}</span>
                    <span className="text-[9px] text-slate-400">({sub.yield_rate}%)</span>
                    {sub.special_var_note && (
                      <span className={`inline-block px-1 py-0.5 rounded text-[9px] ${sub.is_switched_off ? 'bg-slate-200 text-slate-500' : 'bg-blue-50 text-blue-600'}`}>{sub.special_var_note}</span>
                    )}
                    <span className={`ml-auto font-mono ${sub.is_switched_off ? 'text-slate-400' : 'text-slate-600'}`}>{fNum(sub.flow)} 吨</span>
                    {sub.is_switched_off && <span className="text-[9px] text-slate-400">已切走</span>}
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>

      {/* 结论说明：按"装置类型 + mode"生成，避免对单一装置硬编码 */}
      <div className="mt-2 rounded bg-amber-50 border border-amber-200 px-2.5 py-1.5 text-[10px] text-amber-800 leading-relaxed">
        <ConclusionText mode={mode} deviceName={deviceName} input={input}
          activeSum={activeSum}
          jian1Rerouted={jian1Rerouted} sources={sources} />
      </div>
    </div>
  )
}

// 结论文案：按超容装置实际进料来源生成，不写死"蜡加"
function ConclusionText({ mode, deviceName, input, activeSum, jian1Rerouted, sources }: {
  mode: string; deviceName: string; input: number; activeSum: number
  jian1Rerouted: number; sources: InputSource[]
}) {
  const isX = mode === 'X_ZERO'
  // 判断装置类型：看进料是否经 HC罐(蜡加) / 工业燃料油罐(柴加)
  const hasHcTank = sources.some(s => (s.from_device_name || '').includes('HC'))
  const hasGyrlyTank = sources.some(s => (s.from_device_name || '').includes('工业燃料油'))
  if (hasHcTank) {
    // 蜡加：进料 = HC罐(减一线Y + 减二三四线) + 柴加含硫副产回流
    if (isX) {
      return <>X_ZERO：减一线(Y→蜡加侧) = <b className="font-mono">{fNum(jian1Rerouted)}</b> 吨全进蜡加，{deviceName} 进料 <b className="font-mono">{fNum(input)}</b> = 减一线 + 减二三四线经HC罐 {fNum(activeSum)} 吨 + 柴加含硫副产回流，仍超上限。</>
    }
    return <>Y_ZERO：减一线 = <b className="font-mono">{fNum(jian1Rerouted)}</b> 吨已全切去柴加，{deviceName} 的减一线来料 = <b className="font-mono">0</b> 吨；进料 <b className="font-mono">{fNum(input)}</b> 来自减二三四线经HC罐 {fNum(activeSum)} 吨 + 柴加含硫副产回流——减一线改道并未卸掉蜡加负荷，仍超上限。</>
  }
  if (hasGyrlyTank) {
    // 柴加：进料 = 工业燃料油罐(常线 + 减一线X)
    if (isX) {
      return <>X_ZERO：减一线 = <b className="font-mono">{fNum(jian1Rerouted)}</b> 吨已全切去蜡加，{deviceName} 的减一线来料 = <b className="font-mono">0</b> 吨；进料 <b className="font-mono">{fNum(input)}</b> 仅来自常线经工业燃料油罐 {fNum(activeSum)} 吨——减一线改道卸掉了柴加负荷，但本装置仍超上限。</>
    }
    return <>Y_ZERO：减一线(X→柴加侧) = <b className="font-mono">{fNum(jian1Rerouted)}</b> 吨全进柴加，{deviceName} 进料 <b className="font-mono">{fNum(input)}</b> = 常线 + 减一线经工业燃料油罐 {fNum(activeSum)} 吨，仍超上限。</>
  }
  // 通用兜底
  return <>{mode}：{deviceName} 进料 <b className="font-mono">{fNum(input)}</b> 吨，实际进料来源合计 {fNum(activeSum)} 吨，超上限。</>
}

function FragmentRow({ children }: { children: React.ReactNode }) {
  return <>{children}</>
}
