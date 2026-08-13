'use client'

import { useState, useEffect } from 'react'
import { Power, PowerOff } from 'lucide-react'

// 装置停工声明（unit 为装置 ID，动态从 /api/units 加载）
// 时间精度到分钟，ISO 字符串格式（如 "2026-04-05T08:00"）
export type ShutdownItem = {
  unit: string
  start_time: string  // ISO: "2026-04-05T08:00"
  end_time: string    // ISO: "2026-04-08T18:00"
}

// 后端返回的停工摘要（switch_planner.enumerate_valve_switching.shutdown）
export type ShutdownInfo = {
  enabled: boolean
  windows: { unit: string; unit_name: string; start_time: string; end_time: string; start_hour: number; end_hour: number }[]
  conflicts: { day: number; reason: string }[]
}

// 加工装置（type 非 tank/start）
type UnitDevice = { id: string; name: string; type: string; enabled?: boolean }

/**
 * 装置停工面板：勾选加工装置停工 + 填写起止时间（精度到分钟）。
 *
 * 停工语义（v2）：
 *   - 停工不再触发 X/Y 强制改道
 *   - 停工只影响：装置在该时段产出按比例缩减 + 罐容累积更易触发上限
 *   - 停工装置进料罐照常接收 CDU 来料（出向按非停工比例缩减）
 *
 * 装置列表从 /api/units 动态加载，不再硬编码。
 * 受控组件：value 为停工项数组，onChange 通知父组件更新请求体。
 * 空数组表示无停工。
 */
export default function ShutdownPanel({
  value, onChange, disabled, month,
}: {
  value: ShutdownItem[]
  onChange: (items: ShutdownItem[]) => void
  disabled?: boolean
  month?: string  // "2026-04"，用于生成默认起止时间
}) {
  const [units, setUnits] = useState<UnitDevice[]>([])

  useEffect(() => {
    fetch('/api/units', { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(d => {
        // 后端 _build_crud 返回 {<list_key>: [...]}，units 接口为 {units: [...]}
        const list = d?.units ?? d?.data ?? []
        if (Array.isArray(list)) {
          setUnits(list.filter((u: UnitDevice) => u.type !== 'tank' && u.type !== 'start' && u.enabled !== false))
        }
      })
      .catch(() => {})
  }, [])

  // 生成默认起止时间：当月1日00:00 ~ 28日00:00
  const defaultRange = (m?: string): { start: string; end: string } => {
    if (m && /^\d{4}-\d{2}$/.test(m)) {
      return { start: `${m}-01T00:00`, end: `${m}-28T00:00` }
    }
    const now = new Date()
    const y = now.getFullYear()
    const mo = String(now.getMonth() + 1).padStart(2, '0')
    return { start: `${y}-${mo}-01T00:00`, end: `${y}-${mo}-28T00:00` }
  }

  // 切换某装置停工开关
  const toggle = (unit: string, on: boolean) => {
    const rest = value.filter(v => v.unit !== unit)
    if (on) {
      const { start, end } = defaultRange(month)
      onChange([...rest, { unit, start_time: start, end_time: end }])
    } else {
      onChange(rest)
    }
  }
  // 改某装置起止时间
  const setTime = (unit: string, field: 'start_time' | 'end_time', val: string) => {
    onChange(value.map(v => v.unit === unit ? { ...v, [field]: val } : v))
  }

  // 格式化 ISO 时间为简短显示
  const fmtTime = (iso: string): string => {
    if (!iso) return ''
    // "2026-04-05T08:00" → "04-05 08:00"
    const m = iso.match(/(\d{2})-(\d{2})T(\d{2}:\d{2})/)
    return m ? `${m[1]}-${m[2]} ${m[3]}` : iso
  }

  const renderRow = (unit: string, label: string, on: boolean, item?: ShutdownItem) => (
    <div key={unit} className="flex items-center gap-3 flex-wrap">
      <button
        onClick={() => toggle(unit, !on)} disabled={disabled}
        className={`inline-flex items-center gap-1.5 h-8 px-3 rounded-lg text-sm border transition-colors disabled:opacity-50
          ${on ? 'border-red-400 bg-red-50 text-red-700' : 'border-slate-200 bg-white text-slate-500 hover:border-slate-300'}`}>
        {on ? <PowerOff className="w-3.5 h-3.5" /> : <Power className="w-3.5 h-3.5" />}
        {label}{on ? '·停工' : '·运行'}
      </button>
      {on && item && (
        <div className="flex items-center gap-1.5 text-xs text-slate-600">
          <input type="datetime-local" value={item.start_time} disabled={disabled}
            onChange={e => setTime(unit, 'start_time', e.target.value)}
            className="h-7 rounded border border-slate-200 px-1.5 text-xs text-slate-700 disabled:bg-slate-50" />
          <span className="text-slate-400">→</span>
          <input type="datetime-local" value={item.end_time} disabled={disabled}
            onChange={e => setTime(unit, 'end_time', e.target.value)}
            className="h-7 rounded border border-slate-200 px-1.5 text-xs text-slate-700 disabled:bg-slate-50" />
          <span className="ml-1 text-[11px] text-red-500">装置停工·产能折减</span>
        </div>
      )}
    </div>
  )

  return (
    <div className="flex items-center gap-5 flex-wrap">
      <div className="flex items-center gap-1.5 text-xs text-slate-400">
        <Power className="w-3.5 h-3.5" />装置停工
      </div>
      {units.length > 0
        ? units.map(u => {
            const item = value.find(v => v.unit === u.id)
            return renderRow(u.id, u.name, !!item, item)
          })
        : <span className="text-xs text-slate-400">加载装置列表…</span>
      }
    </div>
  )
}
