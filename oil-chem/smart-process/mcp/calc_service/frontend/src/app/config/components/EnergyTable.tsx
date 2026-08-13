import { useState, useMemo } from 'react'
import { Flame, ChevronDown, ChevronRight as ChevRight } from 'lucide-react'
import { CardHead } from '@/components/SolveResult'
import { cn } from '@/lib/utils'
import { EmptyHint } from './EmptyHint'
import type { EnergyRow } from '../types'

export function EnergyTable({ rows, orig, onChange }: {
  rows: EnergyRow[]
  orig: EnergyRow[]
  onChange: (i: number, field: 'consumption_per_ton' | 'price_per_unit', v: number) => void
}) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  // 按装置分组（同 device_id 聚合），保留原始行索引以便 onChange 定位
  const groups = useMemo(() => {
    const m: Record<string, { row: EnergyRow; idx: number }[]> = {}
    rows.forEach((r, i) => { (m[r.device_id] = m[r.device_id] || []).push({ row: r, idx: i }) })
    return m
  }, [rows])
  const deviceIds = Object.keys(groups).sort()

  function toggle(did: string) {
    setExpanded(prev => {
      const next = new Set(prev)
      next.has(did) ? next.delete(did) : next.add(did)
      return next
    })
  }

  if (!rows.length) return <EmptyHint text="暂无能耗系数数据" />
  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60">
        <CardHead icon={Flame} title="能耗系数" accent="from-amber-500 to-orange-500"
          hint={`共 ${rows.length} 条 · 按装置分组 · 仅修改行参与保存`} />
      </div>
      <div className="divide-y divide-slate-100">
        {deviceIds.map(did => {
          const isOpen = expanded.has(did)
          const groupRows = groups[did]
          return (
            <div key={did}>
              <button onClick={() => toggle(did)}
                className="w-full flex items-center gap-2 px-4 py-2.5 hover:bg-slate-50/60 transition-colors">
                {isOpen ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
                <span className="text-[13px] font-semibold text-slate-800">{did}</span>
                <span className="text-[11px] text-slate-400">{groupRows.length} 项能耗</span>
              </button>
              {isOpen && (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="bg-slate-50/50 text-[12px] text-slate-500 border-y border-slate-100">
                      <th className="text-left font-medium px-3 py-2">能源类型</th>
                      <th className="text-right font-medium px-3 py-2 w-40">单耗系数</th>
                      <th className="text-right font-medium px-3 py-2 w-40">单价(元/单位)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {groupRows.map(({ row: r, idx }) => {
                      const o = orig.find(x => x.id === r.id)
                      const dirtyC = o && Math.abs(r.consumption_per_ton - o.consumption_per_ton) > 0.0001
                      const dirtyP = o && Math.abs(r.price_per_unit - o.price_per_unit) > 0.001
                      const dirty = dirtyC || dirtyP
                      return (
                        <tr key={r.id} className={cn('border-b border-slate-50 last:border-0', dirty && 'bg-amber-50/40')}>
                          <td className="px-3 py-2 text-slate-700">{r.energy_type}</td>
                          <td className="px-3 py-2 text-right">
                            <input type="number" step="0.0004" value={r.consumption_per_ton}
                              onChange={e => onChange(idx, 'consumption_per_ton', parseFloat(e.target.value) || 0)}
                              className="w-32 text-right font-mono text-sm rounded-md border border-slate-200 px-2 py-1 focus:border-amber-400 focus:ring-1 focus:ring-amber-300 outline-none" />
                          </td>
                          <td className="px-3 py-2 text-right">
                            <input type="number" step="0.01" value={r.price_per_unit}
                              onChange={e => onChange(idx, 'price_per_unit', parseFloat(e.target.value) || 0)}
                              className="w-32 text-right font-mono text-sm rounded-md border border-slate-200 px-2 py-1 focus:border-amber-400 focus:ring-1 focus:ring-amber-300 outline-none" />
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
