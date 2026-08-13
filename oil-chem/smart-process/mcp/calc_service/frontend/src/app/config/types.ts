// ── 基础配置页共享类型 ──

export type Tab = 'product' | 'crude' | 'energy' | 'side_line' | 'unit' | 'tank' | 'flow' | 'yield' | 'crude_type'

export type ProductPrice = {
  price_month: string; product_id: string; product_name: string; price: number
}

export type CrudeCost = {
  planned_month: string; crude_type_id: string; crude_type_name: string; cost: number
}

export type EnergyRow = {
  id: string; device_id: string; consumption_per_ton: number; price_per_unit: number; energy_type: string
}

export type MappingRow = {
  product_id: string; product_name: string
  material_id: number | null; material_name: string | null
}

export type MaterialOption = {
  material_id: number; material_name: string
}

export type DeviceRow = {
  id: string; name: string; type: string
  safety_stock_thrd: number; low_safety_thrd: number
  current_capacity: number; refinery_unit_load_percent: number
  effective_capacity: number
  backend_device_id: number | null
  tank_category: string | null  // intermediate/product/crude (仅 tank)
  material_id: number | null    // 储罐关联物料 (仅 tank)
  material_name: string | null  // 物料名称（只读展示）
  enabled: boolean  // 启用/停用
}

export type FlowRow = {
  id: string; source_type: string; source_device_id: string;
  source_product_id: string; source_name: string;
  tank_id: string; target_device_id: string;
  target_product_id: string;
  flow_type: string;
  special_var: string; priority: number;
  is_unique_target: boolean; split_ratio: number;
  // 后端增补的名称字段（只读展示用）
  source_device_name?: string; source_product_name?: string;
  tank_name?: string; target_device_name?: string;
  target_product_name?: string;
}

export type YieldRow = {
  id: string; name: string; source_device_id: string
  yield_rate: number; yield_rate_2: number; yield_rate_3: number; yield_rate_4: number
  is_final: boolean; crude_type: string; material_type: string
}

// 侧线配置行：一个 side_line_id 一行，含物料绑定
export type SideLineRow = {
  id: string                 // side_line_id (即 product_id)
  name: string               // 侧线名称
  source_device_id: string   // 所属装置
  material_type: string      // product / main_feed / auxiliary
  is_final: boolean          // 是否终端产品
  material_id: number | null // 绑定的物料ID（来自 side_lines.material_id）
  material_name: string | null // 物料名称（只读展示）
}

// 生成近 18 个月份选项（前 6 个月 + 当前月 + 后 11 个月），覆盖跨年场景
export const FALLBACK_MONTHS = (() => {
  const now = new Date()
  const opts: { key: string; label: string }[] = []
  for (let i = -6; i < 12; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() + i, 1)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
    opts.push({ key, label: `${d.getFullYear()}年${d.getMonth() + 1}月` })
  }
  return opts
})()

// React Flow 类型（Turbopack 对 @xyflow/react 的 export * 类型解析有问题，内联定义）
export type RFNode = { id: string; type?: string; position: { x: number; y: number }; data: Record<string, unknown> }
export type RFEdge = { id: string; source: string; target: string; label?: string; style?: Record<string, unknown>; animated?: boolean; type?: string; labelStyle?: Record<string, unknown>; labelBgStyle?: Record<string, unknown>; labelBgPadding?: [number, number]; labelBgBorderRadius?: number }

export type FlowNodeData = { name: string; id: string; layer: number; label: string }

export type CrudeTypeRow = {
  crude_type_id: string; crude_name: string; crude_code: string
  aliases: string[]; is_active: boolean; is_default: boolean
  sort_order: number; note: string
}
