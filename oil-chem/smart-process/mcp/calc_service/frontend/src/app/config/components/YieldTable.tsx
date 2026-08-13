import { useState, useMemo } from 'react'
import { Beaker, ChevronDown, ChevronRight as ChevRight, Trash2, ArrowUpFromLine, ArrowDownToLine } from 'lucide-react'
import { CardHead } from '@/components/SolveResult'
import { cn } from '@/lib/utils'
import { EmptyHint } from './EmptyHint'
import type { YieldRow, DeviceRow } from '../types'

export function YieldTable({ rows, orig, crudeFilter, tankIds, allDevices, crudeTypes, onCrudeFilterChange, onChange, onDelete }: {
  rows: YieldRow[]
  orig: YieldRow[]
  crudeFilter: string
  tankIds: Set<string>
  allDevices: DeviceRow[]
  crudeTypes: {crude_type_id: string; crude_name: string; crude_code: string; is_default: boolean}[]
  onCrudeFilterChange: (v: string) => void
  onChange: (i: number, field: keyof YieldRow, v: string | number | boolean) => void
  onDelete: (i: number) => void
}) {
  // 用 collapsed 集合管理：空集合 = 全部展开（默认展开）
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())

  const { unitGroups } = useMemo(() => {
    const filtered = rows.map((r, i) => ({ r, i }))
      .filter(({ r }) => !crudeFilter || r.crude_type === crudeFilter)

    const unitMap: Record<string, { row: YieldRow; idx: number }[]> = {}
    for (const { r, i } of filtered) {
      ;(unitMap[r.source_device_id] = unitMap[r.source_device_id] || []).push({ row: r, idx: i })
    }
    for (const dev of allDevices) {
      if (!unitMap[dev.id]) unitMap[dev.id] = []
    }
    return { unitGroups: unitMap }
  }, [rows, crudeFilter, tankIds, allDevices])

  const unitDeviceIds = Object.keys(unitGroups).sort()

  const deviceNameMap = useMemo(() => {
    const m: Record<string, string> = {}
    for (const d of allDevices) m[d.id] = d.name
    return m
  }, [allDevices])

  function toggle(did: string) {
    setCollapsed(prev => {
      const next = new Set(prev)
      next.has(did) ? next.delete(did) : next.add(did)
      return next
    })
  }

  if (!rows.length && !allDevices.length) return <EmptyHint text="暂无收率数据" />

  return (
    <div className="space-y-4">
      {/* ── 加工装置产品 ── */}
      <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
        <div className="flex items-center justify-between gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60">
          <CardHead icon={Beaker} title="装置产品收率" accent="from-teal-500 to-cyan-600"
            hint={`共 ${rows.length} 条 · 按装置分组 · 仅收率字段可编辑，侧线身份请在「侧线配置」页管理`} />
          <select value={crudeFilter} onChange={e => onCrudeFilterChange(e.target.value)}
            className="h-8 rounded-md border border-slate-200 bg-white px-2 text-sm text-slate-700">
            <option key="__all__" value="">全部油种</option>
            {crudeTypes.map((c, idx) => <option key={c.crude_type_id || `ct-${idx}`} value={c.crude_type_id}>{c.crude_name}{c.is_default ? ' (通配)' : ''}</option>)}
          </select>
        </div>
        <div className="divide-y divide-slate-100">
          {unitDeviceIds.map(did => (
            <DeviceGroup key={did} did={did} groupRows={unitGroups[did]} orig={orig}
              deviceName={deviceNameMap[did]}
              collapsed={collapsed} toggle={toggle} crudeTypes={crudeTypes}
              onChange={onChange} onDelete={onDelete} />
          ))}
          {!unitDeviceIds.length && (
            <div className="px-4 py-6 text-center text-[13px] text-slate-400">暂无装置产品</div>
          )}
        </div>
      </div>
    </div>
  )
}

function DeviceGroup({ did, groupRows, orig, deviceName, collapsed, toggle, crudeTypes, onChange, onDelete, isTank }: {
  did: string
  groupRows: { row: YieldRow; idx: number }[]
  orig: YieldRow[]
  deviceName?: string
  collapsed: Set<string>
  toggle: (did: string) => void
  crudeTypes: {crude_type_id: string; crude_name: string; crude_code: string; is_default: boolean}[]
  onChange: (i: number, field: keyof YieldRow, v: string | number | boolean) => void
  onDelete: (i: number) => void
  isTank?: boolean
}) {
  // collapsed 集合为空 = 全部展开；不在集合中 = 展开
  const isOpen = !collapsed.has(did)
  const inputRows = groupRows.filter(({ row }) => row.material_type === 'main_feed' || row.material_type === 'auxiliary')
  const outputRows = groupRows.filter(({ row }) => row.material_type === 'product')
  const isEmpty = !groupRows.length

  // 出料总收率汇总
  const outputSums = useMemo(() => {
    if (isTank || !outputRows.length) return null
    const s = { y1: 0, y2: 0, y3: 0, y4: 0 }
    for (const { row } of outputRows) {
      s.y1 += row.yield_rate
      s.y2 += row.yield_rate_2
      s.y3 += row.yield_rate_3
      s.y4 += row.yield_rate_4
    }
    return s
  }, [outputRows, isTank])

  // 进料总配比汇总
  const inputSums = useMemo(() => {
    if (isTank || !inputRows.length) return null
    const s = { y1: 0, y2: 0, y3: 0, y4: 0 }
    for (const { row } of inputRows) {
      s.y1 += row.yield_rate
      s.y2 += row.yield_rate_2
      s.y3 += row.yield_rate_3
      s.y4 += row.yield_rate_4
    }
    return s
  }, [inputRows, isTank])

  function deviationColor(sum: number): string {
    const dev = Math.abs(sum - 100)
    if (dev <= 0.5) return 'text-emerald-600'
    if (dev <= 5) return 'text-amber-600'
    return 'text-red-600'
  }

  return (
    <div>
      <button onClick={() => toggle(did)}
        className="w-full flex items-center gap-2 px-4 py-2.5 hover:bg-slate-50/60 transition-colors">
        {isOpen ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
        {deviceName && <span className="text-[13px] font-semibold text-slate-800">{deviceName}</span>}
        <span className="text-[10px] text-slate-400 font-mono">{did || '(未设置)'}</span>
        <span className={cn('text-[11px]', isEmpty ? 'text-amber-500' : 'text-slate-400')}>
          {isEmpty ? '未配置' : `${groupRows.length} 条`}
        </span>
        {isTank && <span className="text-[10px] text-slate-400 bg-slate-100 px-1.5 py-0.5 rounded">储罐</span>}
        {!isTank && outputSums && (
          <span className={cn('text-[11px] font-mono ml-auto', deviationColor(outputSums.y1))}>
            出料合计: {outputSums.y1.toFixed(2)}% (差 {(outputSums.y1 - 100).toFixed(2)}%)
          </span>
        )}
      </button>
      {isOpen && !isEmpty && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50/50 text-[12px] text-slate-500 border-y border-slate-100">
                <th className="text-left font-medium px-3 py-2 min-w-[100px]">侧线ID</th>
                <th className="text-left font-medium px-3 py-2 min-w-[100px]">名称</th>
                <th className="text-left font-medium px-3 py-2 w-24">类型</th>
                {!isTank && <>
                  <th className="text-right font-medium px-3 py-2 w-20">收率1<br/><span className="text-[10px] font-normal text-slate-400">基础</span></th>
                  <th className="text-right font-medium px-3 py-2 w-20">收率2<br/><span className="text-[10px] font-normal text-blue-400">X_ZERO</span></th>
                  <th className="text-right font-medium px-3 py-2 w-20">收率3<br/><span className="text-[10px] font-normal text-orange-400">航煤X</span></th>
                  <th className="text-right font-medium px-3 py-2 w-20">收率4<br/><span className="text-[10px] font-normal text-red-400">航煤Y</span></th>
                </>}
                {isTank && <th className="text-right font-medium px-3 py-2 w-20">通过率</th>}
                <th className="text-left font-medium px-3 py-2 w-20">油种</th>
                <th className="text-center font-medium px-3 py-2 w-16">终端</th>
                <th className="text-center font-medium px-3 py-2 w-12"></th>
              </tr>
            </thead>
            <tbody>
              {/* 进料行 */}
              {!isTank && inputRows.length > 0 && (
                <tr className="bg-teal-50/30">
                  <td colSpan={10} className="px-3 py-1">
                    <span className="text-[11px] font-medium text-teal-600 flex items-center gap-1">
                      <ArrowDownToLine className="w-3 h-3" />进料 ({inputRows.length})
                    </span>
                  </td>
                </tr>
              )}
              {!isTank && inputRows.map(({ row: r, idx }) => (
                <YieldRow key={`in-${idx}`} r={r} idx={idx} orig={orig} isTank={isTank} crudeTypes={crudeTypes} onChange={onChange} onDelete={onDelete} />
              ))}
              {/* 进料总配比汇总行 */}
              {!isTank && inputSums && (
                <tr className="border-t border-teal-200 bg-teal-50/40">
                  <td className="px-3 py-2" colSpan={3}>
                    <span className="text-[12px] font-semibold text-teal-700">进料总配比</span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <span className={cn('font-mono text-sm font-semibold', deviationColor(inputSums.y1))}>
                      {inputSums.y1.toFixed(2)}%
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <span className={cn('font-mono text-sm font-semibold', deviationColor(inputSums.y2))}>
                      {inputSums.y2.toFixed(2)}%
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <span className={cn('font-mono text-sm font-semibold', deviationColor(inputSums.y3))}>
                      {inputSums.y3.toFixed(2)}%
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <span className={cn('font-mono text-sm font-semibold', deviationColor(inputSums.y4))}>
                      {inputSums.y4.toFixed(2)}%
                    </span>
                  </td>
                  <td className="px-3 py-2 text-center" colSpan={3}>
                    <span className={cn('text-[11px] font-mono', deviationColor(inputSums.y1))}>
                      偏差: {(inputSums.y1 - 100).toFixed(2)}%
                    </span>
                  </td>
                </tr>
              )}
              {/* 出料行 */}
              {!isTank && outputRows.length > 0 && (
                <tr className="bg-emerald-50/30">
                  <td colSpan={10} className="px-3 py-1">
                    <span className="text-[11px] font-medium text-emerald-600 flex items-center gap-1">
                      <ArrowUpFromLine className="w-3 h-3" />出料 ({outputRows.length})
                    </span>
                  </td>
                </tr>
              )}
              {!isTank && outputRows.map(({ row: r, idx }) => (
                <YieldRow key={`out-${idx}`} r={r} idx={idx} orig={orig} isTank={isTank} crudeTypes={crudeTypes} onChange={onChange} onDelete={onDelete} />
              ))}
              {/* 储罐行 */}
              {isTank && groupRows.map(({ row: r, idx }) => (
                <YieldRow key={`tank-${idx}`} r={r} idx={idx} orig={orig} isTank={isTank} crudeTypes={crudeTypes} onChange={onChange} onDelete={onDelete} />
              ))}
              {/* 出料总收率汇总行 */}
              {!isTank && outputSums && (
                <tr className="border-t-2 border-slate-200 bg-slate-50/80">
                  <td className="px-3 py-2" colSpan={3}>
                    <span className="text-[12px] font-semibold text-slate-600">出料总收率</span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <span className={cn('font-mono text-sm font-semibold', deviationColor(outputSums.y1))}>
                      {outputSums.y1.toFixed(2)}%
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <span className={cn('font-mono text-sm font-semibold', deviationColor(outputSums.y2))}>
                      {outputSums.y2.toFixed(2)}%
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <span className={cn('font-mono text-sm font-semibold', deviationColor(outputSums.y3))}>
                      {outputSums.y3.toFixed(2)}%
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <span className={cn('font-mono text-sm font-semibold', deviationColor(outputSums.y4))}>
                      {outputSums.y4.toFixed(2)}%
                    </span>
                  </td>
                  <td className="px-3 py-2 text-center" colSpan={3}>
                    <span className={cn('text-[11px] font-mono', deviationColor(outputSums.y1))}>
                      偏差: {(outputSums.y1 - 100).toFixed(2)}%
                    </span>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function YieldRow({ r, idx, orig, isTank, crudeTypes, onChange, onDelete }: {
  r: YieldRow
  idx: number
  orig: YieldRow[]
  isTank?: boolean
  crudeTypes: {crude_type_id: string; crude_name: string; crude_code: string; is_default: boolean}[]
  onChange: (i: number, field: keyof YieldRow, v: string | number | boolean) => void
  onDelete: (i: number) => void
}) {
  const o = orig.find(x => x.id === r.id)
  const isDirty = !o
    || r.crude_type !== o.crude_type
    || (!isTank && (
      Math.abs(r.yield_rate - o.yield_rate) > 0.01
      || Math.abs(r.yield_rate_2 - o.yield_rate_2) > 0.01
      || Math.abs(r.yield_rate_3 - o.yield_rate_3) > 0.01
      || Math.abs(r.yield_rate_4 - o.yield_rate_4) > 0.01
    ))
  return (
    <tr className={cn('border-b border-slate-50 last:border-0', isDirty && 'bg-amber-50/40')}>
      <td className="px-3 py-2">
        <span className="font-mono text-[11px] text-slate-500 px-2 py-1">
          {r.id.includes('~') ? r.id.split('~')[0] : r.id}
        </span>
      </td>
      <td className="px-3 py-2">
        <span className="text-sm text-slate-700 px-2 py-1">{r.name}</span>
      </td>
      <td className="px-3 py-2">
        <span className={cn('text-[12px] px-2 py-1 rounded',
          r.material_type === 'product' ? 'text-emerald-600 bg-emerald-50' :
          r.material_type === 'main_feed' ? 'text-teal-600 bg-teal-50' :
          'text-amber-600 bg-amber-50')}>
          {r.material_type}
        </span>
      </td>
      {isTank ? (
        <td className="px-3 py-2 text-right">
          <span className="font-mono text-sm text-slate-400">1.00</span>
        </td>
      ) : (
        <>
          <td className="px-3 py-2 text-right">
            <input type="number" step="0.01" value={r.yield_rate.toFixed(2)}
              onChange={e => onChange(idx, 'yield_rate', parseFloat(e.target.value) || 0)}
              className="w-20 text-right font-mono text-sm rounded border border-slate-200 px-2 py-1 focus:border-amber-400 focus:ring-1 focus:ring-amber-300 outline-none" />
          </td>
          <td className="px-3 py-2 text-right">
            <input type="number" step="0.01" value={r.yield_rate_2.toFixed(2)}
              onChange={e => onChange(idx, 'yield_rate_2', parseFloat(e.target.value) || 0)}
              className="w-20 text-right font-mono text-sm rounded border border-blue-200 px-2 py-1 focus:border-blue-400 focus:ring-1 focus:ring-blue-300 outline-none" />
          </td>
          <td className="px-3 py-2 text-right">
            <input type="number" step="0.01" value={r.yield_rate_3.toFixed(2)}
              onChange={e => onChange(idx, 'yield_rate_3', parseFloat(e.target.value) || 0)}
              className="w-20 text-right font-mono text-sm rounded border border-orange-200 px-2 py-1 focus:border-orange-400 focus:ring-1 focus:ring-orange-300 outline-none" />
          </td>
          <td className="px-3 py-2 text-right">
            <input type="number" step="0.01" value={r.yield_rate_4.toFixed(2)}
              onChange={e => onChange(idx, 'yield_rate_4', parseFloat(e.target.value) || 0)}
              className="w-20 text-right font-mono text-sm rounded border border-red-200 px-2 py-1 focus:border-red-400 focus:ring-1 focus:ring-red-300 outline-none" />
          </td>
        </>
      )}
      <td className="px-3 py-2">
        <select value={r.crude_type}
          onChange={e => onChange(idx, 'crude_type', e.target.value)}
          className="text-[12px] rounded border border-slate-200 px-1.5 py-1 outline-none bg-white">
          {crudeTypes.map((c, ci) => <option key={c.crude_type_id || `ct-${ci}`} value={c.crude_type_id}>{c.crude_name}</option>)}
        </select>
      </td>
      <td className="px-3 py-2 text-center">
        <input type="checkbox" checked={r.is_final} disabled
          className="w-4 h-4 rounded accent-teal-600 opacity-60" />
      </td>
      <td className="px-3 py-2 text-center">
        <button onClick={() => onDelete(idx)}
          className="text-slate-300 hover:text-red-500 transition-colors"
          title="删除此行">
          <Trash2 className="w-3.5 h-3.5" />
        </button>
      </td>
    </tr>
  )
}
