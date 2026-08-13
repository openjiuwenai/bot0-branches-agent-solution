import { useState, useEffect } from 'react'
import { Cog, Archive, Plus, Trash2 } from 'lucide-react'
import { CardHead } from '@/components/SolveResult'
import { cn } from '@/lib/utils'
import { EmptyHint } from './EmptyHint'
import type { DeviceRow } from '../types'

// 慧炼主数据装置（public.md_device）
type MdDevice = { id: number; name: string; alias: string }

// 罐分类选项
const TANK_CATEGORIES = [
  { value: 'intermediate', label: '中间罐' },
  { value: 'product', label: '成品罐' },
  { value: 'crude', label: '原油罐' },
]

// 2026 年 1-12 月固定选项
function buildMonthOptions(): { key: string; label: string }[] {
  const opts: { key: string; label: string }[] = []
  for (let m = 1; m <= 12; m++) {
    const key = `2026-${String(m).padStart(2, '0')}`
    opts.push({ key, label: `2026年${m}月` })
  }
  return opts
}

export function DeviceTable({ rows, orig, onChange, onAdd, onDelete, lockType,
  tankMonthlyInitials, onMonthlyInitialChange, selectedMonth, onMonthChange }: {
  rows: DeviceRow[]
  orig: DeviceRow[]
  onChange: (i: number, field: keyof DeviceRow, v: string | number | boolean) => void
  onAdd: () => void
  onDelete: (i: number) => void
  lockType?: string
  tankMonthlyInitials?: Record<string, number>
  onMonthlyInitialChange?: (tankId: string, val: number) => void
  selectedMonth?: string
  onMonthChange?: (month: string) => void
}) {
  const [mdDevices, setMdDevices] = useState<MdDevice[]>([])

  useEffect(() => {
    fetch('/api/md_devices', { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(d => { if (d?.success && Array.isArray(d.data)) setMdDevices(d.data) })
      .catch(() => {})
  }, [])

  if (!rows.length) return <EmptyHint text={lockType === 'tank' ? "暂无储罐数据" : "暂无装置数据"} />
  const isTank = lockType === 'tank'
  const monthOptions = buildMonthOptions()
  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60">
        <CardHead icon={isTank ? Archive : Cog} title={isTank ? "储罐管理" : "装置管理"} accent="from-slate-600 to-slate-700"
          hint={`共 ${rows.length} ${isTank ? '个储罐' : '台装置'} · 行内编辑后点右上角保存 · 新增行需填写ID`} />
        {isTank && (
          <div className="ml-auto flex items-center gap-2 text-[12px] text-slate-500">
            <span>月初容量月份:</span>
            <select value={selectedMonth || ''} onChange={e => onMonthChange?.(e.target.value)}
              className="text-sm rounded border border-slate-200 px-2 py-1 outline-none bg-white">
              {monthOptions.map(m => <option key={m.key} value={m.key}>{m.label}</option>)}
            </select>
          </div>
        )}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
              <th className="text-left font-medium px-3 py-2 min-w-[100px]">{isTank ? '储罐ID' : '装置ID'}</th>
              <th className="text-left font-medium px-3 py-2 min-w-[120px]">名称</th>
              <th className="text-left font-medium px-3 py-2 w-28">类型</th>
              <th className="text-right font-medium px-3 py-2 w-32">安全库存阈值</th>
              <th className="text-right font-medium px-3 py-2 w-32">低安全阈值</th>
              <th className="text-right font-medium px-3 py-2 w-32">当前容量</th>
              <th className="text-right font-medium px-3 py-2 w-28">负荷率(%)</th>
              {isTank && <th className="text-left font-medium px-3 py-2 w-32">罐分类</th>}
              {isTank && <th className="text-right font-medium px-3 py-2 w-36">月初容量</th>}
              {isTank ? null : <th className="text-left font-medium px-3 py-2 w-44">关联主数据装置</th>}
              <th className="text-center font-medium px-3 py-2 w-20">启用</th>
              <th className="text-center font-medium px-3 py-2 w-12"></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => {
              const o = orig.find(x => x.id === r.id)
              const isDirty = !o
                || r.name !== o.name || r.type !== o.type
                || Math.abs(r.safety_stock_thrd - o.safety_stock_thrd) > 0.01
                || Math.abs(r.low_safety_thrd - o.low_safety_thrd) > 0.01
                || Math.abs(r.current_capacity - o.current_capacity) > 0.01
                || Math.abs(r.refinery_unit_load_percent - o.refinery_unit_load_percent) > 0.01
                || (r.backend_device_id ?? null) !== (o.backend_device_id ?? null)
                || (r.tank_category ?? null) !== (o.tank_category ?? null)
                || (r.enabled ?? true) !== (o.enabled ?? true)
              const isIntermediate = isTank && r.tank_category === 'intermediate'
              return (
                <tr key={i} className={cn('border-b border-slate-50 last:border-0', isDirty && 'bg-amber-50/40')}>
                  <td className="px-3 py-2">
                    <input type="text" value={r.id} placeholder="如 cyjq_02"
                      onChange={e => onChange(i, 'id', e.target.value)}
                      className="w-full font-mono text-[11px] rounded border border-slate-200 px-2 py-1 focus:border-slate-400 focus:ring-1 focus:ring-slate-300 outline-none" />
                  </td>
                  <td className="px-3 py-2">
                    <input type="text" value={r.name}
                      onChange={e => onChange(i, 'name', e.target.value)}
                      className="w-full text-sm rounded border border-slate-200 px-2 py-1 focus:border-slate-400 focus:ring-1 focus:ring-slate-300 outline-none" />
                  </td>
                  <td className="px-3 py-2">
                    {isTank ? (
                      <input type="text" value="tank" disabled
                        className="w-full text-sm rounded border border-slate-200 bg-slate-50 px-2 py-1 text-slate-400 cursor-not-allowed" />
                    ) : (
                      <select value={r.type}
                        onChange={e => onChange(i, 'type', e.target.value)}
                        className="w-full text-sm rounded border border-slate-200 px-2 py-1 outline-none bg-white">
                        <option value="normal">normal</option>
                        <option value="tank">tank</option>
                        <option value="start">start</option>
                      </select>
                    )}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <input type="number" step="0.01" value={r.safety_stock_thrd}
                      onChange={e => onChange(i, 'safety_stock_thrd', parseFloat(e.target.value) || 0)}
                      className="w-28 text-right font-mono text-sm rounded border border-slate-200 px-2 py-1 focus:border-amber-400 focus:ring-1 focus:ring-amber-300 outline-none" />
                  </td>
                  <td className="px-3 py-2 text-right">
                    <input type="number" step="0.01" value={r.low_safety_thrd}
                      onChange={e => onChange(i, 'low_safety_thrd', parseFloat(e.target.value) || 0)}
                      className="w-28 text-right font-mono text-sm rounded border border-slate-200 px-2 py-1 focus:border-amber-400 focus:ring-1 focus:ring-amber-300 outline-none" />
                  </td>
                  <td className="px-3 py-2 text-right">
                    <input type="number" step="0.01" value={r.current_capacity}
                      onChange={e => onChange(i, 'current_capacity', parseFloat(e.target.value) || 0)}
                      className="w-28 text-right font-mono text-sm rounded border border-slate-200 px-2 py-1 focus:border-amber-400 focus:ring-1 focus:ring-amber-300 outline-none" />
                  </td>
                  <td className="px-3 py-2 text-right">
                    <input type="number" step="0.1" value={r.refinery_unit_load_percent}
                      onChange={e => onChange(i, 'refinery_unit_load_percent', parseFloat(e.target.value) || 0)}
                      className="w-24 text-right font-mono text-sm rounded border border-slate-200 px-2 py-1 focus:border-amber-400 focus:ring-1 focus:ring-amber-300 outline-none" />
                  </td>
                  {isTank && (
                    <td className="px-3 py-2">
                      <select value={r.tank_category ?? ''}
                        onChange={e => onChange(i, 'tank_category', e.target.value || null as unknown as string)}
                        className="w-full text-sm rounded border border-slate-200 px-2 py-1 outline-none bg-white">
                        <option value="">— 未分类 —</option>
                        {TANK_CATEGORIES.map(tc => (
                          <option key={tc.value} value={tc.value}>{tc.label}</option>
                        ))}
                      </select>
                    </td>
                  )}
                  {isTank && (
                    <td className="px-3 py-2 text-right">
                      {isIntermediate ? (
                        <input type="number" step="0.01"
                          value={tankMonthlyInitials?.[r.id] ?? r.current_capacity}
                          onChange={e => onMonthlyInitialChange?.(r.id, parseFloat(e.target.value) || 0)}
                          className="w-32 text-right font-mono text-sm rounded border border-slate-200 px-2 py-1 focus:border-sky-400 focus:ring-1 focus:ring-sky-300 outline-none" />
                      ) : (
                        <span className="text-slate-300 text-xs">—</span>
                      )}
                    </td>
                  )}
                  {isTank ? null : (
                    <td className="px-3 py-2">
                      <select value={r.backend_device_id ?? ''}
                        onChange={e => onChange(i, 'backend_device_id', e.target.value ? parseInt(e.target.value, 10) : null as unknown as number)}
                        className="w-full text-sm rounded border border-slate-200 px-2 py-1 outline-none bg-white">
                        <option value="">— 未关联 —</option>
                        {mdDevices.map(md => (
                          <option key={md.id} value={md.id}>
                            {md.name}{md.alias ? `（${md.alias}）` : ''}
                          </option>
                        ))}
                      </select>
                    </td>
                  )}
                  <td className="px-3 py-2 text-center">
                    <button onClick={() => onChange(i, 'enabled', !(r.enabled ?? true))}
                      className={cn('relative inline-flex h-5 w-9 items-center rounded-full transition-colors',
                        (r.enabled ?? true) ? 'bg-emerald-500' : 'bg-slate-300')}>
                      <span className={cn('inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform',
                        (r.enabled ?? true) ? 'translate-x-4' : 'translate-x-1')} />
                    </button>
                  </td>
                  <td className="px-3 py-2 text-center">
                    <button onClick={() => onDelete(i)}
                      className="text-slate-300 hover:text-red-500 transition-colors">
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <button onClick={onAdd}
        className="w-full flex items-center justify-center gap-1.5 py-2.5 text-[13px] text-slate-500 hover:text-slate-700 hover:bg-slate-50/60 border-t border-slate-100 transition-colors">
        <Plus className="w-3.5 h-3.5" />{isTank ? '新增储罐' : '新增装置'}
      </button>
    </div>
  )
}
