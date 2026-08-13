import { useState } from 'react'
import { Link } from 'lucide-react'
import { CardHead } from '@/components/SolveResult'
import { cn } from '@/lib/utils'
import { EmptyHint } from './EmptyHint'
import type { MappingRow, MaterialOption } from '../types'

export function MappingTable({ rows, materials, orig, onChange }: {
  rows: MappingRow[]
  materials: MaterialOption[]
  orig: MappingRow[]
  onChange: (i: number, material_id: number | null) => void
}) {
  if (!rows.length) return <EmptyHint text="暂无产品数据" />
  const [savingId, setSavingId] = useState<string | null>(null)

  async function saveRow(r: MappingRow) {
    setSavingId(r.product_id)
    try {
      const res = await fetch('/api/price_cost/mapping', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ product_id: r.product_id, material_id: r.material_id }),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      // 更新 orig 中对应行的值
      const idx = orig.findIndex(o => o.product_id === r.product_id)
      if (idx >= 0) orig[idx] = { ...r }
    } catch (e) {
      alert(`保存失败: ${e instanceof Error ? e.message : e}`)
    } finally {
      setSavingId(null)
    }
  }

  // 统计
  const total = rows.length
  const bound = rows.filter(r => r.material_id !== null).length
  const unbound = total - bound

  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60">
        <CardHead icon={Link} title="产品物料映射" accent="from-cyan-500 to-blue-600"
          hint={`共 ${total} 个产品 · 已绑定 ${bound} · 未绑定 ${unbound} · 选择物料后自动保存`} />
      </div>
      <table className="w-full text-sm">
        <thead>
          <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
            <th className="text-left font-medium px-3 py-2">产品ID</th>
            <th className="text-left font-medium px-3 py-2">产品名称</th>
            <th className="text-left font-medium px-3 py-2 w-72">绑定物料</th>
            <th className="text-center font-medium px-3 py-2 w-20">状态</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const o = orig[i]
            const isDirty = r.material_id !== (o?.material_id ?? null)
            return (
              <tr key={r.product_id}
                className={cn(
                  'border-b border-slate-50 last:border-0 hover:bg-slate-50/40',
                  isDirty && 'bg-amber-50/40'
                )}>
                <td className="px-3 py-2 font-mono text-[11px] text-slate-500">{r.product_id}</td>
                <td className="px-3 py-2 text-slate-700">{r.product_name || r.product_id}</td>
                <td className="px-3 py-2">
                  <select
                    value={r.material_id ?? ''}
                    onChange={e => {
                      const v = e.target.value
                      onChange(i, v ? parseInt(v) : null)
                    }}
                    className="w-full font-mono text-sm rounded-md border border-slate-200 px-2 py-1.5 focus:border-cyan-400 focus:ring-1 focus:ring-cyan-300 outline-none bg-white">
                    <option value="">-- 未绑定 --</option>
                    {materials.map(m => (
                      <option key={m.material_id} value={m.material_id}>
                        [{m.material_id}] {m.material_name}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="px-3 py-2 text-center">
                  {r.material_id ? (
                    <span className="inline-flex items-center gap-1 text-[11px] text-emerald-600 font-medium">
                      <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />已绑定
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-1 text-[11px] text-slate-400 font-medium">
                      <span className="w-1.5 h-1.5 rounded-full bg-slate-300" />未绑定
                    </span>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
