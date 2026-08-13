'use client'

import { useState, useEffect, useMemo } from 'react'
import {
  Loader2, Save, AlertTriangle, CheckCircle2,
  Package, Droplets, Droplet, Flame,
  Cog, Beaker, Plus, Archive, Share2,
  ChevronUp, ChevronDown, GitBranch,
} from 'lucide-react'
import { normalizeMonth } from '@/components/SolveResult'
import { cn } from '@/lib/utils'

import {
  type Tab, type ProductPrice, type CrudeCost, type EnergyRow,
  type MaterialOption, type DeviceRow, type FlowRow, type YieldRow,
  type CrudeTypeRow, type SideLineRow, type MappingRow,
  FALLBACK_MONTHS,
} from './types'
import { ProductPriceTable } from './components/ProductPriceTable'
import { CrudeCostTable } from './components/CrudeCostTable'
import { EnergyTable } from './components/EnergyTable'
import { SideLineTable } from './components/SideLineTable'
import { UnitTable } from './components/UnitTable'
import { TankTable } from './components/TankTable'
import { MaterialFlowTable } from './components/MaterialFlowTable'
import { FlowDiagram } from './components/FlowDiagram'
import { YieldTable } from './components/YieldTable'
import { CrudeTypeTable } from './components/CrudeTypeTable'

// ── 基础配置页：管理油种 / 装置 / 储罐 / 收率 / 物料流向 / 产品映射 / 产品价格 / 原油成本 / 能耗系数 ──

const TABS: { key: Tab; label: string; icon: typeof Package }[] = [
  { key: 'crude_type', label: '油种管理', icon: Droplet },
  { key: 'unit', label: '装置管理', icon: Cog },
  { key: 'tank', label: '储罐管理', icon: Archive },
  { key: 'side_line', label: '侧线配置', icon: GitBranch },
  { key: 'yield', label: '收率管理', icon: Beaker },
  { key: 'flow', label: '物料流向', icon: Share2 },
  { key: 'product', label: '产品价格', icon: Package },
  { key: 'crude', label: '原油成本', icon: Droplets },
  { key: 'energy', label: '能耗系数', icon: Flame },
]

export default function ConfigPage() {
  const [tab, setTab] = useState<Tab>('crude_type')
  const [headerCollapsed, setHeaderCollapsed] = useState(false)
  const [month, setMonth] = useState('2026-01')
  const [months, setMonths] = useState(FALLBACK_MONTHS)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [toast, setToast] = useState<{ ok: boolean; msg: string } | null>(null)

  // 三类数据 + 编辑态（与原始值对比判定 dirty）
  const [products, setProducts] = useState<ProductPrice[]>([])
  const [crudes, setCrudes] = useState<CrudeCost[]>([])
  const [energy, setEnergy] = useState<EnergyRow[]>([])
  const [productsOrig, setProductsOrig] = useState<ProductPrice[]>([])
  const [crudesOrig, setCrudesOrig] = useState<CrudeCost[]>([])
  const [energyOrig, setEnergyOrig] = useState<EnergyRow[]>([])
  // ── 侧线配置 ──
  const [sideLines, setSideLines] = useState<SideLineRow[]>([])
  const [sideLinesOrig, setSideLinesOrig] = useState<SideLineRow[]>([])
  const [sideLineDevices, setSideLineDevices] = useState<DeviceRow[]>([])
  const [materials, setMaterials] = useState<MaterialOption[]>([])
  // ── 装置管理（独立状态，不与储罐共用） ──
  const [unitDevices, setUnitDevices] = useState<DeviceRow[]>([])
  const [unitDevicesOrig, setUnitDevicesOrig] = useState<DeviceRow[]>([])
  // ── 储罐管理（独立状态） ──
  const [tankDevices, setTankDevices] = useState<DeviceRow[]>([])
  const [tankDevicesOrig, setTankDevicesOrig] = useState<DeviceRow[]>([])
  // ── 储罐月初容量 ──
  const [tankMonthlyInitials, setTankMonthlyInitials] = useState<Record<string, number>>({})
  const [tankMonthlyInitialsOrig, setTankMonthlyInitialsOrig] = useState<Record<string, number>>({})
  const [selectedMonth, setSelectedMonth] = useState<string>(() => {
    const d = new Date()
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    // 限定在 2026 年范围内
    return y === 2026 ? `2026-${m}` : '2026-01'
  })
  // ── 物流管理 ──
  const [flows, setFlows] = useState<FlowRow[]>([])
  const [flowsOrig, setFlowsOrig] = useState<FlowRow[]>([])
  const [flowDevices, setFlowDevices] = useState<DeviceRow[]>([])
  const [flowProducts, setFlowProducts] = useState<YieldRow[]>([])
  // ── 收率管理 ──
  const [yields, setYields] = useState<YieldRow[]>([])
  const [yieldsOrig, setYieldsOrig] = useState<YieldRow[]>([])
  const [yieldTankIds, setYieldTankIds] = useState<Set<string>>(new Set())
  const [yieldAllDevices, setYieldAllDevices] = useState<DeviceRow[]>([])
  const [crudeTypes, setCrudeTypes] = useState<{crude_type_id: string; crude_name: string; crude_code: string; is_default: boolean}[]>([])
  // ── 油种管理 ──
  const [crudeTypeRows, setCrudeTypeRows] = useState<CrudeTypeRow[]>([])
  const [crudeTypeOrig, setCrudeTypeOrig] = useState<CrudeTypeRow[]>([])
  const [crudeFilter, setCrudeFilter] = useState('')

  // ── 挂载时拉月份列表（同排产/预测页）──
  useEffect(() => {
    let cancelled = false
    fetch('/api/scheduling/plans', { cache: 'no-store' })
      .then(async r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json() })
      .then(d => {
        if (cancelled) return
        const plans: { plan_id: string; plan_month?: string }[] = d?.plans || []
        const list = plans
          .map(p => normalizeMonth(p.plan_month || p.plan_id))
          .filter((m): m is { key: string; label: string } => m !== null)
        if (list.length) {
          setMonths(list)
          if (!list.some(m => m.key === month)) setMonth(list[0].key)
        }
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [])

  // ── 拉数据：tab 切换或月份变化时 ──
  useEffect(() => {
    let cancelled = false
    setLoading(true); setError(null)
    if (tab === 'side_line') {
      // 侧线配置页：直接调用 /api/side_lines 获取 side_lines 表数据 + 物料列表
      // 装置下拉从 /api/units + /api/tanks 合并
      Promise.all([
        fetch('/api/side_lines', { cache: 'no-store' }).then(r => r.json()),
        fetch('/api/units', { cache: 'no-store' }).then(r => r.json()),
        fetch('/api/tanks', { cache: 'no-store' }).then(r => r.json()),
      ]).then(([sd, ud, td]) => {
        if (cancelled) return
        const rows: SideLineRow[] = (sd.side_lines || []).map((s: Record<string, unknown>) => ({
          id: s.side_line_id as string,
          name: (s.name as string) || '',
          source_device_id: (s.source_device_id as string) || '',
          material_type: (s.material_type as string) || 'product',
          is_final: Boolean(s.is_final),
          material_id: (s.material_id as number | null) ?? null,
          material_name: (s.material_name as string | null) ?? null,
        }))
        setSideLines(rows); setSideLinesOrig(rows.map(r => ({ ...r })))
        setMaterials((sd.materials || []).map((m: Record<string, unknown>) => ({
          material_id: m.id as number,
          material_name: m.name as string,
        })))
        const unitRows: DeviceRow[] = ud.units || ud.devices || []
        const tankRows: DeviceRow[] = td.tanks || td.devices || []
        setSideLineDevices(unitRows)  // 储罐不再有侧线，侧线配置页只显示装置
      })
        .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '加载失败') })
        .finally(() => { if (!cancelled) setLoading(false) })
    } else if (tab === 'unit') {
      fetch('/api/units', { cache: 'no-store' })
        .then(async r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json() })
        .then(d => {
          if (cancelled) return
          const rows: DeviceRow[] = d.units || d.devices || []
          setUnitDevices(rows); setUnitDevicesOrig(rows.map(r => ({ ...r })))
        })
        .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '加载失败') })
        .finally(() => { if (!cancelled) setLoading(false) })
    } else if (tab === 'tank') {
      Promise.all([
        fetch('/api/tanks', { cache: 'no-store' }).then(r => r.json()),
        fetch(`/api/tank_monthly_initial?year_month=${selectedMonth}`, { cache: 'no-store' }).then(r => r.json()),
        fetch('/api/materials', { cache: 'no-store' }).then(r => r.json()),
      ]).then(([d, mi, md]) => {
        if (cancelled) return
        // /api/tanks 返回的 _device_row 格式已与 DeviceRow 对齐，直接使用
        const rows: DeviceRow[] = d.tanks || d.devices || []
        setTankDevices(rows); setTankDevicesOrig(rows.map(r => ({ ...r })))
        setMaterials((md.materials || []).map((m: Record<string, unknown>) => ({
          material_id: m.id as number,
          material_name: m.name as string,
        })))
        const initials: Record<string, number> = {}
        for (const r of (mi?.data || [])) {
          initials[r.tank_id] = r.initial_capacity
        }
        setTankMonthlyInitials(initials)
        setTankMonthlyInitialsOrig({ ...initials })
      })
        .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '加载失败') })
        .finally(() => { if (!cancelled) setLoading(false) })
    } else if (tab === 'flow') {
      Promise.all([
        fetch('/api/material_flows', { cache: 'no-store' }).then(r => r.json()),
        fetch('/api/units', { cache: 'no-store' }).then(r => r.json()),
        fetch('/api/tanks', { cache: 'no-store' }).then(r => r.json()),
        fetch('/api/products', { cache: 'no-store' }).then(r => r.json()),
      ]).then(([fd, ud, td, pd]) => {
        if (cancelled) return
        const rows: FlowRow[] = fd.flows || fd.material_flows || []
        setFlows(rows); setFlowsOrig(rows.map(r => ({ ...r })))
        const unitRows: DeviceRow[] = ud.units || ud.devices || []
        const tankRows: DeviceRow[] = td.tanks || td.devices || []
        setFlowDevices([...unitRows, ...tankRows])
        setFlowProducts(pd.products || [])
      })
        .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '加载失败') })
        .finally(() => { if (!cancelled) setLoading(false) })
    } else if (tab === 'yield') {
      Promise.all([
        fetch('/api/products', { cache: 'no-store' }).then(r => r.json()),
        fetch('/api/units', { cache: 'no-store' }).then(r => r.json()),
        fetch('/api/crude_types', { cache: 'no-store' }).then(r => r.json()),
      ]).then(([pd, ud, cd]) => {
        if (cancelled) return
        const rows: YieldRow[] = pd.products || []
        setYields(rows); setYieldsOrig(rows.map(r => ({ ...r })))
        // 储罐不再有侧线/收率，收率管理只展示装置
        setYieldTankIds(new Set())
        const unitRows: DeviceRow[] = ud.units || ud.devices || []
        setYieldAllDevices(unitRows)
        // 油种列表
        const ctList = cd.data || cd.crude_types || []
        setCrudeTypes(Array.isArray(ctList) ? ctList : [])
      })
        .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '加载失败') })
        .finally(() => { if (!cancelled) setLoading(false) })
    } else if (tab === 'crude_type') {
      fetch('/api/crude_types', { cache: 'no-store' })
        .then(async r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json() })
        .then(d => {
          if (cancelled) return
          const rows: CrudeTypeRow[] = (d.data || []).map((r: Record<string, unknown>) => ({
            crude_type_id: r.crude_type_id as string,
            crude_name: r.crude_name as string,
            crude_code: r.crude_code as string || '',
            aliases: r.aliases as string[] || [],
            is_active: r.is_active as boolean ?? true,
            is_default: r.is_default as boolean ?? false,
            sort_order: r.sort_order as number ?? 0,
            note: (r.note as string) || '',
          }))
          setCrudeTypeRows(rows); setCrudeTypeOrig(rows.map(r => ({ ...r })))
        })
        .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '加载失败') })
        .finally(() => { if (!cancelled) setLoading(false) })
    } else {
      const url = tab === 'product'
        ? `/api/price_cost/products?month=${month}`
        : tab === 'crude'
          ? '/api/price_cost/crude'
          : '/api/energy_consumptions'
      fetch(url, { cache: 'no-store' })
        .then(async r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json() })
        .then(d => {
          if (cancelled) return
          if (tab === 'product') {
            const rows: ProductPrice[] = d.data || []
            setProducts(rows); setProductsOrig(rows.map(r => ({ ...r })))
          } else if (tab === 'crude') {
            const rows: CrudeCost[] = d.data || []
            setCrudes(rows); setCrudesOrig(rows.map(r => ({ ...r })))
          } else {
            const rows: EnergyRow[] = d.energy_consumptions || []
            setEnergy(rows); setEnergyOrig(rows.map(r => ({ ...r })))
          }
        })
        .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '加载失败') })
        .finally(() => { if (!cancelled) setLoading(false) })
    }
    return () => { cancelled = true }
  }, [tab, month])

  // ── dirty 判定 ──
  const productsDirty = useMemo(
    () => products.some((p, i) => Math.abs(p.price - (productsOrig[i]?.price ?? p.price)) > 0.001),
    [products, productsOrig]
  )
  const crudesDirty = useMemo(
    () => crudes.some((c, i) => Math.abs(c.cost - (crudesOrig[i]?.cost ?? c.cost)) > 0.001),
    [crudes, crudesOrig]
  )
  const energyDirty = useMemo(
    () => energy.some((e, i) =>
      Math.abs(e.consumption_per_ton - (energyOrig[i]?.consumption_per_ton ?? e.consumption_per_ton)) > 0.0001
      || Math.abs(e.price_per_unit - (energyOrig[i]?.price_per_unit ?? e.price_per_unit)) > 0.001),
    [energy, energyOrig]
  )
  const sideLinesDirty = useMemo(() => {
    const origMap = new Map(sideLinesOrig.map(s => [s.id, s]))
    const currentIds = new Set(sideLines.map(s => s.id))
    const hasNewOrChanged = sideLines.some(s => {
      const o = origMap.get(s.id)
      if (!o) return true
      return s.name !== o.name || s.source_device_id !== o.source_device_id
        || s.material_type !== o.material_type || s.is_final !== o.is_final
        || (s.material_id ?? null) !== (o.material_id ?? null)
    })
    const hasDeleted = sideLinesOrig.some(s => s.id && !currentIds.has(s.id))
    return hasNewOrChanged || hasDeleted
  }, [sideLines, sideLinesOrig])
  const unitDevicesDirty = useMemo(() => {
    const origMap = new Map(unitDevicesOrig.map(d => [d.id, d]))
    const currentIds = new Set(unitDevices.map(d => d.id))
    const hasNewOrChanged = unitDevices.some(d => {
      const o = origMap.get(d.id)
      if (!o) return true
      return d.name !== o.name || d.type !== o.type
        || Math.abs(d.safety_stock_thrd - o.safety_stock_thrd) > 0.01
        || Math.abs(d.low_safety_thrd - o.low_safety_thrd) > 0.01
        || Math.abs(d.current_capacity - o.current_capacity) > 0.01
        || Math.abs(d.refinery_unit_load_percent - o.refinery_unit_load_percent) > 0.01
        || (d.backend_device_id ?? null) !== (o.backend_device_id ?? null)
        || (d.enabled ?? true) !== (o.enabled ?? true)
    })
    const hasDeleted = unitDevicesOrig.some(d => d.id && !currentIds.has(d.id))
    return hasNewOrChanged || hasDeleted
  }, [unitDevices, unitDevicesOrig])
  const tankDevicesDirty = useMemo(() => {
    const origMap = new Map(tankDevicesOrig.map(d => [d.id, d]))
    const currentIds = new Set(tankDevices.map(d => d.id))
    const hasNewOrChanged = tankDevices.some(d => {
      const o = origMap.get(d.id)
      if (!o) return true
      return d.name !== o.name
        || Math.abs(d.safety_stock_thrd - o.safety_stock_thrd) > 0.01
        || Math.abs(d.low_safety_thrd - o.low_safety_thrd) > 0.01
        || Math.abs(d.current_capacity - o.current_capacity) > 0.01
        || (d.tank_category ?? null) !== (o.tank_category ?? null)
        || (d.enabled ?? true) !== (o.enabled ?? true)
    })
    const hasDeleted = tankDevicesOrig.some(d => d.id && !currentIds.has(d.id))
    const initialsDirty = Object.keys(tankMonthlyInitials).some(k =>
      (tankMonthlyInitials[k] ?? 0) !== (tankMonthlyInitialsOrig[k] ?? 0))
    return hasNewOrChanged || hasDeleted || initialsDirty
  }, [tankDevices, tankDevicesOrig, tankMonthlyInitials, tankMonthlyInitialsOrig])
  const flowsDirty = useMemo(() => {
    const origMap = new Map(flowsOrig.map(f => [f.id, f]))
    const currentIds = new Set(flows.map(f => f.id))
    const hasNewOrChanged = flows.some(f => {
      const o = origMap.get(f.id)
      if (!o) return true
      return f.source_type !== o.source_type || f.source_device_id !== o.source_device_id
        || f.source_product_id !== o.source_product_id || f.source_name !== o.source_name
        || f.tank_id !== o.tank_id || f.target_device_id !== o.target_device_id
        || f.target_product_id !== o.target_product_id
        || f.flow_type !== o.flow_type || f.material_role !== o.material_role
        || f.special_var !== o.special_var || f.priority !== o.priority
        || f.is_unique_target !== o.is_unique_target
        || Math.abs(f.split_ratio - o.split_ratio) > 0.01
    })
    const hasDeleted = flowsOrig.some(f => f.id && !currentIds.has(f.id))
    return hasNewOrChanged || hasDeleted
  }, [flows, flowsOrig])
  const yieldsDirty = useMemo(() => {
    const origMap = new Map(yieldsOrig.map(y => [y.id, y]))
    const currentIds = new Set(yields.map(y => y.id))
    const hasNewOrChanged = yields.some(y => {
      const o = origMap.get(y.id)
      if (!o) return true
      // 收率页仅收率字段 + 油种参与 dirty 判定，身份字段在侧线配置页管理
      return y.crude_type !== o.crude_type
        || Math.abs(y.yield_rate - o.yield_rate) > 0.01
        || Math.abs(y.yield_rate_2 - o.yield_rate_2) > 0.01
        || Math.abs(y.yield_rate_3 - o.yield_rate_3) > 0.01
        || Math.abs(y.yield_rate_4 - o.yield_rate_4) > 0.01
    })
    const hasDeleted = yieldsOrig.some(y => y.id && !currentIds.has(y.id))
    return hasNewOrChanged || hasDeleted
  }, [yields, yieldsOrig])
  const crudeTypeDirty = useMemo(() => {
    const origMap = new Map(crudeTypeOrig.map(c => [c.crude_type_id, c]))
    const currentIds = new Set(crudeTypeRows.map(c => c.crude_type_id))
    const hasNewOrChanged = crudeTypeRows.some(c => {
      const o = origMap.get(c.crude_type_id)
      if (!o) return true
      return c.crude_name !== o.crude_name || c.crude_code !== o.crude_code
        || c.aliases.join(',') !== o.aliases.join(',')
        || c.is_active !== o.is_active || c.is_default !== o.is_default
        || c.sort_order !== o.sort_order || c.note !== o.note
    })
    const hasDeleted = crudeTypeOrig.some(c => c.crude_type_id !== 'default' && !currentIds.has(c.crude_type_id))
    return hasNewOrChanged || hasDeleted
  }, [crudeTypeRows, crudeTypeOrig])
  const dirty = tab === 'product' ? productsDirty : tab === 'crude' ? crudesDirty : tab === 'energy' ? energyDirty : tab === 'side_line' ? sideLinesDirty : tab === 'unit' ? unitDevicesDirty : tab === 'tank' ? tankDevicesDirty : tab === 'flow' ? flowsDirty : tab === 'crude_type' ? crudeTypeDirty : yieldsDirty

  function showToast(ok: boolean, msg: string) {
    setToast({ ok, msg })
    setTimeout(() => setToast(null), 2800)
  }

  // ── 保存 ──
  async function save() {
    setLoading(true); setError(null)
    try {
      if (tab === 'side_line') {
        // 侧线配置保存：直接操作 side_lines 表，不走 /api/products
        const origMap = new Map(sideLinesOrig.map(s => [s.id, s]))
        const currentIds = new Set(sideLines.map(s => s.id))
        const newRows = sideLines.filter(s => !origMap.has(s.id))
        const changedRows = sideLines.filter(s => {
          const o = origMap.get(s.id)
          if (!o) return false
          return s.name !== o.name || s.source_device_id !== o.source_device_id
            || s.material_type !== o.material_type || s.is_final !== o.is_final
            || (s.material_id ?? null) !== (o.material_id ?? null)
        })
        const deletedIds = sideLinesOrig.filter(s => s.id && !currentIds.has(s.id)).map(s => s.id)

        // 新增：POST /api/side_lines
        for (const s of newRows) {
          if (!s.id) throw new Error('新增行必须填写侧线ID')
          const r = await fetch('/api/side_lines', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              side_line_id: s.id, name: s.name, source_device_id: s.source_device_id,
              material_type: s.material_type, is_final: s.is_final, material_id: s.material_id,
            }),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        // 更新：PUT /api/side_lines/{id}
        for (const s of changedRows) {
          const r = await fetch(`/api/side_lines/${s.id}`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              name: s.name, source_device_id: s.source_device_id,
              material_type: s.material_type, is_final: s.is_final, material_id: s.material_id,
            }),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        // 删除：DELETE /api/side_lines/{id}
        for (const id of deletedIds) {
          const r = await fetch(`/api/side_lines/${id}`, { method: 'DELETE' })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        // 重新加载
        const sd = await fetch('/api/side_lines', { cache: 'no-store' }).then(r => r.json())
        const rows: SideLineRow[] = (sd.side_lines || []).map((s: Record<string, unknown>) => ({
          id: s.side_line_id as string,
          name: (s.name as string) || '',
          source_device_id: (s.source_device_id as string) || '',
          material_type: (s.material_type as string) || 'product',
          is_final: Boolean(s.is_final),
          material_id: (s.material_id as number | null) ?? null,
          material_name: (s.material_name as string | null) ?? null,
        }))
        setSideLines(rows); setSideLinesOrig(rows.map(r => ({ ...r })))
        setMaterials((sd.materials || []).map((m: Record<string, unknown>) => ({
          material_id: m.id as number,
          material_name: m.name as string,
        })))
        showToast(true, `已保存 ${newRows.length + changedRows.length} 条侧线，删除 ${deletedIds.length} 条`)
        setLoading(false)
        return
      }
      if (tab === 'product') {
        const r = await fetch('/api/price_cost/products', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ month, items: products.map(p => ({ product_id: p.product_id, price: p.price, product_name: p.product_name })) }),
        })
        const b = await r.json().catch(() => ({}))
        if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        setProductsOrig(products.map(p => ({ ...p })))
        showToast(true, b.message || '保存成功')
        setLoading(false)
        return
      } else if (tab === 'crude') {
        // 原油成本按行原有 planned_month 分组逐月保存（求解器不分月读全表，成本实为全局；
        // 每行带自己的月份，避免把 2026-04 的行误写进当前 month）
        const byMonth = new Map<string, { crude_type_id: string; cost: number; crude_type_name: string }[]>()
        for (const c of crudes) {
          const m = c.planned_month
          if (!byMonth.has(m)) byMonth.set(m, [])
          byMonth.get(m)!.push({ crude_type_id: c.crude_type_id, cost: c.cost, crude_type_name: c.crude_type_name })
        }
        let saved = 0
        for (const [m, items] of byMonth) {
          const r = await fetch('/api/price_cost/crude', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ month: m, items }),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
          saved += items.length
        }
        setCrudesOrig(crudes.map(c => ({ ...c })))
        showToast(true, `已保存 ${saved} 条原油成本`)
        setLoading(false)
        return
      } else if (tab === 'energy') {
        // 能耗逐行 PUT
        const changed = energy.filter((e, i) =>
          Math.abs(e.consumption_per_ton - (energyOrig[i]?.consumption_per_ton ?? e.consumption_per_ton)) > 0.0001
          || Math.abs(e.price_per_unit - (energyOrig[i]?.price_per_unit ?? e.price_per_unit)) > 0.001)
        for (const e of changed) {
          const r = await fetch(`/api/energy_consumptions/${e.id}`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(e),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        setEnergyOrig(energy.map(e => ({ ...e })))
        showToast(true, `已保存 ${changed.length} 条能耗系数`)
        setLoading(false)
        return
      } else if (tab === 'unit') {
        const baseUrl = '/api/units'
        const origMap = new Map(unitDevicesOrig.map(d => [d.id, d]))
        const currentIds = new Set(unitDevices.map(d => d.id))
        const newRows = unitDevices.filter(d => !origMap.has(d.id))
        const changedRows = unitDevices.filter(d => {
          const o = origMap.get(d.id)
          if (!o) return false
          return d.name !== o.name || d.type !== o.type
            || Math.abs(d.safety_stock_thrd - o.safety_stock_thrd) > 0.01
            || Math.abs(d.low_safety_thrd - o.low_safety_thrd) > 0.01
            || Math.abs(d.current_capacity - o.current_capacity) > 0.01
            || Math.abs(d.refinery_unit_load_percent - o.refinery_unit_load_percent) > 0.01
            || (d.backend_device_id ?? null) !== (o.backend_device_id ?? null)
            || (d.enabled ?? true) !== (o.enabled ?? true)
        })
        const deletedIds = unitDevicesOrig.filter(d => d.id && !currentIds.has(d.id)).map(d => d.id)
        for (const d of newRows) {
          if (!d.id) throw new Error('新增行必须填写ID')
          const r = await fetch(baseUrl, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(d),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        for (const d of changedRows) {
          const r = await fetch(`${baseUrl}/${d.id}`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(d),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        for (const id of deletedIds) {
          const r = await fetch(`${baseUrl}/${id}`, { method: 'DELETE' })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        const resp = await fetch(baseUrl, { cache: 'no-store' })
        const d = await resp.json()
        const rows: DeviceRow[] = d.units || d.devices || []
        setUnitDevices(rows); setUnitDevicesOrig(rows.map(r => ({ ...r })))
        showToast(true, `已保存 ${newRows.length + changedRows.length} 条，删除 ${deletedIds.length} 条装置数据`)
        setLoading(false)
        return
      } else if (tab === 'tank') {
        const baseUrl = '/api/tanks'
        const origMap = new Map(tankDevicesOrig.map(d => [d.id, d]))
        const currentIds = new Set(tankDevices.map(d => d.id))
        const newRows = tankDevices.filter(d => !origMap.has(d.id))
        const changedRows = tankDevices.filter(d => {
          const o = origMap.get(d.id)
          if (!o) return false
          return d.name !== o.name
            || Math.abs(d.safety_stock_thrd - o.safety_stock_thrd) > 0.01
            || Math.abs(d.low_safety_thrd - o.low_safety_thrd) > 0.01
            || Math.abs(d.current_capacity - o.current_capacity) > 0.01
            || (d.tank_category ?? null) !== (o.tank_category ?? null)
            || (d.material_id ?? null) !== (o.material_id ?? null)
            || (d.enabled ?? true) !== (o.enabled ?? true)
        })
        const deletedIds = tankDevicesOrig.filter(d => d.id && !currentIds.has(d.id)).map(d => d.id)
        const tankPayload = (d: DeviceRow) => ({
          id: d.id, name: d.name, type: 'tank',
          safety_stock_thrd: d.safety_stock_thrd, low_safety_thrd: d.low_safety_thrd,
          current_capacity: d.current_capacity, refinery_unit_load_percent: d.refinery_unit_load_percent,
          backend_device_id: d.backend_device_id,
          tank_category: d.tank_category, material_id: d.material_id, enabled: d.enabled,
        })
        for (const d of newRows) {
          if (!d.id) throw new Error('新增行必须填写ID')
          const r = await fetch(baseUrl, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(tankPayload(d)),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        for (const d of changedRows) {
          const r = await fetch(`${baseUrl}/${d.id}`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(tankPayload(d)),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        for (const id of deletedIds) {
          const r = await fetch(`${baseUrl}/${id}`, { method: 'DELETE' })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        // 保存月初容量
        const initRows = tankDevices
          .filter(dr => dr.tank_category === 'intermediate' && dr.id)
          .map(dr => ({ tank_id: dr.id, initial_capacity: tankMonthlyInitials[dr.id] ?? dr.current_capacity }))
        if (initRows.length) {
          const miResp = await fetch('/api/tank_monthly_initial', {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ year_month: selectedMonth, rows: initRows }),
          })
          const miB = await miResp.json().catch(() => ({}))
          if (!miResp.ok) throw new Error(miB?.message || `HTTP ${miResp.status}`)
        }
        setTankMonthlyInitialsOrig({ ...tankMonthlyInitials })
        // 重新加载
        const resp = await fetch(baseUrl, { cache: 'no-store' })
        const d = await resp.json()
        const rows: DeviceRow[] = d.tanks || d.devices || []
        setTankDevices(rows); setTankDevicesOrig(rows.map(r => ({ ...r })))
        showToast(true, `已保存 ${newRows.length + changedRows.length} 条，删除 ${deletedIds.length} 条储罐数据`)
        setLoading(false)
        return
      } else if (tab === 'flow') {
        // 物料流向 CRUD: POST新增 / PUT更新 / DELETE删除
        const origMap = new Map(flowsOrig.map(f => [f.id, f]))
        const currentIds = new Set(flows.map(f => f.id))
        const newRows = flows.filter(f => !f.id || !origMap.has(f.id))
        const changedRows = flows.filter(f => {
          if (!f.id) return false  // 空 id 的行不走 PUT（只走 POST 新增）
          const o = origMap.get(f.id)
          if (!o) return false
          return f.source_type !== o.source_type || f.source_device_id !== o.source_device_id
            || f.source_product_id !== o.source_product_id || f.source_name !== o.source_name
            || f.tank_id !== o.tank_id || f.target_device_id !== o.target_device_id
            || f.target_product_id !== o.target_product_id
            || f.flow_type !== o.flow_type || f.material_role !== o.material_role
            || f.special_var !== o.special_var || f.priority !== o.priority
            || f.is_unique_target !== o.is_unique_target
            || Math.abs(f.split_ratio - o.split_ratio) > 0.01
        })
        const deletedIds = flowsOrig.filter(f => f.id && !currentIds.has(f.id)).map(f => f.id)
        for (const f of newRows) {
          const r = await fetch('/api/material_flows', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(f),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        for (const f of changedRows) {
          const r = await fetch(`/api/material_flows/${f.id}`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(f),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        for (const id of deletedIds) {
          const r = await fetch(`/api/material_flows/${id}`, { method: 'DELETE' })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        // 重新加载数据（获取后端生成的新ID）
        const resp = await fetch('/api/material_flows', { cache: 'no-store' })
        const d = await resp.json()
        const rows: FlowRow[] = d.flows || d.material_flows || []
        setFlows(rows); setFlowsOrig(rows.map(r => ({ ...r })))
        showToast(true, `已保存 ${newRows.length + changedRows.length} 条，删除 ${deletedIds.length} 条流向数据`)
        setLoading(false)
        return
      } else if (tab === 'yield') {
        // 收率 CRUD：直接操作 device_yields 表，不走 /api/products
        // 收率页仅保存收率字段 + 油种，身份字段在侧线配置页管理
        const origMap = new Map(yieldsOrig.map(y => [y.id, y]))
        const currentIds = new Set(yields.map(y => y.id))
        const newRows = yields.filter(y => !origMap.has(y.id))
        const changedRows = yields.filter(y => {
          if (!y.id) return false
          const o = origMap.get(y.id)
          if (!o) return false
          return y.crude_type !== o.crude_type
            || Math.abs(y.yield_rate - o.yield_rate) > 0.01
            || Math.abs(y.yield_rate_2 - o.yield_rate_2) > 0.01
            || Math.abs(y.yield_rate_3 - o.yield_rate_3) > 0.01
            || Math.abs(y.yield_rate_4 - o.yield_rate_4) > 0.01
        })
        const deletedIds = yieldsOrig.filter(y => y.id && !currentIds.has(y.id)).map(y => y.id)
        // 新增/更新：POST /api/yields（upsert by side_line_id + crude_type）
        for (const y of [...newRows, ...changedRows]) {
          if (!y.id) throw new Error('收率行缺少 side_line_id')
          const r = await fetch('/api/yields', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              side_line_id: y.id.includes('~') ? y.id.split('~')[0] : y.id,
              crude_type: y.crude_type || 'default',
              yield_rate: y.yield_rate / 100,
              yield_rate_2: y.yield_rate_2 / 100,
              yield_rate_3: y.yield_rate_3 / 100,
              yield_rate_4: y.yield_rate_4 / 100,
            }),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        // 删除：DELETE /api/yields/{side_line_id}/{crude_type}
        for (const id of deletedIds) {
          const o = origMap.get(id)
          const sid = id.includes('~') ? id.split('~')[0] : id
          const ct = o?.crude_type || 'default'
          const r = await fetch(`/api/yields/${sid}/${ct}`, { method: 'DELETE' })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        // 重新加载（仍用 /api/products 获取 joined 数据用于展示）
        const resp = await fetch('/api/products', { cache: 'no-store' })
        const d = await resp.json()
        const rows: YieldRow[] = d.products || []
        setYields(rows); setYieldsOrig(rows.map(r => ({ ...r })))
        showToast(true, `已保存 ${newRows.length + changedRows.length} 条，删除 ${deletedIds.length} 条收率数据`)
        setLoading(false)
        return
      } else if (tab === 'crude_type') {
        // 油种 CRUD
        const origMap = new Map(crudeTypeOrig.map(c => [c.crude_type_id, c]))
        const currentIds = new Set(crudeTypeRows.map(c => c.crude_type_id))
        const newRows = crudeTypeRows.filter(c => !origMap.has(c.crude_type_id))
        const changedRows = crudeTypeRows.filter(c => {
          const o = origMap.get(c.crude_type_id)
          if (!o) return false
          return c.crude_name !== o.crude_name || c.crude_code !== o.crude_code
            || c.aliases.join(',') !== o.aliases.join(',')
            || c.is_active !== o.is_active || c.is_default !== o.is_default
            || c.sort_order !== o.sort_order || c.note !== o.note
        })
        const deletedIds = crudeTypeOrig.filter(c => c.crude_type_id !== 'default' && !currentIds.has(c.crude_type_id)).map(c => c.crude_type_id)
        for (const c of newRows) {
          if (!c.crude_type_id) throw new Error('新增行必须填写油种ID')
          const r = await fetch('/api/crude_types', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(c),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        for (const c of changedRows) {
          const r = await fetch(`/api/crude_types/${c.crude_type_id}`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(c),
          })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        for (const id of deletedIds) {
          const r = await fetch(`/api/crude_types/${id}`, { method: 'DELETE' })
          const b = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(b?.message || `HTTP ${r.status}`)
        }
        // 重新加载
        const resp = await fetch('/api/crude_types', { cache: 'no-store' })
        const d = await resp.json()
        const rows: CrudeTypeRow[] = (d.data || []).map((r: Record<string, unknown>) => ({
          crude_type_id: r.crude_type_id as string,
          crude_name: r.crude_name as string,
          crude_code: r.crude_code as string || '',
          aliases: r.aliases as string[] || [],
          is_active: r.is_active as boolean ?? true,
          is_default: r.is_default as boolean ?? false,
          sort_order: r.sort_order as number ?? 0,
          note: (r.note as string) || '',
        }))
        setCrudeTypeRows(rows); setCrudeTypeOrig(rows.map(r => ({ ...r })))
        showToast(true, `已保存 ${newRows.length + changedRows.length} 条，删除 ${deletedIds.length} 条油种数据`)
        setLoading(false)
        return
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
      showToast(false, '保存失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="space-y-5 animate-fade-in-up">
      {/* 页头 + Tab — sticky 冻结 */}
      <div className="sticky top-0 z-50 -mx-1 px-1 pt-1 pb-2 bg-[#f8fafc]/95 backdrop-blur-sm border-b border-slate-200/60">
        {/* 页头 */}
        <div className="flex items-end justify-between flex-wrap gap-3">
          <div className="flex items-center gap-2">
            <button onClick={() => setHeaderCollapsed(v => !v)}
              className="p-1 rounded hover:bg-slate-200/60 text-slate-400 hover:text-slate-600 transition-colors"
              title={headerCollapsed ? '展开页头' : '收起页头'}>
              {headerCollapsed
                ? <ChevronDown className="w-4 h-4" />
                : <ChevronUp className="w-4 h-4" />}
            </button>
            {!headerCollapsed && (
              <div>
                <h1 className="text-xl font-bold text-slate-900 flex items-center gap-2">
                  <Cog className="w-5 h-5 text-slate-600" />基础配置
                </h1>
                <p className="text-sm text-slate-500 mt-0.5">
                  管理产品价格、原油成本、能耗系数、装置物流与收率，修改后写库持久化，下次求解自动生效
                </p>
              </div>
            )}
          </div>
          <div className="flex items-center gap-2">
            {tab === 'product' && (
              <>
                <span className="text-xs text-slate-500">月份</span>
                <select value={month} onChange={e => setMonth(e.target.value)} disabled={loading}
                  className="h-9 rounded-md border border-slate-200 bg-white px-3 text-sm text-slate-700 disabled:bg-slate-50">
                  {months.map(m => <option key={m.key} value={m.key}>{m.label}</option>)}
                </select>
              </>
            )}
            <button onClick={save} disabled={loading || !dirty}
              className={cn(
                "inline-flex items-center h-9 px-5 rounded-md text-white text-sm font-medium disabled:opacity-40 transition-colors",
                dirty ? 'bg-amber-600 hover:bg-amber-700' : 'bg-slate-400'
              )}>
              {loading ? <><Loader2 className="w-4 h-4 mr-1.5 animate-spin" />保存中…</>
                : <><Save className="w-4 h-4 mr-1.5" />保存{dirty ? ' *' : ''}</>}
            </button>
          </div>
        </div>

        {/* Tab 切换 */}
        <div className="flex items-center gap-1 p-1 rounded-lg bg-slate-100 w-fit mt-3">
          {TABS.map(t => {
            const Icon = t.icon
            const isDirty = t.key === 'product' ? productsDirty : t.key === 'crude' ? crudesDirty : t.key === 'energy' ? energyDirty : t.key === 'side_line' ? sideLinesDirty : t.key === 'unit' ? unitDevicesDirty : t.key === 'tank' ? tankDevicesDirty : t.key === 'flow' ? flowsDirty : yieldsDirty
            return (
              <button key={t.key} onClick={() => setTab(t.key)} disabled={loading}
                className={cn(
                  'inline-flex items-center gap-1.5 h-8 px-4 rounded-md text-sm font-medium transition-all',
                  tab === t.key ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700'
                )}>
                <Icon className="w-4 h-4" />{t.label}
                {isDirty && <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />}
              </button>
            )
          })}
        </div>
      </div>

      {/* toast */}
      {toast && (
        <div className={cn(
          'flex items-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium',
          toast.ok ? 'bg-emerald-50 text-emerald-700 border border-emerald-200' : 'bg-red-50 text-red-700 border border-red-200'
        )}>
          {toast.ok ? <CheckCircle2 className="w-4 h-4" /> : <AlertTriangle className="w-4 h-4" />}
          {toast.msg}
        </div>
      )}

      {error && (
        <div className="p-4 rounded-xl border border-red-200 bg-red-50/40">
          <div className="flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 text-red-500 mt-0.5 shrink-0" />
            <div className="text-xs text-red-700 font-mono whitespace-pre-wrap break-all">{error}</div>
          </div>
        </div>
      )}

      {/* 内容区 */}
      {loading && !products.length && !crudes.length && !energy.length && !sideLines.length && !unitDevices.length && !tankDevices.length && !flows.length && !yields.length ? (
        <div className="flex items-center justify-center py-16 text-slate-400">
          <Loader2 className="w-5 h-5 animate-spin mr-2" />加载中…
        </div>
      ) : tab === 'crude_type' ? (
        <CrudeTypeTable rows={crudeTypeRows} orig={crudeTypeOrig}
          onChange={(i, field, v) => setCrudeTypeRows(prev => prev.map((c, idx) => idx === i ? { ...c, [field]: v } : c))}
          onAdd={() => setCrudeTypeRows(prev => [...prev, { crude_type_id: '', crude_name: '', crude_code: '', aliases: [], is_active: true, is_default: false, sort_order: 99, note: '' }])}
          onDelete={(i) => setCrudeTypeRows(prev => prev.filter((_, idx) => idx !== i))} />
      ) : tab === 'product' ? (
        <ProductPriceTable rows={products} onChange={(i, v) => setProducts(prev => prev.map((p, idx) => idx === i ? { ...p, price: v } : p))} />
      ) : tab === 'crude' ? (
        <CrudeCostTable rows={crudes} onChange={(i, v) => setCrudes(prev => prev.map((c, idx) => idx === i ? { ...c, cost: v } : c))} />
      ) : tab === 'energy' ? (
        <EnergyTable rows={energy} orig={energyOrig}
          onChange={(i, field, v) => setEnergy(prev => prev.map((e, idx) => idx === i ? { ...e, [field]: v } : e))} />
      ) : tab === 'side_line' ? (
        <SideLineTable rows={sideLines} orig={sideLinesOrig} devices={sideLineDevices} materials={materials}
          onChange={(i, field, v) => setSideLines(prev => prev.map((s, idx) => idx === i ? { ...s, [field]: v } : s))}
          onAdd={(deviceId, materialType) => setSideLines(prev => [...prev, { id: '', name: '', source_device_id: deviceId, material_type: materialType, is_final: false, material_id: null, material_name: null }])}
          onDelete={(i) => setSideLines(prev => prev.filter((_, idx) => idx !== i))} />
      ) : tab === 'unit' ? (
        <UnitTable rows={unitDevices} orig={unitDevicesOrig}
          onChange={(i, field, v) => setUnitDevices(prev => prev.map((d, idx) => idx === i ? { ...d, [field]: v } : d))}
          onAdd={() => setUnitDevices(prev => [...prev, { id: '', name: '', type: 'normal', safety_stock_thrd: 0, low_safety_thrd: 0, current_capacity: 0, refinery_unit_load_percent: 100, effective_capacity: 0, backend_device_id: null, tank_category: null, enabled: true }])}
          onDelete={(i) => setUnitDevices(prev => prev.filter((_, idx) => idx !== i))} />
      ) : tab === 'tank' ? (
        <TankTable rows={tankDevices} orig={tankDevicesOrig} materials={materials}
          onChange={(i, field, v) => setTankDevices(prev => prev.map((d, idx) => idx === i ? { ...d, [field]: v } : d))}
          onAdd={() => setTankDevices(prev => [...prev, { id: '', name: '', type: 'tank', safety_stock_thrd: 0, low_safety_thrd: 0, current_capacity: 0, refinery_unit_load_percent: 100, effective_capacity: 0, backend_device_id: null, tank_category: null, material_id: null, material_name: null, enabled: true }])}
          onDelete={(i) => setTankDevices(prev => prev.filter((_, idx) => idx !== i))}
          tankMonthlyInitials={tankMonthlyInitials}
          onMonthlyInitialChange={(tankId, val) => setTankMonthlyInitials(prev => ({ ...prev, [tankId]: val }))}
          selectedMonth={selectedMonth}
          onMonthChange={(month) => {
            setSelectedMonth(month)
            fetch(`/api/tank_monthly_initial?year_month=${month}`, { cache: 'no-store' })
              .then(r => r.json())
              .then(mi => {
                const initials: Record<string, number> = {}
                for (const r of (mi?.data || [])) {
                  initials[r.tank_id] = r.initial_capacity
                }
                setTankMonthlyInitials(initials)
                setTankMonthlyInitialsOrig({ ...initials })
              })
              .catch(() => {})
          }} />
      ) : tab === 'flow' ? (
        <>
        <FlowDiagram rows={flows} />
        <MaterialFlowTable rows={flows} orig={flowsOrig}
          devices={flowDevices} products={flowProducts}
          onChange={(i, field, v) => setFlows(prev => prev.map((f, idx) => idx === i ? { ...f, [field]: v } : f))}
          onAdd={(ctx) => setFlows(prev => [...prev, {
            id: '',
            source_type: ctx.isTank ? 'tank' : (ctx.flowType === 'input' ? 'external' : 'device'),
            source_device_id: ctx.asSource && !ctx.isTank ? ctx.deviceId : '',
            source_product_id: '',
            source_name: '',
            tank_id: ctx.isTank ? ctx.deviceId : '',
            target_device_id: !ctx.asSource && !ctx.isTank ? ctx.deviceId : '',
            target_product_id: '',
            flow_type: ctx.flowType,
            material_role: '',
            special_var: '',
            priority: 0,
            is_unique_target: false,
            split_ratio: 1
          }])}
          onDelete={(i) => setFlows(prev => prev.filter((_, idx) => idx !== i))} />
        </>
      ) : (
        <YieldTable rows={yields} orig={yieldsOrig} crudeFilter={crudeFilter} tankIds={yieldTankIds} allDevices={yieldAllDevices} crudeTypes={crudeTypes}
          onCrudeFilterChange={setCrudeFilter}
          onChange={(i, field, v) => setYields(prev => prev.map((y, idx) => idx === i ? { ...y, [field]: v } : y))}
          onDelete={(i) => setYields(prev => prev.filter((_, idx) => idx !== i))} />
      )}
    </div>
  )
}
