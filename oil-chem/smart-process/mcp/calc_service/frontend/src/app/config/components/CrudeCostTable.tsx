import { Droplets } from 'lucide-react'
import { CardHead } from '@/components/SolveResult'
import { EmptyHint } from './EmptyHint'
import type { CrudeCost } from '../types'

export function CrudeCostTable({ rows, onChange }: {
  rows: CrudeCost[]
  onChange: (i: number, v: number) => void
}) {
  if (!rows.length) return <EmptyHint text="该月份暂无原油成本数据" />
  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60">
        <CardHead icon={Droplets} title="原油成本" accent="from-rose-500 to-rose-600"
          hint={`共 ${rows.length} 个油种 · 单位 元/吨 · 行内编辑后点右上角保存`} />
      </div>
      <table className="w-full text-sm">
        <thead>
          <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
            <th className="text-left font-medium px-3 py-2">原油品种</th>
            <th className="text-left font-medium px-3 py-2">月份</th>
            <th className="text-right font-medium px-3 py-2 w-48">成本(元/吨)</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={`${r.planned_month}-${r.crude_type_id}`} className="border-b border-slate-50 last:border-0 hover:bg-slate-50/40">
              <td className="px-3 py-2 text-slate-700">{r.crude_type_name || r.crude_type_id}</td>
              <td className="px-3 py-2 font-mono text-xs text-slate-500">{r.planned_month}</td>
              <td className="px-3 py-2 text-right">
                <input type="number" step="0.01" value={r.cost}
                  onChange={e => onChange(i, parseFloat(e.target.value) || 0)}
                  className="w-40 text-right font-mono text-sm rounded-md border border-slate-200 px-2 py-1 focus:border-amber-400 focus:ring-1 focus:ring-amber-300 outline-none" />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
