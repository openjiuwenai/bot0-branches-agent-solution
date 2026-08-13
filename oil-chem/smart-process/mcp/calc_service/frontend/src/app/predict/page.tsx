'use client'

import { useState, useEffect } from 'react'
import {
  Play, Loader2, TrendingUp, AlertTriangle,
} from 'lucide-react'
import {
  SolveResult, SolveResp, normalizeMonth, defaultCrudeName,
  DeviceProfitSummary,
} from '@/components/SolveResult'
import ShutdownPanel, { ShutdownItem } from '@/components/ShutdownPanel'
import HangmeiPanel, { HangmeiInput } from '@/components/HangmeiPanel'

type CrudeMap = Record<string, { id: string; name: string; cost: number }>

const FALLBACK_MONTHS = [
  { key: '2026-01', label: '2026年1月' },
  { key: '2026-02', label: '2026年2月' },
  { key: '2026-03', label: '2026年3月' },
]

export default function PredictPage() {
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
  const [planSource, setPlanSource] = useState<'lp' | 'cp_sat'>('lp')

  // ── 挂载时拉月份列表 + 原油映射（同排产/批次页）──
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
      .catch(() => { if (!cancelled) setServiceDown(true) })
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

  // ── 单月综合求解：LP 排产 → 阀门切换组合枚举 → 效益评估选优 ──
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
          plan_source: planSource,
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
      {/* 标题 + 月份 + 求解按钮（页头与排产/批次页统一：标题左 / 控件右）*/}
      <div className="flex items-end justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900 flex items-center gap-2">
            <TrendingUp className="w-5 h-5 text-blue-600" />效益预测
          </h1>
          <p className="text-sm text-slate-500 mt-0.5">
            选取月份 → 综合求解（排产 + 阀门切换 + 效益评估）→ 输出最优组合效益明细
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-500">月份</span>
          <select value={month} onChange={e => setMonth(e.target.value)} disabled={loading}
            className="h-9 rounded-md border border-slate-200 bg-white px-3 text-sm text-slate-700 disabled:bg-slate-50">
            {months.map(m => <option key={m.key} value={m.key}>{m.label}</option>)}
          </select>
          {/* 排产数据源切换：LP 实际排产 vs CP-SAT 排产 */}
          <div className="flex items-center rounded-md border border-slate-200 bg-white overflow-hidden">
            <button onClick={() => setPlanSource('lp')} disabled={loading}
              className={`h-9 px-3 text-xs font-medium transition-colors ${
                planSource === 'lp' ? 'bg-blue-600 text-white' : 'text-slate-500 hover:bg-slate-50'
              }`}>
              实际排产
            </button>
            <button onClick={() => setPlanSource('cp_sat')} disabled={loading}
              className={`h-9 px-3 text-xs font-medium transition-colors border-l border-slate-200 ${
                planSource === 'cp_sat' ? 'bg-purple-600 text-white' : 'text-slate-500 hover:bg-slate-50'
              }`}>
              CP-SAT 排产
            </button>
          </div>
          <button onClick={solve} disabled={loading || serviceDown}
            className="inline-flex items-center h-9 px-6 rounded-md bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium disabled:opacity-50">
            {loading ? <><Loader2 className="w-4 h-4 mr-1.5 animate-spin" />求解中…</> : <><Play className="w-4 h-4 mr-1.5" />效益求解</>}
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

      {/* 装置停工 / 航煤工况声明（与批次页停工卡同款的独立卡片） */}
      <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm space-y-3">
        <ShutdownPanel value={shutdown} onChange={setShutdown} disabled={loading} month={month} />
        <HangmeiPanel value={hangmei} onChange={setHangmei} disabled={loading} />
      </div>

      {error && (
        <div className="p-4 rounded-xl border border-red-200 bg-red-50/40">
          <div className="flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 text-red-500 mt-0.5 shrink-0" />
            <div className="text-xs text-red-700 font-mono whitespace-pre-wrap break-all">{error}</div>
          </div>
        </div>
      )}

      {/* 单月求解结果（求解失败时显示提示卡；无可行方案时 SolveResult 内部展示理论收益对比） */}
      {result && (
        result.success
          ? <>
              <SolveResult result={result} elapsedMs={elapsedMs} crudeName={crudeName} />
              <DeviceProfitSummary breakdown={result.economic_breakdown} />
            </>
          : (
            <div className="p-5 rounded-xl border border-amber-200 bg-amber-50/40">
              <div className="flex items-center gap-2 text-amber-800">
                <AlertTriangle className="w-5 h-5" />
                <span className="font-semibold">求解失败</span>
              </div>
              <p className="text-sm text-amber-700 mt-2">{result.message}</p>
              {/* 停工冲突明细（后端 shutdown.conflicts 透传，逐日列出冲突原因） */}
              {result.shutdown?.conflicts?.length ? (
                <div className="mt-3">
                  <div className="text-xs font-medium text-amber-800 mb-1.5">停工冲突明细</div>
                  <div className="flex flex-wrap gap-1.5">
                    {result.shutdown.conflicts.map((c, i) => (
                      <span key={i} className="inline-flex items-center gap-1 px-2 py-1 rounded-md bg-white/70 border border-amber-200 text-[11px] text-amber-700">
                        <span className="font-mono font-semibold">第{c.day}天</span>
                        <span className="text-amber-500">·</span>
                        <span>{c.reason}</span>
                      </span>
                    ))}
                  </div>
                  <p className="text-[11px] text-amber-600 mt-2">
                    柴加与蜡加同日停工时减一线无去向，请错开两装置的停工日期段。
                  </p>
                </div>
              ) : null}
            </div>
          )
      )}
    </div>
  )
}
