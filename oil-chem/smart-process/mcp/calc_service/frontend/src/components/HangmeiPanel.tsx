'use client'

import { Plane, PlaneTakeoff } from 'lucide-react'

// 航煤工况输入：全局开关 + 目标产出吨数（一个值作用于所有勾选月）
export type HangmeiInput = { enabled: boolean; target: number }

/**
 * 航煤工况面板：开关 + 目标航煤产出吨数。
 *
 * 航煤工况仅对柴加 cyjq_01 生效：航煤期(day_index < M)用 yield_rate_3/4，
 * 非航煤期用 yield_rate/yield_rate_2。后端按目标产出算 M/N 天数分配。
 *
 * 受控组件：value/onChange 通知父组件，父组件据此决定请求体 hangmei_target。
 * target=全局单值，对所有勾选月统一生效（各月 M/N 由各自批次独立算）。
 */
export default function HangmeiPanel({
  value, onChange, disabled,
}: {
  value: HangmeiInput
  onChange: (v: HangmeiInput) => void
  disabled?: boolean
}) {
  const { enabled, target } = value

  return (
    <div className="flex items-center gap-5 flex-wrap">
      <div className="flex items-center gap-1.5 text-xs text-slate-400">
        <Plane className="w-3.5 h-3.5" />航煤工况
      </div>
      <button
        onClick={() => onChange({ ...value, enabled: !enabled })} disabled={disabled}
        className={`inline-flex items-center gap-1.5 h-8 px-3 rounded-lg text-sm border transition-colors disabled:opacity-50
          ${enabled ? 'border-sky-400 bg-sky-50 text-sky-700' : 'border-slate-200 bg-white text-slate-500 hover:border-slate-300'}`}>
        {enabled ? <PlaneTakeoff className="w-3.5 h-3.5" /> : <Plane className="w-3.5 h-3.5" />}
        {enabled ? '航煤·启用' : '航煤·关闭'}
      </button>
      {enabled && (
        <div className="flex items-center gap-1.5 text-xs text-slate-600">
          <span className="text-slate-400">目标航煤产出</span>
          <input type="number" min={0} value={target} disabled={disabled}
            onChange={e => onChange({ ...value, target: Math.max(0, Number(e.target.value) || 0) })}
            className="w-24 h-7 rounded border border-slate-200 px-2 text-center text-slate-700 disabled:bg-slate-50" />
          <span className="text-slate-400">吨/月</span>
          <span className="ml-1 text-[11px] text-sky-500">
            按首批次方向算 M 航煤天数 / N 非航煤天数，逼近目标产出
          </span>
        </div>
      )}
    </div>
  )
}
