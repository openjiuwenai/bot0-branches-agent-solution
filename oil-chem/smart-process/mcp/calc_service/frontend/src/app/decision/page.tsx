'use client'

import { useState, useEffect } from 'react'
import {
  Play, Loader2, ClipboardCheck, AlertTriangle,
} from 'lucide-react'
import {
  SolveResp, normalizeMonth, defaultCrudeName,
} from '@/components/SolveResult'
import { BusinessResult } from '@/components/BusinessResult'
import ShutdownPanel, { ShutdownItem } from '@/components/ShutdownPanel'
import HangmeiPanel, { HangmeiInput } from '@/components/HangmeiPanel'

type CrudeMap = Record<string, { id: string; name: string; cost: number }>

const FALLBACK_MONTHS = [
  { key: '2026-01', label: '2026年1月' },
  { key: '2026-02', label: '2026年2月' },
  { key: '2026-03', label: '2026年3月' },
]

// 效益决策台 —— 面向生产技术部生产规划人员的单方案决策视图。
// 复用 comprehensive_solve 接口（同排产/预测页），但以业务语言呈现：
// 决策结论 → 效益拆解 → 排产执行 → 航煤工况 → 假设说明。
export default function DecisionPage() {
  const [month, setMonth] = useState('2026-01')
  const [months, setMonths] = useState(FALLBACK_MONTHS)
  const [crudeMap, setCrudeMap] = useState<CrudeMap>({})
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<SolveResp | null>(null)
  const [elapsedMs, setElapsedMs] = useState(0)
  const [serviceDown, setServiceDown] = useState(false)
  const [shutdown, setShutdown] = useState<ShutdownItem[]>([])
  const [hangmei, setHangmei] = useState<HangmeiInput>({ enabled: false, target: 5000 })

  // ── 挂载时拉月份列表 + 原油映射（同排产/预测页）──
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
        const list = plans
          .map(p => normalizeMonth(p.plan_month || p.plan_id))
          .filter((m): m is { key: string; label: string } => m !== null)
          .sort((a, b) => a.key.localeCompare(b.key))
        if (list.length) {
          setMonths(list)
          if (!list.some(m => m.key === month)) setMonth(list[0].key)
        }
      })
      .catch(() => { if (cancelled) setServiceDown(true) })
    return () => { cancelled = true }
  }, [])

  // ── 月份变化时自动读取航煤目标产量（plan_product 3号喷气燃料 内贸+出口）──
  useEffect(() => {
    if (!month) return
    let cancelled = false
    fetch(`/api/scheduling/hangmei_target?month=${month}`, { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(d => {
        if (cancelled || !d?.success) return
        if (d.target_tons != null && d.target_tons > 0)
          setHangmei({ enabled: true, target: d.target_tons })
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [month])

  // ── 生成决策方案：综合求解（排产 + 阀门切换 + 效益评估）──
  async function solve() {
    setLoading(true); setError(null); setResult(null)
    const startedAt = Date.now()
    try {
      const r = await fetch('/api/scheduling/comprehensive_solve', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          plan_month: month,
          production_plans_input: [],
          monthly_crude_input: null,
          blend_mode: false,
          save_data: false,
          hangmei_target: hangmei.enabled ? hangmei.target : null,
          shutdown_config: shutdown,
        }),
      })
      const body = await r.json().catch(() => ({}))
      if (!r.ok) throw new Error(body?.message || body?.detail || `HTTP ${r.status}`)
      setResult(body as SolveResp)
    } catch (e) {
      setError(e instanceof Error ? e.message : '求解失败，请确认 solve_v1 服务已启动（:5081）')
    } finally {
      setElapsedMs(Date.now() - startedAt)
      setLoading(false)
    }
  }

  const crudeName = (id: string) => defaultCrudeName(id, crudeMap)

  return (
    <div className="space-y-5 animate-fade-in-up">
      {/* 页头：标题 + 月份 + 生成按钮（与排产/预测页统一布局） */}
      <div className="flex items-end justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900 flex items-center gap-2">
            <ClipboardCheck className="w-5 h-5 text-emerald-600" />效益决策台
          </h1>
          <p className="text-sm text-slate-500 mt-0.5">
            选取月份 → 生成最优排产决策方案（含效益结论、损益拆解与执行计划）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-500">月份</span>
          <select value={month} onChange={e => setMonth(e.target.value)} disabled={loading}
            className="h-9 rounded-md border border-slate-200 bg-white px-3 text-sm text-slate-700 disabled:bg-slate-50">
            {months.map(m => <option key={m.key} value={m.key}>{m.label}</option>)}
          </select>
          <button onClick={solve} disabled={loading || serviceDown}
            className="inline-flex items-center h-9 px-6 rounded-md bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-medium disabled:opacity-50">
            {loading ? <><Loader2 className="w-4 h-4 mr-1.5 animate-spin" />生成中…</> : <><Play className="w-4 h-4 mr-1.5" />生成决策方案</>}
          </button>
        </div>
      </div>

      {/* 服务未启动提示 */}
      {serviceDown && (
        <div className="p-4 rounded-xl border border-red-300 bg-red-50/60">
          <div className="flex items-center gap-2 text-sm text-red-700">
            <AlertTriangle className="w-4 h-4" />
            <span className="font-bold">solve_v1 服务未启动</span>
            <span className="text-red-600">— 请先运行 <code className="bg-white/70 px-1.5 py-0.5 rounded border border-red-200 font-mono text-xs">python -m solve_v1.app</code>（端口 5081）</span>
          </div>
        </div>
      )}

      {/* 工况假设：装置停工 / 航煤工况声明 */}
      <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm space-y-3">
        <ShutdownPanel value={shutdown} onChange={setShutdown} disabled={loading} month={month} />
        <HangmeiPanel value={hangmei} onChange={setHangmei} disabled={loading} />
      </div>

      {loading && (
        <div className="flex items-center justify-center py-16 text-slate-400">
          <Loader2 className="w-5 h-5 animate-spin mr-2" />正在生成决策方案…
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

      {/* 决策方案结果 */}
      {result && !loading && (
        <BusinessResult result={result} elapsedMs={elapsedMs} crudeName={crudeName} />
      )}
    </div>
  )
}
