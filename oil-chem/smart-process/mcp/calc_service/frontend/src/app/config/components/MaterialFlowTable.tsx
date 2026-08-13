import { useMemo, useState } from 'react'
import { Plus, Trash2, Share2, ChevronDown, ChevronRight, AlertCircle, ArrowDownToLine, ArrowUpFromLine, Star } from 'lucide-react'
import { CardHead } from '@/components/SolveResult'
import { cn } from '@/lib/utils'
import { EmptyHint } from './EmptyHint'
import type { FlowRow, DeviceRow, YieldRow } from '../types'

// ── Add 上下文 ──────────────────────────────────────────────────
export interface AddContext {
  deviceId: string
  flowType: string
  asSource: boolean
  isTank: boolean
}

// ── 装置类型 → 分区定义 ─────────────────────────────────────────
type SectionDef = { flowType: string; label: string }

const DEVICE_SECTIONS: { source: SectionDef[]; target: SectionDef[] } = {
  source: [
    { flowType: 'source_to_tank|final', label: '到罐' },
    { flowType: 'direct',                label: '到其他装置' },
  ],
  target: [
    { flowType: 'tank_to_target', label: '中间罐来料' },
    { flowType: 'input',          label: '外购来料' },
    { flowType: 'direct',         label: '其他装置来料' },
  ],
}

const TANK_SECTIONS: { source: SectionDef[]; target: SectionDef[] } = {
  source: [{ flowType: 'tank_to_target', label: '出料到装置' }],
  target: [{ flowType: 'source_to_tank', label: '装置来料' }],
}

// ── 每种 flow_type + 方向 → 列定义 ──────────────────────────────
type ColDef = { key: string; label: string; w?: string }

const COLS: Record<string, ColDef[]> = {
  // 产出方向 — 到罐（source_to_tank + final 合并）
  'source_to_tank|final': [
    { key: 'source_product', label: '源产品', w: 'min-w-[140px]' },
    { key: 'tank',           label: '目标罐', w: 'min-w-[140px]' },
    { key: 'is_final',       label: '成品', w: 'w-12' },
    { key: 'special_var',    label: '分流标记', w: 'w-16' },
    { key: 'priority',       label: '优先级', w: 'w-16' },
    { key: 'split_ratio',    label: '分配比例', w: 'w-20' },
    { key: 'actions',        label: '', w: 'w-12' },
  ],
  direct_source: [
    { key: 'source_product', label: '源产品', w: 'min-w-[140px]' },
    { key: 'target_device',  label: '目标装置', w: 'min-w-[140px]' },
    { key: 'target_product', label: '目标产品', w: 'min-w-[140px]' },
    { key: 'priority',       label: '优先级', w: 'w-16' },
    { key: 'split_ratio',    label: '分配比例', w: 'w-20' },
    { key: 'actions',        label: '', w: 'w-12' },
  ],
  // 来料方向
  tank_to_target: [
    { key: 'tank',           label: '中间罐', w: 'min-w-[140px]' },
    { key: 'target_product', label: '进料产品', w: 'min-w-[140px]' },
    { key: 'priority',       label: '优先级', w: 'w-16' },
    { key: 'split_ratio',    label: '分配比例', w: 'w-20' },
    { key: 'actions',        label: '', w: 'w-12' },
  ],
  input: [
    { key: 'source_name',    label: '外购名称', w: 'min-w-[140px]' },
    { key: 'target_product', label: '进料产品', w: 'min-w-[140px]' },
    { key: 'priority',       label: '优先级', w: 'w-16' },
    { key: 'split_ratio',    label: '分配比例', w: 'w-20' },
    { key: 'actions',        label: '', w: 'w-12' },
  ],
  direct_target: [
    { key: 'source_device',  label: '来源装置', w: 'min-w-[140px]' },
    { key: 'source_product', label: '来源产品', w: 'min-w-[140px]' },
    { key: 'target_product', label: '本装置进料', w: 'min-w-[140px]' },
    { key: 'priority',       label: '优先级', w: 'w-16' },
    { key: 'split_ratio',    label: '分配比例', w: 'w-20' },
    { key: 'actions',        label: '', w: 'w-12' },
  ],
}

// flow_type 复合 key 直接使用，单类型去掉 direct 的方向后缀
function colKey(flowType: string, asSource: boolean): string {
  if (flowType === 'direct') return asSource ? 'direct_source' : 'direct_target'
  return flowType
}

// ── ID 下拉选择 ─────────────────────────────────────────────────
function IdSelect({ id, name, options, field, onChange, i, disabled, placeholder }: {
  id: string; name?: string
  options: { id: string; name: string }[]
  field: keyof FlowRow
  onChange: (i: number, field: keyof FlowRow, v: string) => void
  i: number
  disabled?: boolean
  placeholder?: string
}) {
  if (disabled) {
    return <span className="text-[11px] text-slate-300 italic">{placeholder || '-'}</span>
  }
  const isEmpty = !options.length
  return (
    <div className="min-w-[120px]">
      <select value={id}
        onChange={e => onChange(i, field, e.target.value)}
        className={cn('w-full text-[12px] rounded border px-1.5 py-1 outline-none bg-white',
          isEmpty ? 'border-amber-300 bg-amber-50/30' : 'border-slate-200')}>
        <option value="">-</option>
        {options.map(o => (
          <option key={o.id} value={o.id}>{o.name} ({o.id})</option>
        ))}
      </select>
      {id && name && !options.find(o => o.id === id) && (
        <span className="block text-[10px] text-amber-400 mt-0.5 truncate">{name} (未匹配)</span>
      )}
      {isEmpty && (
        <span className="flex items-center gap-0.5 text-[10px] text-amber-500 mt-0.5">
          <AlertCircle className="w-2.5 h-2.5" />未配置产品
        </span>
      )}
    </div>
  )
}

// ── Section 小表 ────────────────────────────────────────────────
function SectionTable({ label, flowType, asSource, items, deviceId, isTank, devMap, outputProducts, inputProducts, tanks, units, orig, onChange, onAdd, onDelete }: {
  label: string
  flowType: string
  asSource: boolean
  items: { idx: number; row: FlowRow }[]
  deviceId: string
  isTank: boolean
  devMap: Map<string, DeviceRow>
  outputProducts: Map<string, { id: string; name: string }[]>
  inputProducts: Map<string, { id: string; name: string }[]>
  tanks: DeviceRow[]
  units: DeviceRow[]
  orig: FlowRow[]
  onChange: (i: number, field: keyof FlowRow, v: string | number | boolean) => void
  onAdd: (ctx: AddContext) => void
  onDelete: (i: number) => void
}) {
  const [open, setOpen] = useState(true)
  const ck = colKey(flowType, asSource)
  const cols = COLS[ck] || []
  const devOpts = (devs: DeviceRow[]) => devs.map(d => ({ id: d.id, name: d.name }))

  return (
    <div className="ml-4 border-l border-slate-100">
      {/* section header */}
      <div className="flex items-center gap-2 px-3 py-1.5 cursor-pointer hover:bg-slate-50/60"
        onClick={() => setOpen(!open)}>
        {open ? <ChevronDown className="w-3 h-3 text-slate-400" /> : <ChevronRight className="w-3 h-3 text-slate-400" />}
        <span className="text-[12px] font-medium text-slate-600">{label}</span>
        <span className="text-[10px] text-slate-400">{items.length} 条</span>
        {items.length === 0 && <span className="text-[10px] text-slate-300 italic">（空）</span>}
      </div>

      {/* section table */}
      {open && items.length > 0 && (
        <table className="w-full text-sm ml-4" style={{ maxWidth: 'calc(100% - 2rem)' }}>
          <thead>
            <tr className="text-[11px] text-slate-400 border-b border-slate-50">
              {cols.map(c => (
                <th key={c.key} className={cn('text-left font-medium px-2 py-1', c.w)}>{c.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {items.map(({ row: r, idx: i }) => {
              const o = orig.find(x => x.id === r.id)
              const isDirty = !o
                || r.source_type !== o.source_type || r.source_device_id !== o.source_device_id
                || r.source_product_id !== o.source_product_id || r.source_name !== o.source_name
                || r.tank_id !== o.tank_id || r.target_device_id !== o.target_device_id
                || r.target_product_id !== o.target_product_id
                || r.flow_type !== o.flow_type
                || r.special_var !== o.special_var || r.priority !== o.priority
                || r.is_unique_target !== o.is_unique_target
                || Math.abs(r.split_ratio - o.split_ratio) > 0.01
              return (
                <tr key={i} className={cn('border-b border-slate-50 last:border-0', isDirty && 'bg-amber-50/40')}>
                  {cols.map(c => {
                    switch (c.key) {
                      case 'source_device':
                        return (
                          <td key={c.key} className="px-2 py-1.5">
                            <IdSelect id={r.source_device_id} name={r.source_device_name}
                              options={devOpts(units)} field="source_device_id" onChange={onChange} i={i} />
                          </td>
                        )
                      case 'source_product':
                        return (
                          <td key={c.key} className="px-2 py-1.5">
                            <IdSelect id={r.source_product_id} name={r.source_product_name}
                              options={outputProducts.get(r.source_device_id) || []}
                              field="source_product_id" onChange={onChange} i={i} />
                          </td>
                        )
                      case 'source_name':
                        return (
                          <td key={c.key} className="px-2 py-1.5">
                            <input type="text" value={r.source_name} placeholder="如 曹妃甸原油"
                              onChange={e => onChange(i, 'source_name', e.target.value)}
                              className="w-full font-mono text-[11px] rounded border border-orange-200 bg-orange-50/30 px-2 py-1 outline-none" />
                          </td>
                        )
                      case 'tank':
                        return (
                          <td key={c.key} className="px-2 py-1.5">
                            <IdSelect id={r.tank_id} name={r.tank_name}
                              options={devOpts(tanks)} field="tank_id" onChange={onChange} i={i} />
                          </td>
                        )
                      case 'target_device':
                        return (
                          <td key={c.key} className="px-2 py-1.5">
                            <IdSelect id={r.target_device_id} name={r.target_device_name}
                              options={devOpts(units)} field="target_device_id" onChange={onChange} i={i} />
                          </td>
                        )
                      case 'target_product':
                        return (
                          <td key={c.key} className="px-2 py-1.5">
                            <IdSelect id={r.target_product_id} name={r.target_product_name}
                              options={inputProducts.get(r.target_device_id) || []}
                              field="target_product_id" onChange={onChange} i={i} />
                          </td>
                        )
                      case 'is_final':
                        return (
                          <td key={c.key} className="px-2 py-1.5 text-center">
                            <button onClick={() => onChange(i, 'flow_type', r.flow_type === 'final' ? 'source_to_tank' : 'final')}
                              className="text-[10px] px-1.5 py-0.5 rounded transition-colors">
                              {r.flow_type === 'final'
                                ? <span className="text-green-600 bg-green-50 hover:bg-green-100">成品</span>
                                : <span className="text-slate-400 bg-slate-50 hover:bg-slate-100">中间</span>}
                            </button>
                          </td>
                        )
                      case 'special_var':
                        return (
                          <td key={c.key} className="px-2 py-1.5">
                            <input type="text" value={r.special_var} placeholder="-"
                              onChange={e => onChange(i, 'special_var', e.target.value)}
                              className="w-12 text-center font-mono text-[12px] rounded border border-slate-200 px-1 py-1 outline-none" />
                          </td>
                        )
                      case 'priority':
                        return (
                          <td key={c.key} className="px-2 py-1.5">
                            <div className="flex items-center gap-1 justify-end">
                              <button
                                onClick={() => onChange(i, 'priority', r.priority >= 10 ? 0 : 10)}
                                className="flex-shrink-0 transition-transform hover:scale-110"
                                title={r.priority >= 10 ? '取消主线标记' : '标记为主线'}
                              >
                                <Star className={cn('w-3.5 h-3.5', r.priority >= 10 ? 'fill-amber-400 text-amber-400' : 'text-slate-300')} />
                              </button>
                              <input type="number" step="1" min="0" value={r.priority}
                                onChange={e => onChange(i, 'priority', parseInt(e.target.value) || 0)}
                                className="w-12 text-right font-mono text-sm rounded border border-slate-200 px-2 py-1 outline-none" />
                            </div>
                          </td>
                        )
                      case 'split_ratio':
                        return (
                          <td key={c.key} className="px-2 py-1.5">
                            <input type="number" step="0.01" min="0" max="1" value={r.split_ratio}
                              onChange={e => onChange(i, 'split_ratio', parseFloat(e.target.value) || 0)}
                              className="w-18 text-right font-mono text-sm rounded border border-slate-200 px-2 py-1 focus:border-amber-400 focus:ring-1 focus:ring-amber-300 outline-none" />
                          </td>
                        )
                      case 'actions':
                        return (
                          <td key={c.key} className="px-2 py-1.5 text-center">
                            <button onClick={() => onDelete(i)}
                              className="text-slate-300 hover:text-red-500 transition-colors">
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </td>
                        )
                      default:
                        return <td key={c.key} className="px-2 py-1.5"></td>
                    }
                  })}
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
      {/* add button */}
      {open && (
        <button onClick={() => onAdd({ deviceId, flowType: flowType.split('|')[0], asSource, isTank })}
          className="ml-6 flex items-center gap-1 py-1 px-2 text-[11px] text-slate-400 hover:text-indigo-600 hover:bg-indigo-50/40 rounded transition-colors">
          <Plus className="w-3 h-3" />新增{label}
        </button>
      )}
    </div>
  )
}

// ── 主组件 ──────────────────────────────────────────────────────
export function MaterialFlowTable({ rows, orig, devices, products, onChange, onAdd, onDelete }: {
  rows: FlowRow[]
  orig: FlowRow[]
  devices: DeviceRow[]
  products: YieldRow[]
  onChange: (i: number, field: keyof FlowRow, v: string | number | boolean) => void
  onAdd: (ctx: AddContext) => void
  onDelete: (i: number) => void
}) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  // 子区默认折叠，expanded 记录已展开的
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const toggle = (k: string) => setCollapsed(prev => {
    const next = new Set(prev)
    if (next.has(k)) next.delete(k); else next.add(k)
    return next
  })
  const toggleSub = (k: string) => setExpanded(prev => {
    const next = new Set(prev)
    if (next.has(k)) next.delete(k); else next.add(k)
    return next
  })

  const devMap = useMemo(() => new Map(devices.map(d => [d.id, d])), [devices])
  const tanks = useMemo(() => devices.filter(d => d.type === 'tank'), [devices])
  const units = useMemo(() => devices.filter(d => d.type !== 'tank'), [devices])

  // 产品查找表：按装置分组
  // outputProducts: 仅 product 类型（源产品下拉用）
  // inputProducts: 仅 main_feed/auxiliary 类型（目标产品下拉用）
  const { outputProducts, inputProducts } = useMemo(() => {
    const out = new Map<string, { id: string; name: string }[]>()
    const inp = new Map<string, { id: string; name: string }[]>()
    for (const p of products) {
      const pid = p.id.split('~')[0]
      const target = p.material_type === 'product' ? out : inp
      const list = target.get(p.source_device_id) || []
      if (!list.find(x => x.id === pid)) list.push({ id: pid, name: p.name })
      target.set(p.source_device_id, list)
    }
    return { outputProducts: out, inputProducts: inp }
  }, [products])

  // 已配置流向的产品集合
  const configuredOutput = useMemo(() => {
    const s = new Set<string>()
    for (const r of rows) {
      if (r.source_product_id && ['source_to_tank', 'final', 'direct'].includes(r.flow_type)) {
        s.add(r.source_product_id)
      }
    }
    return s
  }, [rows])

  const configuredInput = useMemo(() => {
    const s = new Set<string>()
    for (const r of rows) {
      if (r.target_product_id && ['tank_to_target', 'input', 'direct'].includes(r.flow_type)) {
        s.add(r.target_product_id)
      }
    }
    return s
  }, [rows])

  // 每个装置未配置流向的产品：{ deviceId: { output: Product[], input: Product[] } }
  const unconfiguredByDevice = useMemo(() => {
    const map = new Map<string, { output: { id: string; name: string }[]; input: { id: string; name: string }[] }>()
    for (const p of products) {
      const pid = p.id.split('~')[0]
      const devId = p.source_device_id
      if (!devId) continue
      // 只处理装置（非罐），罐的流向是只读的
      if (devMap.get(devId)?.type === 'tank') continue
      if (!map.has(devId)) map.set(devId, { output: [], input: [] })
      const g = map.get(devId)!
      if (p.material_type === 'product' && !configuredOutput.has(pid)) {
        if (!g.output.find(x => x.id === pid)) g.output.push({ id: pid, name: p.name })
      }
      if ((p.material_type === 'main_feed' || p.material_type === 'auxiliary') && !configuredInput.has(pid)) {
        if (!g.input.find(x => x.id === pid)) g.input.push({ id: pid, name: p.name })
      }
    }
    return map
  }, [products, devMap, configuredOutput, configuredInput])

  // 构建 装置 → { asSource, asTarget } 分组
  const deviceGroups = useMemo(() => {
    const map = new Map<string, {
      asSource: Map<string, { idx: number; row: FlowRow }[]>
      asTarget: Map<string, { idx: number; row: FlowRow }[]>
    }>()

    const getGroup = (id: string) => {
      if (!map.has(id)) map.set(id, { asSource: new Map(), asTarget: new Map() })
      return map.get(id)!
    }

    // 先补充所有非 tank 装置（含无流向的）
    for (const u of units) {
      getGroup(u.id)
    }

    rows.forEach((row, idx) => {
      // 作为源装置（source_device_id 出料）
      if (row.source_device_id && ['source_to_tank', 'final', 'direct'].includes(row.flow_type)) {
        const g = getGroup(row.source_device_id)
        // source_to_tank 和 final 合并为复合 key
        const ft = (row.flow_type === 'source_to_tank' || row.flow_type === 'final')
          ? 'source_to_tank|final'
          : row.flow_type
        if (!g.asSource.has(ft)) g.asSource.set(ft, [])
        g.asSource.get(ft)!.push({ idx, row })
      }
      if (row.tank_id && row.flow_type === 'tank_to_target') {
        const g = getGroup(row.tank_id)
        if (!g.asSource.has('tank_to_target')) g.asSource.set('tank_to_target', [])
        g.asSource.get('tank_to_target')!.push({ idx, row })
      }
      // 作为目的装置（target_device_id 或 tank_id 入料）
      if (row.target_device_id && ['tank_to_target', 'input', 'direct'].includes(row.flow_type)) {
        const g = getGroup(row.target_device_id)
        const ft = row.flow_type
        if (!g.asTarget.has(ft)) g.asTarget.set(ft, [])
        g.asTarget.get(ft)!.push({ idx, row })
      }
      if (row.tank_id && row.flow_type === 'source_to_tank') {
        const g = getGroup(row.tank_id)
        if (!g.asTarget.has('source_to_tank')) g.asTarget.set('source_to_tank', [])
        g.asTarget.get('source_to_tank')!.push({ idx, row })
      }
    })

    // 只保留装置（非 tank），按名称排序
    return Array.from(map.entries())
      .filter(([id]) => devMap.get(id)?.type !== 'tank')
      .sort((a, b) => {
        const na = devMap.get(a[0])?.name || a[0]
        const nb = devMap.get(b[0])?.name || b[0]
        return na.localeCompare(nb)
      })
  }, [rows, devMap, units])

  // 罐分组：只读展示入罐（source_to_tank）和出罐（tank_to_target）
  const tankGroups = useMemo(() => {
    const map = new Map<string, { inflow: { idx: number; row: FlowRow }[]; outflow: { idx: number; row: FlowRow }[] }>()
    const getGroup = (id: string) => {
      if (!map.has(id)) map.set(id, { inflow: [], outflow: [] })
      return map.get(id)!
    }
    rows.forEach((row, idx) => {
      if (row.tank_id && (row.flow_type === 'source_to_tank' || row.flow_type === 'final')) {
        getGroup(row.tank_id).inflow.push({ idx, row })
      }
      if (row.tank_id && row.flow_type === 'tank_to_target') {
        getGroup(row.tank_id).outflow.push({ idx, row })
      }
    })
    return Array.from(map.entries())
      .filter(([id]) => devMap.get(id)?.type === 'tank')
      .sort((a, b) => {
        const na = devMap.get(a[0])?.name || a[0]
        const nb = devMap.get(b[0])?.name || b[0]
        return na.localeCompare(nb)
      })
  }, [rows, devMap])

  if (!rows.length) return <EmptyHint text="暂无流向数据" />

  const groupName = (id: string) => {
    const dev = devMap.get(id)
    return dev ? `${dev.name}（${id}）` : id
  }

  // 传递给 SectionTable 的公共 props
  const sectionProps = {
    devMap, outputProducts, inputProducts, tanks, units, orig, onChange, onAdd, onDelete,
  }

  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60">
        <CardHead icon={Share2} title="物料流向" accent="from-indigo-500 to-purple-600"
          hint={`共 ${rows.length} 条流向边 · ${deviceGroups.length} 个装置 · 按装置→方向→类型分层配置`} />
      </div>
      <div className="overflow-x-auto max-h-[70vh] overflow-y-auto">
        {deviceGroups.map(([deviceId, group]) => {
          const dev = devMap.get(deviceId)
          const isTank = dev?.type === 'tank'
          const sections = isTank ? TANK_SECTIONS : DEVICE_SECTIONS
          const sourceCount = Array.from(group.asSource.values()).reduce((s, v) => s + v.length, 0)
          const targetCount = Array.from(group.asTarget.values()).reduce((s, v) => s + v.length, 0)
          const totalCount = sourceCount + targetCount
          const uc = unconfiguredByDevice.get(deviceId)
          const ucCount = uc ? uc.output.length + uc.input.length : 0

          return (
            <div key={deviceId} className="border-b border-slate-50">
              {/* 装置 header */}
              <div className="flex items-center gap-2 px-3 py-2 cursor-pointer hover:bg-slate-50/60 bg-slate-50/30"
                onClick={() => toggle(deviceId)}>
                {collapsed.has(deviceId)
                  ? <ChevronRight className="w-4 h-4 text-slate-400" />
                  : <ChevronDown className="w-4 h-4 text-slate-400" />}
                <span className="font-medium text-[13px] text-slate-700">{groupName(deviceId)}</span>
                {isTank && <span className="text-[10px] text-cyan-500 bg-cyan-50 px-1.5 py-0.5 rounded">储罐</span>}
                <span className="text-[11px] text-slate-400">{totalCount} 条流向</span>
                {ucCount > 0 && (
                  <span className="text-[10px] text-amber-600 bg-amber-50 px-1.5 py-0.5 rounded flex items-center gap-0.5">
                    <AlertCircle className="w-2.5 h-2.5" />{ucCount} 个产品待配置
                  </span>
                )}
              </div>

              {/* 装置内容 */}
              {!collapsed.has(deviceId) && (
                <div className="py-1">
                  {/* 产出流向 */}
                  <div className="ml-2">
                    <div className="flex items-center gap-1.5 px-3 py-1 cursor-pointer hover:bg-slate-50/40 flex-wrap"
                      onClick={() => toggleSub(`${deviceId}__source`)}>
                      {expanded.has(`${deviceId}__source`)
                        ? <ChevronDown className="w-3 h-3 text-slate-400" />
                        : <ChevronRight className="w-3 h-3 text-slate-400" />}
                      <ArrowUpFromLine className="w-3 h-3 text-indigo-400" />
                      <span className="text-[12px] font-medium text-slate-600">产出流向</span>
                      <span className="text-[10px] text-slate-400">{sourceCount} 条</span>
                      {uc && uc.output.length > 0 && (
                        <span className="text-[10px] text-amber-600 bg-amber-50 px-1.5 py-0.5 rounded flex items-center gap-0.5">
                          <AlertCircle className="w-2.5 h-2.5" />待配置: {uc.output.map(p => p.name).join('、')}
                        </span>
                      )}
                    </div>
                    {expanded.has(`${deviceId}__source`) && sections.source.map(sec => (
                      <SectionTable key={sec.flowType} label={sec.label} flowType={sec.flowType} asSource
                        deviceId={deviceId} isTank={isTank}
                        items={group.asSource.get(sec.flowType) || []}
                        {...sectionProps} />
                    ))}
                  </div>

                  {/* 原料来源 */}
                  <div className="ml-2 mt-1">
                    <div className="flex items-center gap-1.5 px-3 py-1 cursor-pointer hover:bg-slate-50/40 flex-wrap"
                      onClick={() => toggleSub(`${deviceId}__target`)}>
                      {expanded.has(`${deviceId}__target`)
                        ? <ChevronDown className="w-3 h-3 text-slate-400" />
                        : <ChevronRight className="w-3 h-3 text-slate-400" />}
                      <ArrowDownToLine className="w-3 h-3 text-teal-400" />
                      <span className="text-[12px] font-medium text-slate-600">原料来源</span>
                      <span className="text-[10px] text-slate-400">{targetCount} 条</span>
                      {uc && uc.input.length > 0 && (
                        <span className="text-[10px] text-amber-600 bg-amber-50 px-1.5 py-0.5 rounded flex items-center gap-0.5">
                          <AlertCircle className="w-2.5 h-2.5" />待配置: {uc.input.map(p => p.name).join('、')}
                        </span>
                      )}
                    </div>
                    {expanded.has(`${deviceId}__target`) && sections.target.map(sec => (
                      <SectionTable key={sec.flowType} label={sec.label} flowType={sec.flowType} asSource={false}
                        deviceId={deviceId} isTank={isTank}
                        items={group.asTarget.get(sec.flowType) || []}
                        {...sectionProps} />
                    ))}
                  </div>
                </div>
              )}
            </div>
          )
        })}

        {/* 罐分组 — 只读展示，不可编辑 */}
        {tankGroups.length > 0 && (
          <div className="border-t-2 border-slate-100">
            <div className="flex items-center gap-2 px-4 py-2 bg-cyan-50/40">
              <span className="text-[13px] font-medium text-cyan-700">储罐流向（只读）</span>
              <span className="text-[11px] text-slate-400">入罐和出罐条目由装置侧配置自动关联</span>
            </div>
            {tankGroups.map(([tankId, group]) => {
              const tank = devMap.get(tankId)
              const tankName = tank ? `${tank.name}（${tankId}）` : tankId
              const totalCount = group.inflow.length + group.outflow.length
              return (
                <div key={tankId} className="border-b border-slate-50">
                  <div className="flex items-center gap-2 px-3 py-2 cursor-pointer hover:bg-slate-50/60 bg-slate-50/30"
                    onClick={() => toggle(`tank_${tankId}`)}>
                    {collapsed.has(`tank_${tankId}`)
                      ? <ChevronRight className="w-4 h-4 text-slate-400" />
                      : <ChevronDown className="w-4 h-4 text-slate-400" />}
                    <span className="font-medium text-[13px] text-slate-700">{tankName}</span>
                    <span className="text-[10px] text-cyan-500 bg-cyan-50 px-1.5 py-0.5 rounded">储罐</span>
                    <span className="text-[11px] text-slate-400">{totalCount} 条</span>
                  </div>
                  {!collapsed.has(`tank_${tankId}`) && (
                    <div className="py-1">
                      {/* 入罐 */}
                      {group.inflow.length > 0 && (
                        <div className="ml-4 border-l border-slate-100">
                          <div className="flex items-center gap-2 px-3 py-1.5">
                            <ArrowDownToLine className="w-3 h-3 text-teal-400" />
                            <span className="text-[12px] font-medium text-slate-600">入罐（装置来料）</span>
                            <span className="text-[10px] text-slate-400">{group.inflow.length} 条</span>
                          </div>
                          <table className="w-full text-sm ml-4" style={{ maxWidth: 'calc(100% - 2rem)' }}>
                            <thead>
                              <tr className="text-[11px] text-slate-400 border-b border-slate-50">
                                <th className="text-left font-medium px-2 py-1 min-w-[140px]">源装置</th>
                                <th className="text-left font-medium px-2 py-1 min-w-[140px]">源产品</th>
                                <th className="text-left font-medium px-2 py-1 w-16">分流标记</th>
                                <th className="text-right font-medium px-2 py-1 w-20">分配比例</th>
                              </tr>
                            </thead>
                            <tbody>
                              {group.inflow.map(({ row: r, idx: i }) => (
                                <tr key={i} className="border-b border-slate-50 last:border-0">
                                  <td className="px-2 py-1.5 text-[12px] text-slate-600">{r.source_device_name || r.source_device_id || '-'}</td>
                                  <td className="px-2 py-1.5 text-[12px] text-slate-600">{r.source_product_name || r.source_product_id || '-'}</td>
                                  <td className="px-2 py-1.5 text-[12px] font-mono text-slate-500">{r.special_var || '-'}</td>
                                  <td className="px-2 py-1.5 text-[12px] font-mono text-slate-500 text-right">{r.split_ratio.toFixed(2)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                      {/* 出罐 */}
                      {group.outflow.length > 0 && (
                        <div className="ml-4 border-l border-slate-100 mt-1">
                          <div className="flex items-center gap-2 px-3 py-1.5">
                            <ArrowUpFromLine className="w-3 h-3 text-indigo-400" />
                            <span className="text-[12px] font-medium text-slate-600">出罐（到装置）</span>
                            <span className="text-[10px] text-slate-400">{group.outflow.length} 条</span>
                          </div>
                          <table className="w-full text-sm ml-4" style={{ maxWidth: 'calc(100% - 2rem)' }}>
                            <thead>
                              <tr className="text-[11px] text-slate-400 border-b border-slate-50">
                                <th className="text-left font-medium px-2 py-1 min-w-[140px]">目标装置</th>
                                <th className="text-left font-medium px-2 py-1 min-w-[140px]">进料产品</th>
                                <th className="text-right font-medium px-2 py-1 w-20">分配比例</th>
                              </tr>
                            </thead>
                            <tbody>
                              {group.outflow.map(({ row: r, idx: i }) => {
                                return (
                                  <tr key={i} className="border-b border-slate-50 last:border-0">
                                    <td className="px-2 py-1.5 text-[12px] text-slate-600">{r.target_device_name || r.target_device_id || '-'}</td>
                                    <td className="px-2 py-1.5 text-[12px] text-slate-600">{r.target_product_name || r.target_product_id || '-'}</td>
                                    <td className="px-2 py-1.5 text-[12px] font-mono text-slate-500 text-right">{r.split_ratio.toFixed(2)}</td>
                                  </tr>
                                )
                              })}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
