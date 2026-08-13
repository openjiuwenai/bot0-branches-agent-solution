'use client'

import { useState, useMemo } from 'react'
import { ChevronDown, ChevronRight as ChevRight, Plus, Trash2, ArrowDownToLine, ArrowUpFromLine } from 'lucide-react'
import { cn } from '@/lib/utils'
import { EmptyHint } from './EmptyHint'
import type { SideLineRow, DeviceRow, MaterialOption } from '../types'

type Props = {
  rows: SideLineRow[]
  orig: SideLineRow[]
  devices: DeviceRow[]
  materials: MaterialOption[]
  onChange: (i: number, field: keyof SideLineRow, v: string | number | boolean | null) => void
  onAdd: (deviceId: string, materialType: string) => void
  onDelete: (i: number) => void
}

// 未设置 source_device_id 的侧线归入此分组
const UNSET_DEVICE = ''

export function SideLineTable({ rows, orig, devices, materials, onChange, onAdd, onDelete }: Props) {
  // 默认全折叠：用 expanded 集合管理，用户点击才展开
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  // 按 source_device_id 分组（保留全局索引 idx 供 onChange/onDelete 使用）
  const groups = useMemo(() => {
    const map: Record<string, { row: SideLineRow; idx: number }[]> = {}
    for (let i = 0; i < rows.length; i++) {
      const r = rows[i]
      const did = r.source_device_id || UNSET_DEVICE
      ;(map[did] = map[did] || []).push({ row: r, idx: i })
    }
    return map
  }, [rows])

  const deviceNameMap = useMemo(() => {
    const m: Record<string, string> = {}
    for (const d of devices) m[d.id] = d.name
    return m
  }, [devices])

  // 装置顺序：devices prop 顺序优先，再追加 groups 中存在但 devices 未覆盖的
  const orderedDeviceIds = useMemo(() => {
    const ids = devices.map(d => d.id)
    const seen = new Set(ids)
    for (const did of Object.keys(groups)) {
      if (!seen.has(did)) {
        ids.push(did)
        seen.add(did)
      }
    }
    return ids
  }, [devices, groups])

  const origMap = useMemo(() => new Map(orig.map(r => [r.id, r])), [orig])

  function toggle(did: string) {
    setExpanded(prev => {
      const next = new Set(prev)
      next.has(did) ? next.delete(did) : next.add(did)
      return next
    })
  }

  if (!orderedDeviceIds.length) {
    return (
      <div className="space-y-3">
        <p className="text-sm text-slate-500">
          管理侧线基础信息（名称、物料类型、终端标记）及物料绑定。按装置分组，进料与出料分开配置。收率数据请在「收率管理」页配置。
        </p>
        <EmptyHint text="暂无装置数据" />
      </div>
    )
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-slate-500">
        管理侧线基础信息（名称、物料类型、终端标记）及物料绑定。按装置分组，进料与出料分开配置。点击装置标题展开/折叠。
      </p>
      <div className="rounded-xl border border-slate-200 bg-white overflow-hidden">
        <div className="divide-y divide-slate-100">
          {orderedDeviceIds.map(did => (
            <DeviceGroup key={did || '__unset__'} did={did}
              groupRows={groups[did] || []}
              origMap={origMap}
              deviceName={deviceNameMap[did]}
              materials={materials}
              isOpen={expanded.has(did)}
              toggle={toggle}
              onChange={onChange}
              onAdd={onAdd}
              onDelete={onDelete} />
          ))}
        </div>
      </div>
    </div>
  )
}

function DeviceGroup({ did, groupRows, origMap, deviceName, materials, isOpen, toggle, onChange, onAdd, onDelete }: {
  did: string
  groupRows: { row: SideLineRow; idx: number }[]
  origMap: Map<string, SideLineRow>
  deviceName?: string
  materials: MaterialOption[]
  isOpen: boolean
  toggle: (did: string) => void
  onChange: (i: number, field: keyof SideLineRow, v: string | number | boolean | null) => void
  onAdd: (deviceId: string, materialType: string) => void
  onDelete: (i: number) => void
}) {
  const isEmpty = groupRows.length === 0
  const isUnset = did === UNSET_DEVICE
  const inputRows = groupRows.filter(({ row }) => row.material_type === 'main_feed' || row.material_type === 'auxiliary')
  const outputRows = groupRows.filter(({ row }) => row.material_type === 'product')
  const inputCount = inputRows.length
  const outputCount = outputRows.length

  return (
    <div>
      <button onClick={() => toggle(did)}
        className="w-full flex items-center gap-2 px-4 py-2.5 hover:bg-slate-50/60 transition-colors">
        {isOpen ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
        <span className="text-[13px] font-semibold text-slate-800">
          {isUnset ? '(未设置装置)' : (deviceName || '未知装置')}
        </span>
        {!isUnset && <span className="text-[10px] text-slate-400 font-mono">{did}</span>}
        <span className={cn('text-[11px]', isEmpty ? 'text-amber-500' : 'text-slate-400')}>
          {isEmpty ? '未配置' : `进料${inputCount} / 出料${outputCount}`}
        </span>
      </button>
      {isOpen && (
        <div className="pb-2">
          {/* ── 进料区域 ── */}
          <SubSection
            title="进料" icon={ArrowDownToLine} color="teal"
            rows={inputRows} origMap={origMap} materials={materials}
            onChange={onChange} onDelete={onDelete}
            onAdd={() => onAdd(did, 'main_feed')} />
          {/* ── 出料区域 ── */}
          <SubSection
            title="出料" icon={ArrowUpFromLine} color="emerald"
            rows={outputRows} origMap={origMap} materials={materials}
            onChange={onChange} onDelete={onDelete}
            onAdd={() => onAdd(did, 'product')} />
        </div>
      )}
    </div>
  )
}

function SubSection({ title, icon: Icon, color, rows, origMap, materials, onChange, onDelete, onAdd }: {
  title: string
  icon: typeof ArrowDownToLine
  color: 'teal' | 'emerald'
  rows: { row: SideLineRow; idx: number }[]
  origMap: Map<string, SideLineRow>
  materials: MaterialOption[]
  onChange: (i: number, field: keyof SideLineRow, v: string | number | boolean | null) => void
  onDelete: (i: number) => void
  onAdd: () => void
}) {
  const colorMap = {
    teal: { bg: 'bg-teal-50/30', text: 'text-teal-600', hover: 'hover:text-teal-600 hover:bg-teal-50/40', border: 'border-teal-100' },
    emerald: { bg: 'bg-emerald-50/30', text: 'text-emerald-600', hover: 'hover:text-emerald-600 hover:bg-emerald-50/40', border: 'border-emerald-100' },
  }
  const c = colorMap[color]

  return (
    <div className={cn('mx-3 my-1.5 rounded-lg border', c.border, c.bg)}>
      <div className="flex items-center gap-1.5 px-3 py-1.5">
        <Icon className={cn('w-3.5 h-3.5', c.text)} />
        <span className={cn('text-[12px] font-medium', c.text)}>{title}</span>
        <span className="text-[11px] text-slate-400">({rows.length})</span>
      </div>
      {rows.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-[11px] text-slate-500 border-y border-slate-100/80">
                <th className="text-left font-medium px-3 py-1.5 min-w-[100px]">侧线ID</th>
                <th className="text-left font-medium px-3 py-1.5 min-w-[100px]">名称</th>
                <th className="text-left font-medium px-3 py-1.5 w-24">物料类型</th>
                <th className="text-center font-medium px-3 py-1.5 w-16">终端</th>
                <th className="text-left font-medium px-3 py-1.5 min-w-[160px]">绑定物料</th>
                <th className="text-center font-medium px-3 py-1.5 w-12"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ row: r, idx }) => {
                const o = origMap.get(r.id)
                const dirty = !o
                  || r.name !== o.name
                  || r.source_device_id !== o.source_device_id
                  || r.material_type !== o.material_type
                  || r.is_final !== o.is_final
                  || (r.material_id ?? null) !== (o.material_id ?? null)
                const matName = r.material_id
                  ? materials.find(m => m.material_id === r.material_id)?.material_name ?? null
                  : null
                return (
                  <tr key={idx} className={cn('border-b border-slate-50 last:border-0', dirty && 'bg-amber-50/40')}>
                    <td className="px-3 py-2">
                      <input value={r.id}
                        onChange={e => onChange(idx, 'id', e.target.value)}
                        placeholder="新增行必填"
                        className="w-28 h-8 rounded border border-slate-200 px-2 text-xs font-mono" />
                    </td>
                    <td className="px-3 py-2">
                      <input value={r.name}
                        onChange={e => onChange(idx, 'name', e.target.value)}
                        className="w-32 h-8 rounded border border-slate-200 px-2 text-sm" />
                    </td>
                    <td className="px-3 py-2">
                      <select value={r.material_type}
                        onChange={e => onChange(idx, 'material_type', e.target.value)}
                        className="w-24 h-8 rounded border border-slate-200 px-2 text-sm bg-white">
                        <option value="product">产品</option>
                        <option value="main_feed">主料</option>
                        <option value="auxiliary">辅料</option>
                      </select>
                    </td>
                    <td className="px-3 py-2 text-center">
                      <input type="checkbox" checked={r.is_final}
                        onChange={e => onChange(idx, 'is_final', e.target.checked)}
                        className="w-4 h-4 rounded accent-slate-600" />
                    </td>
                    <td className="px-3 py-2">
                      <select value={r.material_id ?? ''}
                        onChange={e => onChange(idx, 'material_id', e.target.value ? Number(e.target.value) : null)}
                        className={cn('w-full min-w-[160px] h-8 rounded border px-2 text-sm bg-white',
                          matName ? 'border-slate-200' : 'border-amber-300')}>
                        <option value="">未绑定</option>
                        {materials.map(m => (
                          <option key={m.material_id} value={m.material_id}>
                            {m.material_name} (#{m.material_id})
                          </option>
                        ))}
                      </select>
                      {matName && (
                        <span className="text-[10px] text-slate-400 ml-1">{matName}</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-center">
                      <button onClick={() => onDelete(idx)}
                        className="p-1 rounded text-red-400 hover:text-red-600 hover:bg-red-50 transition-colors">
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      <button onClick={onAdd}
        className={cn('w-full flex items-center justify-center gap-1.5 py-1.5 text-[12px] text-slate-400 transition-colors', c.hover)}>
        <Plus className="w-3 h-3" />新增{title}
      </button>
    </div>
  )
}
