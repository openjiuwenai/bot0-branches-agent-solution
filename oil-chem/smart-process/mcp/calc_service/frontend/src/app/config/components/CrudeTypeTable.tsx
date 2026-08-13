import { Droplet, Plus, Trash2 } from 'lucide-react'
import { CardHead } from '@/components/SolveResult'
import { cn } from '@/lib/utils'
import { EmptyHint } from './EmptyHint'
import type { CrudeTypeRow } from '../types'

export function CrudeTypeTable({ rows, orig, onChange, onAdd, onDelete }: {
  rows: CrudeTypeRow[]
  orig: CrudeTypeRow[]
  onChange: (i: number, field: keyof CrudeTypeRow, v: string | number | boolean | string[]) => void
  onAdd: () => void
  onDelete: (i: number) => void
}) {
  if (!rows.length) return <EmptyHint text="暂无油种数据" />
  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-100 bg-slate-50/60">
        <CardHead icon={Droplet} title="油种管理" accent="from-cyan-500 to-blue-600"
          hint={`共 ${rows.length} 种油种 · 主数据表 · 供收率配置和排产计划引用`} />
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-slate-50/50 text-[12px] text-slate-500 border-b border-slate-100">
              <th className="text-left font-medium px-3 py-2 min-w-[120px]">油种ID</th>
              <th className="text-left font-medium px-3 py-2 min-w-[100px]">中文名称</th>
              <th className="text-left font-medium px-3 py-2 w-20">简码</th>
              <th className="text-left font-medium px-3 py-2 min-w-[150px]">别名</th>
              <th className="text-center font-medium px-3 py-2 w-16">在用</th>
              <th className="text-center font-medium px-3 py-2 w-16">通配</th>
              <th className="text-right font-medium px-3 py-2 w-16">排序</th>
              <th className="text-left font-medium px-3 py-2 w-32">备注</th>
              <th className="text-center font-medium px-3 py-2 w-12"></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => {
              const o = orig.find(x => x.crude_type_id === r.crude_type_id)
              const isDirty = !o
                || r.crude_name !== o.crude_name || r.crude_code !== o.crude_code
                || r.aliases.join(',') !== o.aliases.join(',')
                || r.is_active !== o.is_active || r.is_default !== o.is_default
                || r.sort_order !== o.sort_order || r.note !== o.note
              const isDefaultRow = r.crude_type_id === 'default'
              return (
                <tr key={i} className={cn('border-b border-slate-50 last:border-0', isDirty && 'bg-amber-50/40')}>
                  <td className="px-3 py-2">
                    {isDefaultRow ? (
                      <span className="font-mono text-[11px] text-slate-500 px-2 py-1 bg-slate-100 rounded">{r.crude_type_id}</span>
                    ) : (
                      <input type="text" value={r.crude_type_id} placeholder="如 bozhong_25_1"
                        onChange={e => onChange(i, 'crude_type_id', e.target.value)}
                        className="w-full font-mono text-[11px] rounded border border-slate-200 px-2 py-1 focus:border-cyan-400 focus:ring-1 focus:ring-cyan-300 outline-none" />
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <input type="text" value={r.crude_name}
                      onChange={e => onChange(i, 'crude_name', e.target.value)}
                      className="w-full text-sm rounded border border-slate-200 px-2 py-1 focus:border-cyan-400 focus:ring-1 focus:ring-cyan-300 outline-none" />
                  </td>
                  <td className="px-3 py-2">
                    <input type="text" value={r.crude_code} placeholder="如 BZ5"
                      onChange={e => onChange(i, 'crude_code', e.target.value)}
                      className="w-full font-mono text-[12px] rounded border border-slate-200 px-2 py-1 outline-none" />
                  </td>
                  <td className="px-3 py-2">
                    <input type="text" value={r.aliases.join(', ')} placeholder="逗号分隔"
                      onChange={e => {
                        const arr = e.target.value.split(',').map(s => s.trim()).filter(Boolean)
                        onChange(i, 'aliases', arr)
                      }}
                      className="w-full text-[12px] rounded border border-slate-200 px-2 py-1 outline-none" />
                  </td>
                  <td className="px-3 py-2 text-center">
                    <input type="checkbox" checked={r.is_active}
                      onChange={e => onChange(i, 'is_active', e.target.checked)}
                      className="w-4 h-4 rounded accent-cyan-600" />
                  </td>
                  <td className="px-3 py-2 text-center">
                    {isDefaultRow ? (
                      <span className="text-[10px] text-amber-600 bg-amber-50 px-1.5 py-0.5 rounded">是</span>
                    ) : (
                      <input type="checkbox" checked={r.is_default}
                        onChange={e => onChange(i, 'is_default', e.target.checked)}
                        className="w-4 h-4 rounded accent-amber-600" />
                    )}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <input type="number" value={r.sort_order}
                      onChange={e => onChange(i, 'sort_order', parseInt(e.target.value) || 0)}
                      className="w-14 text-right font-mono text-sm rounded border border-slate-200 px-2 py-1 outline-none" />
                  </td>
                  <td className="px-3 py-2">
                    <input type="text" value={r.note}
                      onChange={e => onChange(i, 'note', e.target.value)}
                      className="w-full text-[12px] rounded border border-slate-200 px-2 py-1 outline-none" />
                  </td>
                  <td className="px-3 py-2 text-center">
                    {!isDefaultRow && (
                      <button onClick={() => onDelete(i)}
                        className="text-slate-300 hover:text-red-500 transition-colors">
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <button onClick={onAdd}
        className="w-full flex items-center justify-center gap-1.5 py-2.5 text-[13px] text-slate-500 hover:text-slate-700 hover:bg-slate-50/60 border-t border-slate-100 transition-colors">
        <Plus className="w-3.5 h-3.5" />新增油种
      </button>
    </div>
  )
}
