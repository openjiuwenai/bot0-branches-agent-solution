'use client'

import { useState, Fragment } from 'react'
import {
  Loader2, Coins, Network, Layers, Timer,
  TrendingUp, AlertTriangle, ChevronDown, ChevronRight as ChevRight, Trophy,
  Plane, PlaneTakeoff,
  Gauge, ArrowDown, Factory, Receipt, Database, Wallet,
} from 'lucide-react'
import EChart, { CHART_COLORS } from './EChart'

// ── 数字格式化 ─────────────────────────────────────────────────────────
export const fNum = (n: number) => Math.round(n).toLocaleString()

// ── 罐容段级检测结果类型 ──────────────────────────────────────────────
export type TankSegment = {
  seg_id: number; batch_idx: number; batch_id: number
  start_day: number; end_day: number; days: number
  crude_type: string; mode: string; is_hangmei: boolean; shutdown_intervals: Record<string, unknown>; shutdown_devices: string[]
}
export type TankViolation = {
  tank_id: string; tank_name: string; seg_id: number
  start_day: number; end_day: number; capacity: number; threshold: number
  violation_type: 'over_high' | 'below_low'; severity: number
}
export type TankTrajectoryPoint = {
  seg_id: number; start_day: number; end_day: number; days: number
  is_hangmei: boolean; batch_id: number
  inflow: number; outflow: number; delta: number
  start_cap: number; end_cap: number; util_pct: number | null
}
export type TankTrajectory = {
  tank_id: string; tank_name: string; tank_category: string
  safety_stock_thrd: number; low_safety_thrd: number; initial_capacity: number
  points: TankTrajectoryPoint[]
  violations: TankViolation[]
}
export type TankCheckResult = {
  segments: TankSegment[]
  tank_trajectories: TankTrajectory[]
  violations: TankViolation[]
  violation_count: number
  has_violations: boolean
}

// comprehensive_solve 返回的批次（switch_planner.identify_batches 产出）
export type SolveBatch = {
  batch_id: number; crude_type: string; start_day: number; end_day: number
  total_input: number; days: number; daily_inputs: number[]
}
// 停工批次标记（switch_planner.apply_shutdown_windows 给停工段子批次打的字段）
// 非停工段为空字典；停工段为 { device_id: [(start_hour, end_hour), ...] }
export type SolveBatchWithShutdown = SolveBatch & { shutdown_intervals?: Record<string, unknown> | null }
// batch_results 元素（batch_optimizer.evaluate_combination 产出）
export type BatchResult = {
  batch_id: number; crude_type: string; start_day: number; end_day: number
  total_input: number; mode: string; daily_input: number; revenue: number
  jian1_to_diesel: number; jian1_to_wax: number
  is_hangmei_period?: boolean
}
// combination_results 元素（batch_optimizer.optimize_combinations 返回）
// feasible/bottleneck/infeasible_summary/batch_details 为约束展示+联动新增
// 月度装置负荷（solve_service._build_monthly_load 产出，展示+检测层）
// 装置：月度加工量 vs 月度能力，检测月度平均是否超容；罐：月均库存仅展示不判超限
export type MonthlyDevice = {
  device_id: string; name: string; type?: string
  monthly_input: number; daily_avg: number; monthly_capacity: number
  monthly_util: number; is_overloaded: boolean
  unprocessed_material?: number  // 未加工完的中间料量（月负荷超容时>0）
}
export type MonthlyLoad = {
  total_days: number; devices: MonthlyDevice[]
  overload_count: number; summary: string
}
export type ComboResult = {
  combination_id: number; description: string; switch_position: number
  initial_mode: string; switches: Record<number, string>
  total_revenue: number; batch_results: BatchResult[]
  feasible?: boolean
  near_feasible?: boolean  // 罐容无违规 BUT 月度负荷超容 → 接近可行（按满负荷折减）
  temporary_feasible?: boolean  // 兼容旧字段：所有组合均超容时，选取超容最轻的作为临时可行
  bottleneck?: Array<{ device_id: string; device_name: string; input_amount: number; effective_capacity: number; excess: number }>
  infeasible_summary?: string
  batch_details?: CalcBatchDetail[]
  monthly_load?: MonthlyLoad
  tank_check_result?: TankCheckResult  // 罐容检测结果（违规即不可行）
}
// optimal_combination 只是 3-key 瘦 dict，完整数据要从 combination_results 按 id 查
export type OptimalSlim = { combination_id: number; switches: Record<number, string>; description: string } | null
// 航煤工况摘要（batch_optimizer._compute_combo_hangmei 产出，最优组合的摘要回传顶层）
export type HangmeiWindowBatch = {
  batch_id: number; crude: string; mode?: string; days: number; benefit: number
  daily_input?: number      // 日加工量（吨）
  yield_low?: number        // 非航煤收率（小数）
  yield_high?: number       // 航煤收率（小数）
  hm_daily_delta?: number   // 该批次航煤日增量（吨/天）
  hm_delta?: number         // 该批次航煤增量（吨）
}
export type HangmeiWindowDetail = {
  start: number; end: number; total_benefit: number
  m_days?: number           // 该候选位置的实际 M（天）
  hm_total?: number         // 该窗口航煤总增量（吨）
  feasible?: boolean        // 该位置能否满足缺口
  covered_batches: HangmeiWindowBatch[]; is_optimal: boolean
}
export type HangmeiHDefaultDetail = {
  batch_id: number; crude_type: string; mode: string
  days: number; daily_input: number
  cyjq_effective_input?: number; lyjq_effective_input?: number
  yield_low: number; yield_high: number
  lyjq_yield_low?: number; lyjq_yield_high?: number
  cyjq_output?: number; lyjq_output?: number
  batch_output: number
}
export type HangmeiMCalc = {
  H_default: number; target: number; delta_H: number
  best_start: number
  covered_batches: HangmeiWindowBatch[]  // 最优窗口覆盖批次（含各批次日增量/增量/天数）
  hm_total: number   // 窗口内航煤总增量（吨）
  feasible?: boolean  // 该窗口是否满足缺口
  M: number          // 该位置的实际 M（天）
  M_tons?: number    // 航煤期连接主料吨数（吨）
}
export type HangmeiSummary = {
  enabled: boolean; active: boolean; feasible?: boolean; target: number
  m_days: number; n_days: number; total_days: number
  m_tons?: number; n_tons?: number; total_active_tons?: number  // 吨维度（连接主料吨数）
  active_devices?: { device_id: string; device_name: string }[]   // 主动装置列表（收率受航煤影响）
  passive_devices?: { device_id: string; device_name: string }[]  // 被动装置列表（收率不变）
  actual_H: number; deviation: number
  effective_H?: number      // 负荷折减后实际产出（吨，<= actual_H）
  hangmei_gap?: number      // 负荷折减导致的航煤缺口（吨，>0 表示未达标）
  yield_high: number; yield_low: number
  first_mode: string; product_id: string; product_name: string
  hangmei_start?: number       // 最优航煤起始偏移（天）
  window_search?: HangmeiWindowDetail[]  // 候选时段对比
  H_default?: number           // 常规（非航煤）工况全月产出（吨）
  // 航煤工况边际贡献（已计入组合总效益，仅供展示）
  hm_benefit?: number      // 增产收益合计（元，正数）
  rlydmx_loss?: number     // 减产损失合计（元，正数，向后兼容字段名）
  net_benefit?: number     // 净增益（元）= hm_benefit - rlydmx_loss
  hm_price?: number        // 航煤价格（元/吨，向后兼容）
  rlydmx_price?: number    // rlydmx价格（元/吨，向后兼容）
  product_deltas_detail?: HangmeiProductDelta[]  // 各产品收入变化明细
  daily_input_avg?: number // 日均加工量（吨，供展示用）
  h_default_details?: HangmeiHDefaultDetail[]  // 各批次常规产出明细
  m_calc?: HangmeiMCalc    // M 计算过程明细
}
export type HangmeiProductDelta = {
  product_id: string; product_name: string
  device_id?: string      // 装置ID（cyjq_01 / lyjq_01）
  device_name?: string    // 装置名（柴油加氢 / 蜡油加氢）
  delta_revenue: number   // 收入变化（正=增产收益，负=减产损失）
  price: number           // 产品价格（元/吨）
  yield_low: number       // 非航煤收率（小数）
  yield_high: number      // 航煤收率（小数）
  changed?: boolean       // 收率是否有变化（false=航煤期收率不变，收入无影响）
}

// 效益拆解（solve_service._build_economic_breakdown 从 optimal_explanations 聚合）
// 供业务决策台结构化渲染，与 economic_explanation 文本同源同值
export type BreakdownFeed = {
  name: string; label: string         // 主料/辅料
  quantity: number; ratio: number     // 总量(吨), 配比(%)
  price: number; cost: number         // 单价(元/吨), 成本(元)
}
export type BreakdownProduct = {
  product_id: string; product_name: string
  overall_yield: number
  price: number; output: number; revenue: number
}
export type BreakdownDevice = {
  device_id: string; device_name: string
  input_amount: number; effective_input: number; total_feed_qty: number
  main_feed_name: string; main_feed_qty: number; main_load_qty: number
  revenue: number; share: number
  crude_cost: number; energy_cost: number; profit: number
  feeds: BreakdownFeed[]
  products: BreakdownProduct[]
}
export type BreakdownTotals = {
  crude_cost: number; energy_cost: number; product_revenue: number
  total_cost: number; profit: number; total_input: number
}
export type BreakdownAggProduct = {
  product_name: string; quantity: number; price: number; revenue: number; share: number
}
export type EconomicBreakdown = {
  totals: BreakdownTotals
  devices: BreakdownDevice[]
  products: BreakdownAggProduct[]
}

// 计算过程（solve_service._build_batch_details 从最优组合各批次 calc_result + explanation 透传）
// 供效益预测页渲染装置级求解链：投入→常减压负荷→减一线→柴加/蜡加→收率→批次效益
export type CalcDeviceUtil = {
  name: string; type?: string; tank_category?: string; note?: string
  input: number; outflow?: number
  // 蜡加(lyjq_01)专用：input=主进料H(仅hc罐)，total_input=H+B(含柴加回流)，供物料平衡核对
  total_input?: number
  // 罐容库存指标（仅 tank 类型有值；加工装置为 0）
  current_capacity?: number; safety_stock_thrd?: number; low_safety_thrd?: number
  // 装置负荷率（refinery_unit_load_percent，后端写入）
  refinery_unit_load_percent?: number
  // CDU（type=='start'）专用：日均能力上限与是否超负荷
  cdu_daily_cap?: number; cdu_overload?: boolean
  // 加工指标（solve_service 写入）：
  // daily_consumption=月平均日处理量(吨/天), processing_days=月平均负荷下加工天数
  daily_consumption?: number; processing_days?: number
}
export type CalcProductOutput = {
  output: number; yield_rate: number; yield_type: string; yield_reason: string; price: number
}
export type CalcEconProduct = {
  product_id: string; product_name: string
  yield_rate: number; yield_type: string; yield_reason: string
  price: number; output: number; revenue: number
}
export type CalcEconItem = {
  device_id: string; device_name: string
  input_amount: number; effective_input: number; revenue: number
  formula?: string; ton_revenue?: number; products: CalcEconProduct[]
}
export type CalcTonMetrics = {
  revenue: number; feed_cost: number; process_cost: number; profit: number
}
export type CalcBatchCosts = {
  crude_cost: number; energy_cost: number; total_cost: number
  total_profit: number; total_revenue: number; daily_input: number; days: number
  ton_metrics?: CalcTonMetrics
}
export type CalcFeedItem = {
  product_id: string; name: string; material_type: string
  yield_rate: number; feed_qty: number; price: number; cost: number; ton_cost?: number; label: string
}
export type CalcFeedDetail = {
  device_id: string; device_name: string; input_qty: number
  feed_cost: number; ton_feed_cost?: number; items: CalcFeedItem[]
}
export type CalcProcessDetail = {
  device_id: string; device_name: string; input_qty: number
  unit_cost: number; process_cost: number
}
export type CalcBatchDetail = {
  batch_id: number; crude_type: string; start_day: number; end_day: number
  mode: string; total_input: number; revenue: number
  jian1_to_diesel: number; jian1_to_wax: number; is_hangmei_period: boolean
  device_inputs: Record<string, number>
  device_utilization: Record<string, CalcDeviceUtil>
  special_vars: Record<string, number>
  economic_analysis: CalcEconItem[]
  all_product_outputs: Record<string, Record<string, CalcProductOutput>>
  feed_details: CalcFeedDetail[]
  process_details: CalcProcessDetail[]
  diversion_scenarios: any[]
  costs: CalcBatchCosts
}

// comprehensive_solve 顶层返回（solve_service 编排入口）
// 停工摘要类型复用 ShutdownPanel 的定义（单一来源，避免两处定义漂移）
import type { ShutdownInfo } from '@/components/ShutdownPanel'

export type Jian1SwitchAnalysis = {
  switch_day: number
  switch_batch_id: number | null
  initial_mode: string
  initial_mode_cn: string
  diesel_processing_days: number
  wax_processing_days: number
  diesel: { cdu_output: number; device_demand: number; diff: number }
  wax: { cdu_output: number; device_demand: number; diff: number }
}

export type SolveResp = {
  success: boolean
  has_feasible?: boolean
  batches: SolveBatch[]
  total_combinations: number
  combination_results: ComboResult[]
  optimal_combination: OptimalSlim
  optimal_revenue: number
  message: string
  economic_explanation?: string
  economic_breakdown?: EconomicBreakdown
  optimal_batch_details?: CalcBatchDetail[]
  hangmei_summary?: HangmeiSummary
  shutdown?: ShutdownInfo
  jian1_switch_analysis?: Jian1SwitchAnalysis
  // 双价格月口径（选方案/算效益解耦）
  selection_price_month?: string   // 选方案用的价格月（上月）
  final_price_month?: string       // 核算效益用的价格月（本月）
  selection_revenue?: number       // 选优阶段效益（上月价，选方案用）
  // 本月价全组合对比（与上月价 combination_results 同结构，供横向对比两套价格）
  combination_results_final?: ComboResult[]
  final_optimal_combo_id?: number  // 本月价各自选优的组合id（本月价表高亮用）
  // 罐容段级检测结果（检测优先，不做可行性判定）
  tank_check_result?: TankCheckResult
}

// 切换方向 → 中文（X_ZERO 全去蜡加，Y_ZERO 全去柴加）
export const MODE_CN: Record<string, string> = { X_ZERO: '→ 蜡油加氢', Y_ZERO: '→ 柴油加氢' }
export const MODE_SHORT: Record<string, string> = { X_ZERO: '蜡加', Y_ZERO: '柴加' }

// 装置 ID（cjy_01 常减压为固定起点；加工装置动态从 economic_analysis 获取）
export const DEV_CJY = 'cjy_01'
// 保留旧常量供其他组件兼容引用（不再用于硬编码渲染）
export const DEV_CYJQ = 'cyjq_01'
export const DEV_LYJQ = 'lyjq_01'

// 流程步骤编号（① ② ③ ...，最多支持12步）
const CIRCLED = ['①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩', '⑪', '⑫']
// 加工装置色调循环
const PROC_TONES: ('blue' | 'purple')[] = ['blue', 'purple']

// 求解器原油 ID → 展示名（solve_v1 的 production_plans_input!crude_type_name 列存在乱码，name 不可靠）
export const CRUDE_ID_CN: Record<string, string> = {
  bozhong_25_1: '渤中25-1',
  'qinhuangdao/nanpu_35_2': '秦皇岛/南堡',
  caofeidian: '曹妃甸',
  'LH/LD': '流花/旅大',
}

// PLAN-202601 → {key:'2026-01', label:'2026年1月'}；已归一化的 YYYY-MM 也兼容；非法返回 null
export function normalizeMonth(raw: string): { key: string; label: string } | null {
  if (!raw) return null
  const m = /(\d{4})-?(\d{2})/.exec(raw)
  if (!m) return null
  return { key: `${m[1]}-${m[2]}`, label: `${m[1]}年${parseInt(m[2], 10)}月` }
}

// 默认原油名解析：优先 CRUDE_ID_CN 映射，回退 ID 本身
export function defaultCrudeName(id: string, crudeMap?: Record<string, { id: string; name: string; cost: number }>): string {
  return CRUDE_ID_CN[id] || crudeMap?.[id]?.name || id
}

// ── 结果展示组件（排产页 / 效益预测页共用）─────────────────────────────
// props.crudeName 由各页注入：排产页带 crudeMap，预测页可选
export function SolveResult({
  result, elapsedMs, crudeName,
}: {
  result: SolveResp
  elapsedMs: number
  crudeName: (id: string) => string
}) {
  const [showAllCombos, setShowAllCombos] = useState(false)
  const [expandedBatch, setExpandedBatch] = useState<number | null>(null)
  // 详细区块默认折叠（核心结论保持展开，辅助校验信息按需展开）
  const [showBatches, setShowBatches] = useState(false)
  const [showMonthlyLoad, setShowMonthlyLoad] = useState(false)
  const [showEconReport, setShowEconReport] = useState(false)
  // 新增可折叠区块（默认折叠）
  const [showTimeline, setShowTimeline] = useState(false)       // 批次时间线
  // 组合-批次联动：当前选中的组合（默认最优）；点击组合表行切换
  const [selectedComboId, setSelectedComboId] = useState<number | null>(null)
  // 当前激活的价格表（钻取明细 + KPI 跟随此口径），默认本月价（与顶层 optimal_revenue 一致）
  const [activePriceTab, setActivePriceTab] = useState<'selection' | 'final'>('final')

  // 数据源：根据激活的价格表选择（两表组合 id 相同，仅 total_revenue/batch_details 随价格月变化）
  const comboSource = (activePriceTab === 'final' && result.combination_results_final?.length)
    ? result.combination_results_final
    : result.combination_results

  // optimal_combination 是瘦 dict，完整 batch_results/total_revenue 从 comboSource 按 id 查
  const optimalCombo = (result.success && result.optimal_combination)
    ? (comboSource.find(c => c.combination_id === result.optimal_combination!.combination_id) ?? null)
    : null
  // 无可行方案时：默认选中理论收益最高的不可行组合，供批次时间线/计算过程联动展示
  const fallbackCombo = optimalCombo ? null
    : [...comboSource].sort((a, b) => b.total_revenue - a.total_revenue)[0] ?? null
  // 选中的组合：优先 selectedComboId，回退最优组合，再回退理论收益最高组合
  const selectedCombo = (selectedComboId != null
    ? comboSource.find(c => c.combination_id === selectedComboId)
    : null) ?? optimalCombo ?? fallbackCombo

  // 减一线月度分流（吨）：柴加 / 蜡加
  const xTons = optimalCombo ? optimalCombo.batch_results.reduce((s, b) => s + b.jian1_to_diesel, 0) : 0
  const yTons = optimalCombo ? optimalCombo.batch_results.reduce((s, b) => s + b.jian1_to_wax, 0) : 0
  const jian1Total = xTons + yTons
  // 可行组合数（combination_results 现含不可行组合，需过滤）
  const feasibleCount = result.combination_results.filter(c => c.feasible === true || (c.feasible !== false && !c.near_feasible)).length
  const nearFeasibleCount = result.combination_results.filter(c => c.near_feasible === true).length
  const infeasibleCount = result.combination_results.length - feasibleCount - nearFeasibleCount
  // ① 输入概览派生量（从 batches 聚合，与组合无关的纯输入侧统计）
  const totalDays = result.batches.reduce((s, b) => s + (b.days || 0), 0)
  const totalInput = result.batches.reduce((s, b) => s + (b.total_input || 0), 0)
  const crudeCount = new Set(result.batches.map(b => b.crude_type)).size
  const shutdownWindows = result.shutdown?.windows ?? []


  // 真正的求解失败（无组合数据）才显示提示卡
  if (!result.success || !result.combination_results?.length) {
    return (
      <div className="p-5 rounded-xl border border-amber-200 bg-amber-50/40">
        <div className="flex items-center gap-2 text-amber-800">
          <AlertTriangle className="w-5 h-5" />
          <span className="font-semibold">求解失败</span>
        </div>
        <p className="text-sm text-amber-700 mt-2">{result.message}</p>
      </div>
    )
  }

  if (!selectedCombo) return null
  const hasFeasible = !!optimalCombo
  const isTemporary = optimalCombo?.temporary_feasible === true
  const isNearOptimal = optimalCombo?.near_feasible === true

  return (
    <div className="space-y-4">
      {/* 接近可行方案提示横幅（罐容无违规 BUT 月度负荷超容，按满负荷折减） */}
      {isNearOptimal && (
        <div className="p-4 rounded-xl border border-amber-300 bg-amber-50/60">
          <div className="flex items-center gap-2 text-amber-800">
            <AlertTriangle className="w-5 h-5" />
            <span className="font-semibold">接近可行方案（月度负荷超容，按满负荷折减）</span>
          </div>
          <p className="text-sm text-amber-700 mt-1.5">{optimalCombo?.infeasible_summary || result.message}</p>
          <p className="text-[11px] text-amber-600 mt-1">罐容无违规但部分装置月度负荷超容，已按满负荷能力折减加工量，超容原料缓存在中间罐中。实际可逐日微调进料以缓解瓶颈。</p>
        </div>
      )}
      {/* 临时可行/无可行方案提示横幅 */}
      {isTemporary && !isNearOptimal && (
        <div className="p-4 rounded-xl border border-amber-300 bg-amber-50/60">
          <div className="flex items-center gap-2 text-amber-800">
            <AlertTriangle className="w-5 h-5" />
            <span className="font-semibold">临时可行方案（所有组合月度均超容）</span>
          </div>
          <p className="text-sm text-amber-700 mt-1.5">{optimalCombo?.infeasible_summary || result.message}</p>
          <p className="text-[11px] text-amber-600 mt-1">已选取超容最轻的组合作为参考方案，装置允许一定超负荷，实际可逐日微调进料。</p>
        </div>
      )}
      {!hasFeasible && (
        <div className="p-4 rounded-xl border border-amber-200 bg-amber-50/50">
          <div className="flex items-center gap-2 text-amber-800">
            <AlertTriangle className="w-5 h-5" />
            <span className="font-semibold">本月无可行排产方案</span>
          </div>
          <p className="text-sm text-amber-700 mt-1.5">{result.message}</p>
          <p className="text-[11px] text-amber-600 mt-1">下方展示各组合的理论收益（基于超负荷进料量计算，不可执行），供横向对比装置负荷瓶颈。</p>
        </div>
      )}
      {/* ① 输入概览（算法第0步：给什么输入） */}
      <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm">
        <CardHead icon={Layers} title="输入概览" accent="from-slate-500 to-slate-600" hint="算法起点 · LP排产产出的离散化前总览" />
        <div className="mt-3 grid grid-cols-2 md:grid-cols-5 gap-3">
          <StatCell label="总天数" value={`${totalDays.toFixed(1)} 天`} />
          <StatCell label="总加工量" value={`${fNum(totalInput)} 吨`} />
          <StatCell label="原油种类" value={`${crudeCount} 种`} accent="text-slate-600" />
          <StatCell label="航煤工况" value={result.hangmei_summary?.enabled ? `目标 ${fNum(result.hangmei_summary.target)} 吨` : '未启用'} accent={result.hangmei_summary?.enabled ? 'text-sky-700' : 'text-slate-400'} />
          <StatCell label="装置停工" value={shutdownWindows.length > 0 ? `${shutdownWindows.length} 段` : '无'} accent={shutdownWindows.length > 0 ? 'text-rose-600' : 'text-slate-400'} />
        </div>
        {shutdownWindows.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {shutdownWindows.map((w, i) => (
              <span key={i} className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-rose-50 text-rose-600 text-[11px]">
                {w.unit_name} {(w.start_time || '').slice(5)} → {(w.end_time || '').slice(5)}
              </span>
            ))}
          </div>
        )}
      </div>

      {/* ② 批次划分（算法第1步：identify_batches 连续同油种聚合）— 默认折叠 */}
      <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
        <button onClick={() => setShowBatches(v => !v)}
          className="w-full flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60 hover:bg-slate-50 transition-colors">
          {showBatches ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
          <CardHead icon={Network} title="批次划分" accent="from-emerald-500 to-emerald-600"
            hint={`连续同油种聚合为 ${result.batches.length} 个批次`} />
          {!showBatches && (
            <span className="ml-auto text-[11px] text-slate-400 shrink-0">{result.batches.length} 批次 · {fNum(totalInput)} 吨 · {totalDays.toFixed(1)} 天</span>
          )}
        </button>
        {showBatches && (
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
              <th className="text-center font-medium px-3 py-2 w-12">#</th>
              <th className="text-left font-medium px-3 py-2">油种</th>
              <th className="text-center font-medium px-3 py-2">起止天</th>
              <th className="text-right font-medium px-3 py-2">天数</th>
              <th className="text-right font-medium px-3 py-2">加工量(吨)</th>
              <th className="text-right font-medium px-3 py-2">日均(吨/天)</th>
            </tr>
          </thead>
          <tbody>
            {result.batches.map(b => (
              <tr key={b.batch_id} className="border-b border-slate-50 last:border-0">
                <td className="px-3 py-2 text-center text-slate-400 font-mono text-xs">{b.batch_id}</td>
                <td className="px-3 py-2 text-slate-700">{crudeName(b.crude_type)}</td>
                <td className="px-3 py-2 text-center font-mono text-xs text-slate-600">{b.start_day}–{b.end_day}</td>
                <td className="px-3 py-2 text-right font-mono text-slate-600">{b.days.toFixed(2)}</td>
                <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(b.total_input)}</td>
                <td className="px-3 py-2 text-right font-mono text-slate-500">{b.days > 0 ? fNum(b.total_input / b.days) : '-'}</td>
              </tr>
            ))}
            <tr className="bg-slate-50/60 font-semibold">
              <td className="px-3 py-2 text-center text-slate-400 text-xs" colSpan={3}>合计</td>
              <td className="px-3 py-2 text-right font-mono text-slate-700">{totalDays.toFixed(2)}</td>
              <td className="px-3 py-2 text-right font-mono text-slate-800">{fNum(totalInput)}</td>
              <td className="px-3 py-2 text-right font-mono text-slate-500">{totalDays > 0 ? fNum(totalInput / totalDays) : '-'}</td>
            </tr>
          </tbody>
        </table>
        )}
      </div>

      {/* ③ 全部组合对比 · 上月价（选优阶段，算法第2-3步：枚举2n组合 + 逐组合评估） */}
      <div className="space-y-2">
        <div className="flex items-center gap-2 px-1">
          <span className="w-1.5 h-5 rounded-full bg-indigo-400" />
          <span className="text-[13px] font-semibold text-slate-700">
            全部组合对比 · 上月价{result.selection_price_month ? `（${result.selection_price_month}，选优）` : '（选优）'}
          </span>
          <span className="text-[11px] text-slate-400">点击行联动下方钻取明细，奖杯为选优组合</span>
        </div>
        <ComboComparePanel
          combos={result.combination_results}
          optimalId={optimalCombo?.combination_id ?? -1}
          selectedId={selectedCombo?.combination_id ?? null}
          onSelect={(id) => { setSelectedComboId(id); setActivePriceTab('selection') }}
          showAll={showAllCombos}
          onToggleShow={() => setShowAllCombos(v => !v)}
        />
      </div>

      {/* ③-2 全部组合对比 · 本月价（核算阶段，同一批组合用本月价重算，对比两套价格下的排序差异） */}
      {result.combination_results_final && result.combination_results_final.length > 0 && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 px-1">
            <span className="w-1.5 h-5 rounded-full bg-emerald-400" />
            <span className="text-[13px] font-semibold text-slate-700">
              全部组合对比 · 本月价{result.final_price_month ? `（${result.final_price_month}，核算）` : '（核算）'}
            </span>
            <span className="text-[11px] text-slate-400">奖杯为本月价各自选优，可与上月价表对比排序差异</span>
          </div>
          <ComboComparePanel
            combos={result.combination_results_final}
            optimalId={result.final_optimal_combo_id ?? -1}
            selectedId={selectedCombo?.combination_id ?? null}
            onSelect={(id) => { setSelectedComboId(id); setActivePriceTab('final') }}
            showAll={showAllCombos}
            onToggleShow={() => setShowAllCombos(v => !v)}
          />
        </div>
      )}

      {/* ③.5 双价格月：选方案(上月价) → 算效益(本月价) 两步对比（无可行方案时隐藏，避免 0→0 误导） */}
      {result.selection_revenue != null && result.selection_revenue > 0 && result.selection_price_month && result.final_price_month && (
        <div className="rounded-xl border border-[#E6EAF1] bg-gradient-to-br from-slate-50 to-white p-4 shadow-sm">
          <div className="flex items-center gap-2 mb-3">
            <span className="w-1.5 h-5 rounded-full bg-indigo-400" />
            <span className="text-[13px] font-semibold text-slate-700">双价格月口径 · 选方案 / 算效益 两步</span>
            <span className="text-[11px] text-slate-400">（减一线切换组合按上月价选优，选中方案按本月价核算实际效益）</span>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-[1fr_auto_1fr] items-center gap-4">
            {/* 选方案 */}
            <div className="rounded-lg border border-indigo-100 bg-indigo-50/50 p-3">
              <div className="flex items-center gap-1.5 mb-1">
                <TrendingUp className="w-3.5 h-3.5 text-indigo-500" />
                <span className="text-[11px] font-medium text-indigo-600">① 选方案 · {result.selection_price_month} 价格</span>
              </div>
              <div className="text-xl font-bold text-slate-900 font-mono">{fNum(result.selection_revenue / 10000)} <span className="text-sm font-normal text-slate-500">万元</span></div>
              <div className="text-[10px] text-slate-400 mt-0.5">上月价评估所有组合，选中组合 #{optimalCombo?.combination_id ?? '-'} 效益最优</div>
            </div>
            {/* 箭头 */}
            <div className="flex md:flex-col items-center justify-center text-slate-300">
              <ChevRight className="w-5 h-5" />
            </div>
            {/* 算效益 */}
            <div className="rounded-lg border border-emerald-100 bg-emerald-50/50 p-3">
              <div className="flex items-center gap-1.5 mb-1">
                <Wallet className="w-3.5 h-3.5 text-emerald-500" />
                <span className="text-[11px] font-medium text-emerald-600">② 算效益 · {result.final_price_month} 价格</span>
              </div>
              <div className="text-xl font-bold text-slate-900 font-mono">{fNum((result.optimal_revenue ?? 0) / 10000)} <span className="text-sm font-normal text-slate-500">万元</span></div>
              <div className="text-[10px] text-slate-400 mt-0.5">
                本月价核算实际效益，差额
                <span className={((result.optimal_revenue ?? 0) - result.selection_revenue) >= 0 ? 'text-emerald-600 font-medium' : 'text-rose-600 font-medium'}>
                  {' '}{((result.optimal_revenue ?? 0) - result.selection_revenue) >= 0 ? '+' : ''}{fNum(((result.optimal_revenue ?? 0) - result.selection_revenue) / 10000)} 万元
                </span>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ④ 选优结论 KPI（算法第4步：optimize_combinations 在可行组合中选收益最高） */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <KpiCard icon={Coins} label="当前组合效益" value={`${fNum(selectedCombo.total_revenue / 10000)} 万元`} sub={`${fNum(selectedCombo.total_revenue)} 元${selectedCombo.near_feasible ? ' (折减)' : selectedCombo.feasible === false ? ' (理论)' : ''} · ${activePriceTab === 'final' ? (result.final_price_month ?? '本月') : (result.selection_price_month ?? '上月')}价`} accent="from-blue-500 to-blue-600" />
        <KpiCard icon={Layers} label="切换组合数" value={`${result.total_combinations} 种`} sub={infeasibleCount > 0 || nearFeasibleCount > 0 ? `可行 ${feasibleCount} · 接近可行 ${nearFeasibleCount} · 不可行 ${infeasibleCount}` : `全部可行 ${feasibleCount} 种`} accent="from-purple-500 to-purple-600" />
        <KpiCard icon={Network} label="加工批次" value={`${result.batches.length} 个`} sub="连续同油种聚合" accent="from-emerald-500 to-emerald-600" />
        <KpiCard icon={Timer} label="求解耗时" value={`${(elapsedMs / 1000).toFixed(2)} 秒`} sub={hasFeasible ? `组合 ${optimalCombo!.combination_id} ${isNearOptimal ? '接近可行' : isTemporary ? '临时可行' : '为最优'}` : `理论最高 组合#${fallbackCombo?.combination_id}`} accent="from-amber-500 to-orange-500" />
      </div>

      {/* ⑤ 钻取：选中组合明细（点上方对比表行联动，三块聚一起） */}
      <div className="space-y-4">
        <div className="flex items-center gap-2 px-1">
          <span className={`w-1.5 h-5 rounded-full ${optimalCombo && selectedCombo.combination_id === optimalCombo.combination_id ? 'bg-amber-400' : selectedCombo.near_feasible ? 'bg-amber-400' : selectedCombo.feasible === false ? 'bg-rose-400' : 'bg-blue-400'}`} />
          <span className="text-[13px] font-semibold text-slate-700">
            选中组合 #{selectedCombo.combination_id} 钻取明细
            {optimalCombo && selectedCombo.combination_id === optimalCombo.combination_id && <span className="ml-2 text-amber-600">· {isNearOptimal ? '接近可行' : isTemporary ? '临时可行' : '最优'}</span>}
            {selectedCombo.near_feasible && !(optimalCombo && selectedCombo.combination_id === optimalCombo.combination_id) && <span className="ml-2 text-amber-600">· 接近可行</span>}
            {selectedCombo.feasible === false && !selectedCombo.near_feasible && <span className="ml-2 text-rose-500">· 不可行(理论)</span>}
            <span className="ml-2 text-[11px] text-slate-400">· {activePriceTab === 'final' ? (result.final_price_month ?? '本月') : (result.selection_price_month ?? '上月')}价</span>
          </span>
        </div>

        {/* ⑤a 批次时间线（每行可展开看按天明细 daily_inputs） */}
        <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
          <button onClick={() => setShowTimeline(v => !v)}
            className="w-full flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60 hover:bg-slate-50 transition-colors">
            {showTimeline ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
            <CardHead
              icon={Network}
              title={`组合 #${selectedCombo.combination_id} · 批次时间线`}
              accent="from-blue-500 to-blue-600"
              hint={`总效益 ${fNum(selectedCombo.total_revenue)} 元${selectedCombo.feasible === false ? ' (理论)' : ''} · 点击批次行展开按天明细`}
            />
            {!showTimeline && (
              <span className="ml-auto text-[11px] text-slate-400 shrink-0">{selectedCombo.batch_results.length} 批次</span>
            )}
          </button>
          {showTimeline && (
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
                <th className="text-center font-medium px-3 py-2 w-12">#</th>
                <th className="text-left font-medium px-3 py-2">油种</th>
                <th className="text-center font-medium px-3 py-2">起止天</th>
                <th className="text-right font-medium px-3 py-2">加工量(吨)</th>
                <th className="text-center font-medium px-3 py-2">减一线方向</th>
                <th className="text-right font-medium px-3 py-2">→柴加(吨)</th>
                <th className="text-right font-medium px-3 py-2">→蜡加(吨)</th>
                <th className="text-right font-medium px-3 py-2">批次效益(元)</th>
              </tr>
            </thead>
            <tbody>
              {selectedCombo.batch_results.map(b => {
                const isExp = expandedBatch === b.batch_id
                // 同批次 id 在 result.batches 里查 daily_inputs（combination_results 的 batch_results 不含它）
                // result.batches 的停工段子批次带 shutdown_intervals 字段
                const batchMeta = result.batches.find(x => x.batch_id === b.batch_id) as SolveBatchWithShutdown | undefined
                const shutdownDevs = batchMeta?.shutdown_intervals ? Object.keys(batchMeta.shutdown_intervals) : []
                const isShutdown = shutdownDevs.length > 0
                return (
                  <FragmentRow key={b.batch_id}>
                    <tr
                      className={`border-b border-slate-50 last:border-0 hover:bg-slate-50/40 cursor-pointer ${
                        isExp ? 'bg-blue-50/40' : isShutdown ? 'bg-red-50/30' : ''
                      } ${b.is_hangmei_period && !isExp && !isShutdown ? 'bg-sky-50/40' : ''}`}
                      onClick={() => setExpandedBatch(isExp ? null : b.batch_id)}
                    >
                      <td className="px-3 py-2 text-center text-slate-400 font-mono text-xs">
                        {isExp ? <ChevRight className="w-3 h-3 inline -rotate-90 text-slate-400" /> : <ChevRight className="w-3 h-3 inline rotate-90 text-slate-300" />}
                        {b.batch_id}
                        {isShutdown && (
                          <span className="block mt-1 text-[9px] text-red-600 leading-tight">
                            {shutdownDevs.includes(DEV_LYJQ) ? '蜡加停' : '柴加停'}
                          </span>
                        )}
                        {b.is_hangmei_period && (
                          <span className="inline-block mt-1 px-1.5 py-0.5 rounded bg-sky-100 text-sky-700 text-[9px] font-medium leading-tight">航煤期</span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-slate-700">{crudeName(b.crude_type)}</td>
                      <td className="px-3 py-2 text-center font-mono text-xs text-slate-600">{b.start_day}–{b.end_day}</td>
                      <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(b.total_input)}</td>
                      <td className="px-3 py-2 text-center">
                        <span className={`inline-block px-2 py-0.5 rounded text-[11px] font-medium ${b.mode === 'X_ZERO' ? 'bg-purple-50 text-purple-700' : 'bg-blue-50 text-blue-700'}`}>
                          {MODE_CN[b.mode] || b.mode}
                        </span>
                      </td>
                      <td className="px-3 py-2 text-right font-mono text-blue-700">{b.jian1_to_diesel > 0 ? fNum(b.jian1_to_diesel) : '—'}</td>
                      <td className="px-3 py-2 text-right font-mono text-purple-700">{b.jian1_to_wax > 0 ? fNum(b.jian1_to_wax) : '—'}</td>
                      <td className={`px-3 py-2 text-right font-mono ${b.revenue < 0 ? 'text-red-600' : 'text-emerald-700'}`}>{fNum(b.revenue)}</td>
                    </tr>
                    {isExp && batchMeta && (
                      <tr className="bg-slate-50/50">
                        <td colSpan={8} className="px-6 py-3">
                          <div className="text-[11px] text-slate-500 mb-2">按天加工明细（共 {batchMeta.days} 天）</div>
                          <div className="grid grid-cols-7 md:grid-cols-10 gap-1.5">
                            {batchMeta.daily_inputs.map((tons, i) => (
                              <div key={i} className="rounded border border-slate-200 bg-white px-2 py-1 text-center">
                                <div className="text-[10px] text-slate-400">第{batchMeta.start_day + i}天</div>
                                <div className="text-[11px] font-mono text-slate-700">{fNum(tons)}</div>
                              </div>
                            ))}
                          </div>
                        </td>
                      </tr>
                    )}
                  </FragmentRow>
                )
              })}
            </tbody>
          </table>
          )}
        </div>

        {/* ⑤b 月度装置负荷 & 罐容平均库存（随选中组合联动）— 默认折叠 */}
        {selectedCombo?.monthly_load && (
          <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
            <button onClick={() => setShowMonthlyLoad(v => !v)}
              className="w-full flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60 hover:bg-slate-50 transition-colors">
              {showMonthlyLoad ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
              <CardHead icon={Gauge} title="月度装置负荷" accent="from-indigo-500 to-blue-600"
                hint={`月度口径 · ${selectedCombo.monthly_load.total_days.toFixed(1)} 天`} />
              {!showMonthlyLoad && (
                <span className={`ml-auto inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium shrink-0 ${
                  selectedCombo.monthly_load.overload_count > 0 ? 'bg-rose-100 text-rose-600' : 'bg-emerald-100 text-emerald-700'
                }`}>
                  {selectedCombo.monthly_load.overload_count > 0 ? `月度超容 ${selectedCombo.monthly_load.overload_count} 台` : '月度全部未超容'}
                </span>
              )}
            </button>
            {showMonthlyLoad && (
              <MonthlyLoadPanel data={selectedCombo.monthly_load} batchDetails={selectedCombo.batch_details} bare />
            )}
          </div>
        )}

        {/* ⑤b-2 罐容段级检测（基于工况切换时间点的段级库存推演与安全阈值检测） */}
        {selectedCombo.tank_check_result && selectedCombo.tank_check_result.tank_trajectories?.length > 0 && (
          <TankCheckPanel data={selectedCombo.tank_check_result} />
        )}

        {/* ⑤c 计算过程（每批次一张可折叠流程卡：投入→常减压负荷→减一线→柴加/蜡加→收率→批次效益）
            精简模式下组合内 batch_details 被剔除，回退到顶层 optimal_batch_details（仅最优组合可用） */}
        {(() => {
          // 精简模式回退：选中组合无 batch_details 时，用顶层最优解明细兜底（仅当选中的就是最优组合）
          const isOptimalSelected = !!optimalCombo && selectedCombo.combination_id === optimalCombo.combination_id
          const details = selectedCombo.batch_details && selectedCombo.batch_details.length > 0
            ? selectedCombo.batch_details
            : (isOptimalSelected ? (result.optimal_batch_details ?? []) : [])
          return details.length > 0 ? (
            <CalcProcessSection
              details={details}
              crudeName={crudeName}
              isOptimal={isOptimalSelected}
              comboId={selectedCombo.combination_id}
            />
          ) : null
        })()}
      </div>

      {/* ⑥ 经济解读（减一线分流 + 航煤工况 + 效益说明） */}
      {result.hangmei_summary?.enabled && <HangmeiCard hm={result.hangmei_summary} />}
      <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm">
        <CardHead icon={TrendingUp} title="减一线月度分流" accent="from-blue-500 to-blue-600" hint="最优组合的减一线去向" />
        <div className="mt-3 space-y-2">
          {jian1Total > 0 ? (
            <>
              <div className="flex h-7 rounded-md overflow-hidden border border-slate-200">
                <div className="bg-gradient-to-r from-blue-400 to-blue-500 grid place-items-center text-white text-xs font-medium"
                  style={{ width: `${(xTons / jian1Total) * 100}%` }}>
                  {xTons / jian1Total > 0.12 ? `柴加 ${fNum(xTons)}吨` : ''}
                </div>
                <div className="bg-gradient-to-r from-purple-400 to-purple-500 grid place-items-center text-white text-xs font-medium"
                  style={{ width: `${(yTons / jian1Total) * 100}%` }}>
                  {yTons / jian1Total > 0.12 ? `蜡加 ${fNum(yTons)}吨` : ''}
                </div>
              </div>
              <div className="flex justify-between text-[11px] text-slate-500">
                <span>柴油加氢：<span className="font-mono text-blue-700">{fNum(xTons)}</span> 吨（{(xTons / jian1Total * 100).toFixed(1)}%）</span>
                <span>蜡油加氢：<span className="font-mono text-purple-700">{fNum(yTons)}</span> 吨（{(yTons / jian1Total * 100).toFixed(1)}%）</span>
                <span>合计：<span className="font-mono text-slate-700">{fNum(jian1Total)}</span> 吨</span>
              </div>
            </>
          ) : (
            <div className="text-xs text-slate-400">本月无可分流减一线产出</div>
          )}
        </div>
      </div>
      {/* 减一线切换点供需分析 */}
      {result.jian1_switch_analysis && result.jian1_switch_analysis.switch_day > 0 && (
        <Jian1SwitchCard analysis={result.jian1_switch_analysis} />
      )}
      {result.economic_explanation && (
        <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
          <button onClick={() => setShowEconReport(v => !v)}
            className="w-full flex items-center gap-2 px-5 py-4 border-b border-slate-100 hover:bg-slate-50/60 transition-colors">
            {showEconReport ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
            <CardHead icon={Coins} title="经济效益分析说明" accent="from-blue-500 to-blue-600" />
            {!showEconReport && <span className="ml-auto text-[11px] text-slate-400 shrink-0">点击展开详情</span>}
          </button>
          {showEconReport && (
            <div className="px-5 py-3 space-y-2">
              <EconReport text={result.economic_explanation} />
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ── ④ 全部组合对比（可行/不可行 + 瓶颈列 + 点击联动选中组合） ───────────
// 可行组合按总效益降序在前，不可行组合在后；可行行可点击切换"选中组合"，
// 联动上方批次时间线 ③ 与下方计算过程 ⑥。瓶颈列按余量紧张程度着色。
function ComboComparePanel({ combos, optimalId, selectedId, onSelect, showAll, onToggleShow }: {
  combos: ComboResult[]
  optimalId: number
  selectedId: number | null
  onSelect: (id: number) => void
  showAll: boolean
  onToggleShow: () => void
}) {
  // 三分类：可行(feasible=true) / 接近可行(near_feasible) / 不可行(其余)
  const feasible = combos.filter(c => c.feasible === true || (c.feasible !== false && !c.near_feasible))
  const nearFeasible = combos.filter(c => c.near_feasible === true)
  const infeasible = combos.filter(c => c.feasible === false && !c.near_feasible)
  // 排序：可行 > 接近可行 > 不可行，各类内按效益降序
  const sorted = [
    ...feasible.sort((a, b) => b.total_revenue - a.total_revenue),
    ...nearFeasible.sort((a, b) => b.total_revenue - a.total_revenue),
    ...infeasible.sort((a, b) => b.total_revenue - a.total_revenue),
  ]
  // 动态装置列表：从各组合的 monthly_load.devices 聚合，按 CJY→CYJQ→LYJQ→其他 顺序
  const devOrder = (id: string) => id === DEV_CJY ? 0 : id === DEV_CYJQ ? 1 : id === DEV_LYJQ ? 2 : 3
  const devMap = new Map<string, string>()
  for (const c of combos) {
    for (const d of c.monthly_load?.devices ?? []) {
      if (!devMap.has(d.device_id)) devMap.set(d.device_id, d.name || d.device_id)
    }
  }
  const devCols = Array.from(devMap.entries())
    .sort((a, b) => devOrder(a[0]) - devOrder(b[0]))
    .map(([id, name]) => ({ id, name }))
  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <button onClick={onToggleShow}
        className="w-full flex items-center gap-2 px-4 py-3 border-b border-slate-100 hover:bg-slate-50/60 transition-colors">
        {showAll ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
        <span className="w-7 h-7 rounded-lg grid place-items-center bg-gradient-to-br from-purple-500 to-purple-600 shadow-sm shrink-0">
          <Layers className="w-4 h-4 text-white" />
        </span>
        <span className="text-[13px] font-semibold text-slate-800">全部组合对比</span>
        <span className="text-[11px] text-slate-400">
          共 {combos.length} 种
          {(nearFeasible.length > 0 || infeasible.length > 0) && (
            <> · 可行 <span className="text-emerald-600 font-medium">{feasible.length}</span> · 接近可行 <span className="text-amber-600 font-medium">{nearFeasible.length}</span> · 不可行 <span className="text-rose-500 font-medium">{infeasible.length}</span></>
          )}
        </span>
        <span className="ml-auto text-[11px] text-slate-400">点击组合行 → 联动批次时间线/计算过程（不可行组合显示理论收益）</span>
      </button>
      {showAll && (
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
              <th className="text-center font-medium px-3 py-2 w-12">组合</th>
              <th className="text-left font-medium px-3 py-2">切换路径（每格=1批次，紫=蜡加 蓝=柴加）</th>
              <th className="text-center font-medium px-3 py-2">初始方向</th>
              <th className="text-center font-medium px-3 py-2">可行性</th>
              <th className="text-left font-medium px-3 py-2">月度瓶颈装置</th>
              {devCols.map(dc => (
                <th key={dc.id} className="text-right font-medium px-3 py-2">{dc.name}</th>
              ))}
              <th className="text-right font-medium px-3 py-2">总效益(元)</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map(c => {
              const isOpt = c.combination_id === optimalId
              const isSel = c.combination_id === selectedId
              const isFeas = c.feasible !== false
              const isNear = c.near_feasible === true
              const isTemp = c.temporary_feasible === true
              // 未加工完的中间料量：月负荷超容装置的 unprocessed_material 汇总
              const mlDevs = c.monthly_load?.devices ?? []
              const unprocessedTotal = mlDevs
                .filter(d => d.is_overloaded && (d.unprocessed_material ?? 0) > 0)
                .reduce((s, d) => s + (d.unprocessed_material ?? 0), 0)
              const mlBottleneck = mlDevs[0] // devices 已按 monthly_util 降序
              const mlOver = mlBottleneck?.is_overloaded
              const mlTone = !mlBottleneck ? 'emerald'
                : mlOver ? 'rose'
                : (mlBottleneck.monthly_util >= 95) ? 'amber'
                : 'emerald'
              const mlToneText = { rose: 'text-rose-600', amber: 'text-amber-600', emerald: 'text-emerald-600' }[mlTone]
              const mlToneBg = { rose: 'bg-rose-50', amber: 'bg-amber-50', emerald: 'bg-emerald-50' }[mlTone]
              return (
                <tr key={c.combination_id}
                  className={`border-b border-slate-50 last:border-0 ${
                    isNear ? (isSel ? 'bg-amber-50/60 ring-1 ring-inset ring-amber-300' : isOpt ? 'bg-amber-50/40' : 'hover:bg-amber-50/30 cursor-pointer')
                    : !isFeas ? (isSel ? 'bg-rose-50/40 ring-1 ring-inset ring-rose-200' : 'bg-slate-50/40 text-slate-400 hover:bg-rose-50/30 cursor-pointer')
                    : isTemp ? (isSel ? 'bg-amber-50/60 ring-1 ring-inset ring-amber-300' : isOpt ? 'bg-amber-50/40' : 'hover:bg-amber-50/30 cursor-pointer')
                    : isSel ? 'bg-blue-50/60 ring-1 ring-inset ring-blue-300'
                    : isOpt ? 'bg-amber-50/40'
                    : 'hover:bg-slate-50/40 cursor-pointer'
                  }`}
                  onClick={() => onSelect(c.combination_id)}
                >
                  <td className="px-3 py-2 text-center">
                    {isOpt ? <span className="inline-flex items-center gap-1 text-amber-600 font-bold"><Trophy className="w-3.5 h-3.5" />{c.combination_id}</span>
                           : isSel ? <span className="inline-flex items-center gap-1 text-blue-600 font-bold">{c.combination_id}</span>
                           : <span className="text-slate-400 font-mono text-xs">{c.combination_id}</span>}
                  </td>
                  <td className="px-3 py-2 text-xs">
                    {(() => {
                      // 可视化切换路径：每批次一格色块，X_ZERO=紫(蜡加) Y_ZERO=蓝(柴加)，切换边界竖线标记
                      const sw = c.switches || {}
                      const ids = Object.keys(sw).map(Number).sort((a, b) => a - b)
                      const sp = c.switch_position ?? 0
                      return (
                        <div className="flex items-center gap-0.5" title={c.description}>
                          {ids.map((bid, i) => {
                            const mode = sw[bid]
                            const isWax = mode === 'X_ZERO'
                            const isSwitch = sp > 0 && i === sp
                            return (
                              <span key={bid} className="relative">
                                {isSwitch && <span className="absolute -left-1 top-1/2 -translate-y-1/2 text-amber-500 text-[10px] font-bold leading-none">▶</span>}
                                <span className={`inline-flex items-center justify-center w-6 h-5 rounded text-[9px] font-medium text-white ${
                                  isWax ? 'bg-gradient-to-br from-purple-400 to-purple-500' : 'bg-gradient-to-br from-blue-400 to-blue-500'
                                }`} title={`批次${bid}: ${MODE_SHORT[mode] || mode}`}>
                                  {bid}
                                </span>
                              </span>
                            )
                          })}
                        </div>
                      )
                    })()}
                  </td>
                  <td className="px-3 py-2 text-center"><span className="text-[11px] text-slate-500">{MODE_SHORT[c.initial_mode] || c.initial_mode}</span></td>
                  <td className="px-3 py-2 text-center">
                    {(() => {
                      const tankViol = c.tank_check_result?.violation_count ?? 0
                      const tankHasV = (c.tank_check_result?.has_violations ?? false) || tankViol > 0
                      const tankTip = tankHasV ? `罐容违规 ${tankViol} 处` : '罐容无违规'
                      return isNear
                        ? <div className="flex flex-col items-center gap-0.5" title={c.infeasible_summary}>
                            <span className="inline-flex items-center gap-0.5 text-[11px] text-amber-600 font-medium">≈ 接近可行</span>
                            <span className={`text-[9px] ${tankHasV ? 'text-rose-500' : 'text-emerald-500'}`}>{tankHasV ? `罐${tankViol}` : '罐✓'}</span>
                            {unprocessedTotal > 0 && (
                              <span className="text-[9px] text-amber-700 font-mono" title="月负荷超容装置未加工完的中间料量">未加工 {fNum(unprocessedTotal)}t</span>
                            )}
                          </div>
                        : isTemp
                        ? <div className="flex flex-col items-center gap-0.5" title={c.infeasible_summary}>
                            <span className="inline-flex items-center gap-0.5 text-[11px] text-amber-600 font-medium">⚠ 临时可行</span>
                            <span className={`text-[9px] ${tankHasV ? 'text-rose-500' : 'text-emerald-500'}`}>{tankHasV ? `罐${tankViol}` : '罐✓'}</span>
                          </div>
                        : isFeas
                        ? <div className="flex flex-col items-center gap-0.5">
                            <span className="inline-flex items-center gap-0.5 text-[11px] text-emerald-600 font-medium">✓ 可行</span>
                            <span className={`text-[9px] ${tankHasV ? 'text-rose-500' : 'text-emerald-500'}`} title={tankTip}>{tankHasV ? `罐${tankViol}` : '罐✓'}</span>
                          </div>
                        : <div className="flex flex-col items-center gap-0.5" title={c.infeasible_summary || tankTip}>
                            <span className="inline-flex items-center gap-0.5 text-[11px] text-rose-500 font-medium">✗ 不可行</span>
                            <span className={`text-[9px] ${tankHasV ? 'text-rose-500 font-medium' : 'text-emerald-500'}`}>{tankHasV ? `罐${tankViol}` : '罐✓'}</span>
                          </div>
                    })()}
                  </td>
                  <td className="px-3 py-2 text-xs">
                    {mlBottleneck ? (
                      <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded ${mlToneBg} ${mlToneText}`} title={c.monthly_load?.summary}>
                        {mlBottleneck.name}
                        <span className="font-mono">{mlBottleneck.monthly_util.toFixed(0)}%</span>
                        {mlOver
                          ? <span className="text-[9px]">超容</span>
                          : mlBottleneck.monthly_util >= 95
                          ? <span className="text-[9px]">偏紧</span>
                          : <span className="text-[9px]">充足</span>}
                      </span>
                    ) : (
                      <span className="text-slate-300">—</span>
                    )}
                  </td>
                  {(() => {
                    const m = c.monthly_load
                    const find = (id: string) => m?.devices.find(d => d.device_id === id)
                    const cell = (d?: MonthlyDevice) => d ? (
                      <span className={`font-mono ${d.is_overloaded ? 'text-rose-600 font-bold' : 'text-slate-500'}`} title={`${d.name}: 月度加工量 ${d.monthly_input.toFixed(0)} / 月度能力 ${d.monthly_capacity.toFixed(0)}`}>
                        {d.monthly_util.toFixed(2)}%{d.is_overloaded && '⚠'}
                      </span>
                    ) : <span className="text-slate-300 font-mono">—</span>
                    return (
                      <>
                        {devCols.map(dc => (
                          <td key={dc.id} className="px-3 py-2 text-right text-[11px]">{cell(find(dc.id))}</td>
                        ))}
                      </>
                    )
                  })()}
                  <td className={`px-3 py-2 text-right font-mono ${!isFeas ? 'text-slate-400' : isOpt ? 'text-amber-700 font-bold' : isSel ? 'text-blue-700 font-semibold' : 'text-slate-700'}`}>
                    {isFeas
                      ? fNum(c.total_revenue)
                      : <span title="超容组合的理论收益，基于超负荷进料量计算，不可执行">{fNum(c.total_revenue)}<span className="text-[9px] text-slate-400 ml-0.5">(理论)</span></span>}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </div>
  )
}

// ── ⑤' 月度装置负荷 & 罐容平均库存（展示+检测层）──────────────────────────
// 与单批次日均校验互补：月度平均未超容 → 实际可逐日微调进料，单批次超容仅提示。
// 装置：月度加工量 vs 月度能力(=日阈值×天数)，检测月度是否超容；
// 月度装置负荷（展示+检测层）。
function MonthlyLoadPanel({ data, batchDetails, bare = false }: { data: MonthlyLoad; batchDetails?: CalcBatchDetail[]; bare?: boolean }) {
  const [expandedDev, setExpandedDev] = useState<string | null>(null)
  const td = data.total_days || 1

  // 逐批贡献（前端从 batchDetails 现算，供展开自验证）
  // 双口径与批次详情卡(DeviceCalcBlock)同源：
  //   连接主料 = device_utilization[did].input（H 主料口径）
  //   主料负荷量 = feed_details items 中 label='主料' 的 feed_qty 之和
  // 月度负荷计算以主料负荷量为准，连接主料作为参考
  // 提取装置连接主料名（取第一个有效批次的最大主料名）
  const devMainName = (did: string): string => {
    for (const b of batchDetails || []) {
      const items = b.feed_details?.find(fd => fd.device_id === did)?.items ?? []
      const mains = items.filter(i => i.label === '主料')
      if (mains.length > 0) {
        return mains.reduce((max, i) => i.feed_qty > max.feed_qty ? i : max).name
      }
    }
    return ''
  }
  const devBatches = (did: string) => (batchDetails || []).map(b => {
    const days = b.costs?.days || 0
    const connMain = b.device_utilization?.[did]?.input ?? 0           // 连接主料(H) 日值
    const feedItems = b.feed_details?.find(fd => fd.device_id === did)?.items ?? []
    const mainLoadBatch = feedItems.filter(i => i.label === '主料').reduce((s, i) => s + i.feed_qty, 0)  // 主料负荷量 批次值
    const daily = days > 0 ? mainLoadBatch / days : 0                  // 日值
    return { batch_id: b.batch_id, days, connMain, mainLoad: daily, daily, contrib: mainLoadBatch }
  })
  // bare 模式：跳过外层卡片边框+标题（由调用方折叠容器提供），只渲染内部内容
  const wrapperClass = bare ? 'p-4' : 'p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm'

  return (
    <div className={wrapperClass}>
      {!bare && (
        <CardHead icon={Gauge} title="月度装置负荷" accent="from-indigo-500 to-blue-600"
          hint={`月度口径 · 共 ${td.toFixed(1)} 天 · 点击展开看逐批计算`}
          right={
            <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium ${
              data.overload_count > 0 ? 'bg-rose-100 text-rose-600' : 'bg-emerald-100 text-emerald-700'
            }`}>
              {data.overload_count > 0 ? `月度超容 ${data.overload_count} 台` : '月度全部未超容'}
            </span>
          } />
      )}

      {/* 装置区：紧凑可展开行 */}
      <div className="mt-3 space-y-1.5">
        <div className="text-[12px] font-semibold text-slate-600 flex items-center gap-1.5">
          <Factory className="w-3.5 h-3.5 text-indigo-500" /> 装置月度负荷
        </div>
        {data.devices.length === 0 ? (
          <div className="text-xs text-slate-400">无装置数据</div>
        ) : data.devices.map(d => {
          const u = d.monthly_util
          const open = expandedDev === d.device_id
          const color = d.is_overloaded
            ? 'bg-gradient-to-r from-rose-500 to-red-500'
            : u >= 99 ? 'bg-gradient-to-r from-emerald-500 to-emerald-600'
            : u >= 80 ? 'bg-gradient-to-r from-cyan-500 to-teal-500'
            : 'bg-gradient-to-r from-amber-400 to-amber-500'
          return (
            <div key={d.device_id} className="rounded-lg border border-slate-100 overflow-hidden">
              <button onClick={() => setExpandedDev(open ? null : d.device_id)}
                className="w-full flex items-center gap-2 px-2.5 py-1.5 hover:bg-slate-50/60 transition-colors text-left">
                {open ? <ChevronDown className="w-3.5 h-3.5 text-slate-400 shrink-0" /> : <ChevRight className="w-3.5 h-3.5 text-slate-400 shrink-0" />}
                <span className="text-[12px] text-slate-700 font-medium w-20 shrink-0">{d.name}</span>
                <div className="flex-1 h-2 rounded-full bg-slate-100 overflow-hidden">
                  <div className={`h-full rounded-full ${color}`} style={{ width: `${Math.min(u, 100)}%` }} />
                </div>
                <span className={`font-mono text-[11px] font-bold w-12 text-right ${d.is_overloaded ? 'text-rose-600' : u >= 99 ? 'text-emerald-700' : 'text-cyan-700'}`}>{u.toFixed(1)}%</span>
                {d.is_overloaded && <span className="text-[10px] text-rose-500 font-medium shrink-0">⚠超</span>}
              </button>
              {open && (
                <div className="px-3 pb-2.5 pt-0.5 bg-slate-50/40 border-t border-slate-100">
                  <div className="text-[10px] text-slate-500 mt-1.5 mb-1">
                    月加工量 = Σ(主料负荷量 × 天数) = <span className="font-mono font-bold text-slate-700">{fNum(d.monthly_input)}</span> 吨
                    ／ 月度能力 = 日阈值 {fNum(d.monthly_capacity / td)} × {td.toFixed(1)}天 = <span className="font-mono text-slate-700">{fNum(d.monthly_capacity)}</span> 吨
                    {d.is_overloaded
                      ? <span className="text-rose-500"> · 超 {fNum(d.monthly_input - d.monthly_capacity)} 吨</span>
                      : <span className="text-emerald-600"> · 余 {fNum(d.monthly_capacity - d.monthly_input)} 吨</span>}
                  </div>
                  {batchDetails ? (
                    <table className="w-full text-[10px] font-mono">
                      <thead>
                        <tr className="text-slate-400 border-b border-slate-200">
                          <th className="text-left font-medium py-1">批次</th>
                          <th className="text-right font-medium py-1">天数</th>
                          <th className="text-right font-medium py-1">{`连接主料${devMainName(d.device_id) ? `（${devMainName(d.device_id)}）` : ''}(吨/天)`}</th>
                          <th className="text-right font-medium py-1">主料负荷量(吨/天)</th>
                          <th className="text-right font-medium py-1">贡献量(吨)</th>
                        </tr>
                      </thead>
                      <tbody>
                        {devBatches(d.device_id).map(r => (
                          <tr key={r.batch_id} className="border-b border-slate-100 last:border-0">
                            <td className="py-0.5 text-slate-600">#{r.batch_id}</td>
                            <td className="py-0.5 text-right text-slate-600">{r.days.toFixed(2)}</td>
                            <td className="py-0.5 text-right text-slate-500">{fNum(r.connMain)}</td>
                            <td className="py-0.5 text-right text-slate-700">{fNum(r.mainLoad)}</td>
                            <td className="py-0.5 text-right text-slate-800 font-semibold">{fNum(r.contrib)}</td>
                          </tr>
                        ))}
                        <tr className="border-t-2 border-slate-300">
                          <td className="py-1 text-slate-500" colSpan={4}>合计</td>
                          <td className="py-1 text-right text-slate-900 font-bold">{fNum(d.monthly_input)}</td>
                        </tr>
                      </tbody>
                    </table>
                  ) : <div className="text-[10px] text-slate-400">无批次明细数据</div>}
                </div>
              )}
            </div>
          )
        })}
      </div>

      <div className="mt-3 text-[11px] text-slate-500 bg-slate-50/60 rounded px-2.5 py-1.5 border border-slate-100">
        {data.summary}
      </div>
    </div>
  )
}

// ── ⑤b-2 罐容段级检测面板 ──────────────────────────────────────────────
function TankCheckPanel({ data }: { data: TankCheckResult }) {
  const [showChart, setShowChart] = useState(false)
  const [showViolations, setShowViolations] = useState(false)
  const { tank_trajectories, violations, violation_count, has_violations, segments } = data

  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <button onClick={() => setShowChart(v => !v)}
        className="w-full flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60 hover:bg-slate-50 transition-colors">
        {showChart ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
        <CardHead icon={Database} title="罐容段级检测" accent="from-amber-500 to-orange-600"
          hint={`段级库存推演 · ${segments.length}段 · ${tank_trajectories.length}个中间罐`} />
        <span className={`ml-auto inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium shrink-0 ${
          has_violations ? 'bg-rose-100 text-rose-600' : 'bg-emerald-100 text-emerald-700'
        }`}>
          {has_violations ? `${violation_count} 处违规` : '无违规'}
        </span>
      </button>

      {showChart && (
        <div className="p-4 space-y-3">
          {/* 说明栏 */}
          <div className="flex items-center gap-2 text-xs text-slate-500 bg-slate-50/80 rounded-lg px-3 py-2">
            <AlertTriangle className="w-3.5 h-3.5 text-amber-500 shrink-0" />
            <span>基于工况切换时间点（油种/减一线/航煤/停工）构建段级库存推演。当前仅检测展示，不做可行性判定。</span>
          </div>

          {/* 每个罐一个独立图表 */}
          <div className="space-y-4">
            {tank_trajectories.map(t => (
              <TankTrajectoryChart key={t.tank_id} trajectory={t} segments={segments} />
            ))}
          </div>

          {/* 罐状态汇总（含0违规罐） */}
          <div className="flex flex-wrap items-center gap-2">
            {tank_trajectories.map(t => {
              const vCount = t.violations?.length ?? 0
              return (
                <span key={t.tank_id} className={`inline-flex items-center gap-1 px-2 py-1 rounded-full text-[11px] font-medium ${
                  vCount > 0 ? 'bg-rose-100 text-rose-600' : 'bg-emerald-100 text-emerald-700'
                }`}>
                  <Database className="w-3 h-3" />
                  {t.tank_name || t.tank_id}
                  <span className="font-mono">{vCount > 0 ? `${vCount}处违规` : '无违规'}</span>
                </span>
              )
            })}
          </div>

          {/* 违规列表 */}
          {has_violations && (
            <div className="border border-rose-200 rounded-lg overflow-hidden">
              <button onClick={() => setShowViolations(v => !v)}
                className="w-full flex items-center gap-2 px-3 py-2 bg-rose-50 hover:bg-rose-100 transition-colors">
                {showViolations ? <ChevronDown className="w-3.5 h-3.5 text-rose-500" /> : <ChevRight className="w-3.5 h-3.5 text-rose-500" />}
                <span className="text-sm font-medium text-rose-700">罐容违规明细（{violation_count} 处）</span>
              </button>
              {showViolations && (
                <div className="overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead className="bg-rose-50/50 text-slate-600">
                      <tr>
                        <th className="px-3 py-2 text-left font-medium">罐名</th>
                        <th className="px-3 py-2 text-right font-medium">时段(天)</th>
                        <th className="px-3 py-2 text-right font-medium">库存量</th>
                        <th className="px-3 py-2 text-right font-medium">阈值</th>
                        <th className="px-3 py-2 text-right font-medium">超出量</th>
                        <th className="px-3 py-2 text-center font-medium">类型</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {violations.map((v, i) => (
                        <tr key={i} className="hover:bg-rose-50/30">
                          <td className="px-3 py-2 font-medium text-slate-700">{v.tank_name}</td>
                          <td className="px-3 py-2 text-right text-slate-500">{v.start_day.toFixed(1)}–{v.end_day.toFixed(1)}</td>
                          <td className="px-3 py-2 text-right font-medium text-rose-600">{fNum(v.capacity)}</td>
                          <td className="px-3 py-2 text-right text-slate-500">{fNum(v.threshold)}</td>
                          <td className="px-3 py-2 text-right font-medium text-rose-600">+{fNum(v.severity)}</td>
                          <td className="px-3 py-2 text-center">
                            <span className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-medium ${
                              v.violation_type === 'over_high' ? 'bg-rose-100 text-rose-600' : 'bg-amber-100 text-amber-600'
                            }`}>
                              {v.violation_type === 'over_high' ? '超上限' : '低于下限'}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// 单个罐的库存轨迹图
function TankTrajectoryChart({ trajectory, segments }: { trajectory: TankTrajectory; segments: TankSegment[] }) {
  const t = trajectory
  const hasV = t.violations.length > 0

  // 构建折线数据：每段的 [start_day, start_cap] 和 [end_day, end_cap]
  // 用 type:'value' 的 xAxis，数据点为 [天数, 库存量]
  const lineData: [number, number][] = []
  if (t.points.length > 0) {
    lineData.push([t.points[0].start_day, t.points[0].start_cap])
    for (const p of t.points) {
      lineData.push([p.end_day, p.end_cap])
    }
  }

  // 违规段背景 markArea
  const markAreaData = t.violations.map(v => [
    { xAxis: v.start_day, itemStyle: { color: v.violation_type === 'over_high' ? 'rgba(244,63,94,0.08)' : 'rgba(245,158,11,0.08)' } },
    { xAxis: v.end_day },
  ])

  // 计算 yAxis 范围：必须包含折线数据和安高/安低线
  const allYValues = [
    ...lineData.map(d => d[1]),
    ...(t.safety_stock_thrd > 0 ? [t.safety_stock_thrd] : []),
    ...(t.low_safety_thrd > 0 ? [t.low_safety_thrd] : []),
  ]
  const yMax = Math.max(...allYValues) * 1.1
  const yMin = Math.min(0, Math.min(...allYValues) * 0.9)

  // 安全上限/下限线
  const markLines: unknown[] = []
  if (t.safety_stock_thrd > 0) {
    markLines.push({
      yAxis: t.safety_stock_thrd,
      name: '安全上限',
      lineStyle: { type: 'dashed', color: '#ef4444', width: 1.5 },
      label: { show: true, formatter: `上限 ${fNum(t.safety_stock_thrd)}`, position: 'end', fontSize: 10, color: '#ef4444' },
    })
  }
  if (t.low_safety_thrd > 0) {
    markLines.push({
      yAxis: t.low_safety_thrd,
      name: '安全下限',
      lineStyle: { type: 'dashed', color: '#f59e0b', width: 1.5 },
      label: { show: true, formatter: `下限 ${fNum(t.low_safety_thrd)}`, position: 'end', fontSize: 10, color: '#f59e0b' },
    })
  }

  // 工况切换标记线（段边界）
  const switchMarkLines: unknown[] = []
  for (let i = 1; i < segments.length; i++) {
    const prev = segments[i - 1]
    const curr = segments[i]
    // 只标注关键切换点：油种变化、航煤边界、停工变化
    const isCrudeChange = prev.crude_type !== curr.crude_type
    const isHmChange = prev.is_hangmei !== curr.is_hangmei
    const isSdChange = (prev.shutdown_devices?.length || 0) > 0 !== (curr.shutdown_devices?.length || 0) > 0
    if (isCrudeChange || isHmChange || isSdChange) {
      const labels: string[] = []
      if (isCrudeChange) labels.push('油种')
      if (isHmChange) labels.push(curr.is_hangmei ? '航煤起' : '航煤止')
      if (isSdChange) labels.push('停工')
      switchMarkLines.push({
        xAxis: curr.start_day,
        lineStyle: { type: 'dotted', color: '#94a3b8', width: 1, opacity: 0.5 },
        label: { show: true, formatter: labels.join('/'), fontSize: 9, color: '#64748b', position: 'start' },
      })
    }
  }

  // 违规摘要
  const violationSummary = hasV
    ? t.violations.map(v => `第${v.seg_id + 1}段(天${v.start_day.toFixed(0)}-${v.end_day.toFixed(0)}) ${v.violation_type === 'over_high' ? '超上限' : '低于下限'} ${fNum(v.severity)}吨`).join('；')
    : ''

  const option: Record<string, unknown> = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params: unknown) => {
        const arr = params as Array<{ value: [number, number]; dataIndex: number }>
        if (!arr.length) return ''
        const [day, cap] = arr[0].value
        // 找到对应的段
        const seg = segments.find(s => day >= s.start_day && day <= s.end_day)
        let html = `<div style="font-weight:600">${t.tank_name}</div>`
        html += `<div style="font-size:11px;color:#94a3b8">天 ${day.toFixed(1)} · 库存 <b>${fNum(cap)}</b> 吨</div>`
        if (seg) {
          html += `<div style="font-size:11px;color:#94a3b8">${seg.crude_type} · ${seg.is_hangmei ? '航煤工况' : '常规'}${seg.shutdown_devices && seg.shutdown_devices.length > 0 ? ' · 停工' : ''}</div>`
        }
        return html
      },
    },
    grid: { left: 70, right: 60, top: 15, bottom: 30 },
    xAxis: {
      type: 'value',
      min: 0,
      max: segments.length > 0 ? segments[segments.length - 1].end_day : undefined,
      axisLabel: { fontSize: 11, color: CHART_COLORS.label, formatter: (val: number) => `${val.toFixed(0)}` },
      axisLine: { lineStyle: { color: CHART_COLORS.axisLine } },
      splitLine: { lineStyle: { color: CHART_COLORS.splitLine, type: 'dashed' } },
      name: '天',
      nameTextStyle: { fontSize: 11, color: CHART_COLORS.label },
    },
    yAxis: {
      type: 'value',
      min: yMin,
      max: yMax,
      axisLabel: { fontSize: 11, color: CHART_COLORS.label },
      splitLine: { lineStyle: { color: CHART_COLORS.splitLine, type: 'dashed' } },
      name: '库存(吨)',
      nameTextStyle: { fontSize: 11, color: CHART_COLORS.label },
    },
    series: [{
      name: t.tank_name,
      type: 'line',
      smooth: false,
      symbol: 'circle',
      symbolSize: 4,
      lineStyle: { width: 2, color: '#3b82f6' },
      itemStyle: { color: '#3b82f6' },
      data: lineData,
      markArea: markAreaData.length > 0 ? {
        silent: true,
        data: markAreaData,
      } : undefined,
      markLine: {
        silent: true,
        symbol: 'none',
        data: [...markLines, ...switchMarkLines],
      },
    }],
  }

  return (
    <div className={`rounded-lg border ${hasV ? 'border-rose-200' : 'border-slate-200'} overflow-hidden`}>
      <div className={`flex items-center gap-2 px-3 py-2 ${hasV ? 'bg-rose-50/60' : 'bg-slate-50/60'}`}>
        <span className="text-sm font-medium text-slate-700">{t.tank_name}</span>
        <span className="text-[10px] text-slate-400 font-mono">{t.tank_id}</span>
        <span className="text-[10px] text-slate-400">初始 {fNum(t.initial_capacity)} 吨</span>
        {hasV && <span className="ml-auto text-[10px] text-rose-600 font-medium">{t.violations.length}处违规</span>}
      </div>
      <div className="p-2">
        <EChart option={option} height={220} />
      </div>
      {hasV && violationSummary && (
        <div className="px-3 pb-2 text-[10px] text-rose-600 leading-relaxed">
          {violationSummary}
        </div>
      )}
    </div>
  )
}

// ── ⑥ 计算过程：装置级求解链 ────────────────────────────────────────────
// 读选中组合的 batch_details，每批次纵向铺开物料流向。多批次用 Set 支持同时展开。
function CalcProcessSection({ details, crudeName, isOptimal, comboId }: {
  details: CalcBatchDetail[]
  crudeName: (id: string) => string
  isOptimal: boolean
  comboId: number
}) {
  const [open, setOpen] = useState<Set<number>>(() => new Set())
  const toggle = (id: number) => setOpen(prev => {
    const next = new Set(prev)
    next.has(id) ? next.delete(id) : next.add(id)
    return next
  })
  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60">
        <CardHead
          icon={isOptimal ? Trophy : Network}
          title={`${isOptimal ? '最优' : '选中'}方案 #${comboId} · 计算过程`}
          accent={isOptimal ? 'from-amber-500 to-orange-500' : 'from-cyan-500 to-teal-600'}
          hint="逐批次展开物料求解链：投入 → 常减压负荷 → 减一线 → 加工装置 → 收率 → 批次效益"
        />
      </div>
      <div className="p-3 space-y-2">
        {details.map(b => (
          <BatchCalcCard key={b.batch_id} b={b} crudeName={crudeName}
            isOpen={open.has(b.batch_id)} onToggle={() => toggle(b.batch_id)} />
        ))}
        <BatchSummaryCard details={details} crudeName={crudeName} />
      </div>
    </div>
  )
}

function BatchCalcCard({ b, crudeName, isOpen, onToggle }: {
  b: CalcBatchDetail
  crudeName: (id: string) => string
  isOpen: boolean
  onToggle: () => void
}) {
  const cjy = b.device_utilization[DEV_CJY]
  const cjyProducts = b.all_product_outputs[DEV_CJY] || {}
  // 动态获取加工装置列表（排除常减压）
  const procDevices = b.economic_analysis
    .filter(e => e.device_id !== DEV_CJY)
    .map(e => e.device_id)
  const econOf = (did: string) => b.economic_analysis.find(e => e.device_id === did)
  const hasJian1Split = b.jian1_to_diesel > 0 || b.jian1_to_wax > 0
  // 步骤编号：①②固定，③减一线分流（条件），④起为加工装置
  const procStartIdx = hasJian1Split ? 3 : 2
  const profitStepIdx = procStartIdx + procDevices.length

  return (
    <div className="rounded-lg border border-slate-200 overflow-hidden">
      {/* 卡头 */}
      <button onClick={onToggle}
        className="w-full flex items-center gap-2 px-3 py-2.5 hover:bg-slate-50/60 transition-colors text-left">
        {isOpen ? <ChevronDown className="w-4 h-4 text-slate-400 shrink-0" /> : <ChevRight className="w-4 h-4 text-slate-400 shrink-0" />}
        <span className="text-[13px] font-semibold text-slate-800 shrink-0">
          批次#{b.batch_id} · {crudeName(b.crude_type)}
        </span>
        <span className="text-[11px] text-slate-400 font-mono shrink-0">第{b.start_day}–{b.end_day}天</span>
        <span className={`inline-block px-2 py-0.5 rounded text-[11px] font-medium shrink-0 ${b.mode === 'X_ZERO' ? 'bg-purple-50 text-purple-700' : 'bg-blue-50 text-blue-700'}`}>
          {MODE_CN[b.mode] || b.mode}
        </span>
        {b.is_hangmei_period && <span className="text-[10px] text-sky-600 shrink-0">航煤期</span>}
        <span className="ml-auto text-[13px] font-mono font-bold text-emerald-700 shrink-0">
          批次利润 {fNum(b.revenue)} 元
        </span>
      </button>

      {isOpen && (
        <div className="px-4 pb-4 pt-1 space-y-3 bg-slate-50/30">
          {/* ① 原油投入 → 常减压负荷 */}
          <FlowStep step="①" icon={Gauge} title="原油投入 → 常减压装置负荷" tone="cyan">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
              <StatCell label="日均加工量" value={`${fNum(b.costs.daily_input)} 吨/天`} />
              <StatCell label="批次总投入" value={`${fNum(b.total_input)} 吨`} accent="text-slate-600" />
              <StatCell label="加工周期" value={`${b.costs.days} 天`} accent="text-slate-600" />
              <StatCell label="常减压装置" value={cjy?.name || DEV_CJY} accent="text-slate-600" />
            </div>
            {cjy && (
              <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px]">
                <span className="text-slate-500">日均进料 <span className="font-mono font-bold text-slate-700">{fNum(cjy.input)}</span> 吨/天</span>
                {cjy.cdu_daily_cap != null && (
                  <span className="text-slate-500">日均能力 <span className="font-mono font-bold text-slate-700">{fNum(cjy.cdu_daily_cap)}</span> 吨/天</span>
                )}
                {cjy.cdu_overload && <span className="text-red-600 font-medium">⚠ 超负荷</span>}
              </div>
            )}
          </FlowStep>

          {/* ② 常减压产物（收率） */}
          <FlowStep step="②" icon={Factory} title="常减压产物 · 收率" tone="teal">
            <ProductYieldTable products={cjyProducts} highlightName="减一线" econProducts={econOf(DEV_CJY)?.products} days={b.costs.days} />
            <MaterialBalance physicalInput={cjy?.input || (b.costs.daily_input * (b.costs.days || 1))} products={cjyProducts} />
          </FlowStep>

          {/* ③ 减一线分流 */}
          {(b.jian1_to_diesel > 0 || b.jian1_to_wax > 0) && (
            <FlowStep step="③" icon={ArrowDown} title="减一线分流（阀门切换）" tone="blue">
              <Jian1SplitBar diesel={b.jian1_to_diesel} wax={b.jian1_to_wax} mode={b.mode} />
            </FlowStep>
          )}

          {/* ④⑤... 加工装置（动态渲染，不硬编码装置ID） */}
          {procDevices.map((did, i) => {
            const econ = econOf(did)
            const input = b.device_utilization[did]?.input ?? 0
            if (input <= 0 || !econ) return null
            return (
              <FlowStep key={did} step={CIRCLED[procStartIdx + i]} icon={Factory}
                title={`${econ.device_name} · 投入产出`} tone={PROC_TONES[i % PROC_TONES.length]}>
                <DeviceCalcBlock
                  revenue={econ.revenue}
                  products={b.all_product_outputs[did] || {}}
                  econProducts={econ.products} days={b.costs.days}
                  util={b.device_utilization[did]}
                  feedItems={b.feed_details?.find(fd => fd.device_id === did)?.items}
                  tonRevenue={econ.ton_revenue}
                  processCost={b.process_details?.find(pd => pd.device_id === did)?.process_cost} />
              </FlowStep>
            )
          })}

          {/* 批次效益 */}
          <FlowStep step={CIRCLED[profitStepIdx]} icon={Receipt} title="批次效益核算" tone="emerald">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
              <StatCell label="销售收入" value={`${fNum(b.costs.total_revenue)} 元`} accent="text-cyan-700" />
              <StatCell label="原料成本" value={`${fNum(b.costs.crude_cost)} 元`} accent="text-rose-600" />
              <StatCell label="加工成本" value={`${fNum(b.costs.energy_cost)} 元`} accent="text-rose-600" />
              <StatCell label="批次利润" value={`${fNum(b.costs.total_profit)} 元`} accent="text-emerald-700" />
            </div>
            <div className="mt-2 text-[11px] text-slate-500 font-mono bg-white rounded px-2 py-1.5 border border-slate-100">
              批次利润 = 销售收入 − 原料成本 − 加工成本 = {fNum(b.costs.total_revenue)} − {fNum(b.costs.crude_cost)} − {fNum(b.costs.energy_cost)} = <span className="font-bold text-emerald-700">{fNum(b.costs.total_profit)}</span> 元
            </div>
            {b.costs.ton_metrics && (
              <>
              <div className="mt-2 grid grid-cols-2 md:grid-cols-4 gap-2">
                <StatCell label="收入吨收" value={`${fNum(b.costs.ton_metrics.revenue)} 元/吨`} accent="text-cyan-700" />
                <StatCell label="原料吨成本" value={`${fNum(b.costs.ton_metrics.feed_cost)} 元/吨`} accent="text-rose-600" />
                <StatCell label="加工吨成本" value={`${fNum(b.costs.ton_metrics.process_cost)} 元/吨`} accent="text-rose-600" />
                <StatCell label="利润吨收" value={`${fNum(b.costs.ton_metrics.profit)} 元/吨`} accent="text-emerald-700" />
              </div>
              <div className="mt-1 text-[10px] text-slate-400">
                口径：以常减压日投入原油量为基准（元/吨原油），仅含加工装置的收支，不含常减压自身加工成本
              </div>
              </>
            )}
          </FlowStep>
        </div>
      )}
    </div>
  )
}

// ── 减一线切换点供需分析卡 ──
function Jian1SwitchCard({ analysis }: { analysis: Jian1SwitchAnalysis }) {
  const { switch_day, switch_batch_id, initial_mode_cn, diesel, wax, diesel_processing_days, wax_processing_days } = analysis
  const diffColor = (d: number) => d >= 0 ? 'text-emerald-600' : 'text-rose-500'
  const diffSign = (d: number) => d >= 0 ? '+' : ''
  const [show, setShow] = useState(false)
  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <button onClick={() => setShow(v => !v)}
        className="w-full flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60 hover:bg-slate-50 transition-colors">
        {show ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
        <CardHead icon={TrendingUp} title="减一线切换点供需分析" accent="from-blue-500 to-blue-600" hint="切换前 CDU产出 vs 设备月平均负荷消耗" />
      </button>
      {show && (
      <div className="p-4 space-y-3">
        <div className="flex items-center gap-4 text-[13px] flex-wrap">
          <span className="text-slate-600">切换时间点：<span className="font-mono font-semibold text-slate-800">第 {switch_day} 天</span>
            {switch_batch_id != null && <span className="text-slate-400 ml-1">（批次{switch_batch_id}起始）</span>}
          </span>
          <span className="text-slate-600">切换前方向：<span className="font-medium text-blue-700">{initial_mode_cn}</span></span>
        </div>
        <div className="flex items-center gap-4 text-[12px] text-slate-500">
          <span>柴加加工天数：<span className="font-mono text-slate-700">{diesel_processing_days}</span> 天</span>
          <span>蜡加加工天数：<span className="font-mono text-slate-700">{wax_processing_days}</span> 天</span>
          <span className="text-slate-400">（CDU时间 {switch_day} 天）</span>
        </div>
        <table className="w-full text-[12px] border border-slate-200 rounded-md overflow-hidden">
          <thead>
            <tr className="bg-slate-50 text-slate-600">
              <th className="text-left font-medium px-3 py-2">物料</th>
              <th className="text-right font-medium px-3 py-2">CDU产出(吨)</th>
              <th className="text-right font-medium px-3 py-2">月平均消耗(吨)</th>
              <th className="text-right font-medium px-3 py-2">差值(吨)</th>
            </tr>
          </thead>
          <tbody>
            <tr className="border-t border-slate-100">
              <td className="px-3 py-2 text-blue-700 font-medium">直馏柴油(→柴加)</td>
              <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(diesel.cdu_output)}</td>
              <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(diesel.device_demand)}</td>
              <td className={`px-3 py-2 text-right font-mono font-semibold ${diffColor(diesel.diff)}`}>{diffSign(diesel.diff)}{fNum(diesel.diff)}</td>
            </tr>
            <tr className="border-t border-slate-100">
              <td className="px-3 py-2 text-purple-700 font-medium">直馏蜡油(→蜡加)</td>
              <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(wax.cdu_output)}</td>
              <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(wax.device_demand)}</td>
              <td className={`px-3 py-2 text-right font-mono font-semibold ${diffColor(wax.diff)}`}>{diffSign(wax.diff)}{fNum(wax.diff)}</td>
            </tr>
          </tbody>
        </table>
        <p className="text-[11px] text-slate-400">
          月平均日处理量 = Σ(各批次总主料量 × 天数) ÷ 月总天数，体现装置全月均匀取料的平均速率。
          加工天数 = 本批次总主料量 ÷ 月平均日处理量，Σ(加工天数) = 月总天数（装置全月持续加工）。
          各批次加工天数可能≠CDU天数，差值为正=CDU产出过剩（可建罐存），为负=供应不足（装置跨批消化）。
        </p>
      </div>
      )}
    </div>
  )
}

// ── 装置加工时间线：各批次月平均负荷加工天数串联进度条 ──
function DeviceProcessingTimeline({ details, deviceId, tone, crudeName }: {
  details: CalcBatchDetail[]
  deviceId: string
  tone: 'blue' | 'purple'
  crudeName: (id: string) => string
}) {
  const segments = details.map(b => ({
    batchId: b.batch_id,
    crudeType: b.crude_type,
    cduDays: b.costs.days || 0,
    procDays: b.device_utilization[deviceId]?.processing_days ?? 0,
    dailyConsumption: b.device_utilization[deviceId]?.daily_consumption ?? 0,
  })).filter(s => s.procDays > 0)

  if (segments.length === 0) return null

  const totalProcDays = segments.reduce((s, seg) => s + seg.procDays, 0)
  const totalCduDays = segments.reduce((s, seg) => s + seg.cduDays, 0)
  const toneColors = tone === 'blue'
    ? { bar: 'from-blue-400 to-blue-500', text: 'text-blue-700', bg: 'bg-blue-50', border: 'border-blue-100' }
    : { bar: 'from-purple-400 to-purple-500', text: 'text-purple-700', bg: 'bg-purple-50', border: 'border-purple-100' }

  // 满负荷口径判断：日处理量 ≈ safety_stock_thrd（后端月负荷超容时按满负荷重算）
  const hasFullLoad = segments.some(s => {
    const cap = details.find(b => b.batch_id === s.batchId)?.device_utilization?.[deviceId]?.safety_stock_thrd ?? 0
    return cap > 0 && Math.abs(s.dailyConsumption - cap) < 0.5
  })
  const timelineLabel = hasFullLoad ? '满负荷加工时间线' : '月平均负荷加工时间线'

  return (
    <div className={`mt-2 rounded-lg border ${toneColors.border} ${toneColors.bg} px-3 py-2`}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-[11px] font-medium text-slate-600">{timelineLabel}</span>
        <span className={`text-[11px] font-mono ${toneColors.text}`}>
          总加工时间 {totalProcDays.toFixed(2)} 天（CDU时间 {totalCduDays.toFixed(1)} 天）
        </span>
      </div>
      {/* 进度条 */}
      <div className="flex h-6 rounded-md overflow-hidden border border-slate-200 bg-slate-100">
        {segments.map((seg, i) => {
          const pct = (seg.procDays / totalProcDays) * 100
          return (
            <div
              key={i}
              className={`h-full bg-gradient-to-r ${toneColors.bar} flex items-center justify-center border-r border-white/30 last:border-r-0 relative group`}
              style={{ width: `${pct}%` }}
              title={`批次${seg.batchId} (${crudeName(seg.crudeType)}): ${seg.procDays.toFixed(2)}天`}
            >
              {pct > 8 && (
                <span className="text-[9px] text-white font-mono font-medium">{seg.procDays.toFixed(1)}d</span>
              )}
            </div>
          )
        })}
      </div>
      {/* 批次标签 */}
      <div className="flex mt-1">
        {segments.map((seg, i) => {
          const pct = (seg.procDays / totalProcDays) * 100
          return (
            <div key={i} style={{ width: `${pct}%` }} className="text-center border-r border-slate-200/50 last:border-r-0">
              {pct > 8 && (
                <span className="text-[9px] text-slate-400 font-mono">B{seg.batchId}</span>
              )}
            </div>
          )
        })}
      </div>
      {/* 明细列表 */}
      <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[10px] text-slate-500">
        {segments.map((seg, i) => (
          <span key={i} className="font-mono">
            批次{seg.batchId}({crudeName(seg.crudeType)}): {seg.procDays.toFixed(2)}天 · {fNum(seg.dailyConsumption)}吨/天
          </span>
        ))}
      </div>
    </div>
  )
}

// ── 汇总卡：所有批次的聚合视图（始终展开，结构与 BatchCalcCard 的 7 步对齐） ──
function BatchSummaryCard({ details, crudeName }: {
  details: CalcBatchDetail[]
  crudeName: (id: string) => string
}) {
  const [show, setShow] = useState(false)
  const totalDays = details.reduce((s, b) => s + (b.costs.days || 0), 0)
  const totalInput = details.reduce((s, b) => s + (b.total_input || 0), 0)
  // ① 加权日均加工量 & 加权负荷率
  const wAvgDaily = totalDays > 0 ? details.reduce((s, b) => s + (b.costs.daily_input || 0) * (b.costs.days || 0), 0) / totalDays : 0
  const cjyInputSum = details.reduce((s, b) => s + (b.device_utilization[DEV_CJY]?.input || 0) * (b.costs.days || 0), 0)
  // ② 常减压各产品聚合：output 已是批次总量（后端 ×days），直接累加；加权收率 = 总产量 / 总投入
  const cjyProductAgg: Record<string, { totalOutput: number; price: number; totalRev: number; isFinal: boolean; isSummary: boolean }> = {}
  for (const b of details) {
    const prods = b.all_product_outputs[DEV_CJY] || {}
    const econMap = new Map((b.economic_analysis.find(e => e.device_id === DEV_CJY)?.products || []).map(p => [p.product_name, p]))
    for (const [name, p] of Object.entries(prods)) {
      const isSummary = name.includes('汇总')
      if (!cjyProductAgg[name]) cjyProductAgg[name] = { totalOutput: 0, price: p.price, totalRev: 0, isFinal: econMap.has(name), isSummary }
      cjyProductAgg[name].totalOutput += (p.output || 0)
      const econ = econMap.get(name)
      if (econ) cjyProductAgg[name].totalRev += econ.revenue || 0
    }
  }
  // ③ 减一线分流聚合（jian1_to_diesel/wax 已是批次总量，不再 ×days）
  const j1Diesel = details.reduce((s, b) => s + (b.jian1_to_diesel || 0), 0)
  const j1Wax = details.reduce((s, b) => s + (b.jian1_to_wax || 0), 0)
  // ④⑤ 柴加/蜡加聚合（output/revenue/feed_qty/cost/process_cost 均为批次值，直接累加）
  const aggDevice = (did: string) => {
    let revenue = 0, connMain = 0, mainLoad = 0, totalFeed = 0, processCost = 0
    let inputSum = 0, mainName = ''
    const productAgg: Record<string, { totalOutput: number; price: number; totalRev: number; isFinal: boolean; isSummary: boolean }> = {}
    // 进料原料明细聚合：按物料名累加（feed_qty/cost 均为批次值，直接累加）
    const feedAgg: Record<string, { name: string; label: string; totalQty: number; price: number; totalCost: number; tonCost: number }> = {}
    for (const b of details) {
      const d = b.costs.days || 1
      const econ = b.economic_analysis.find(e => e.device_id === did)
      const util = b.device_utilization[did]
      const feedItems = b.feed_details?.find(fd => fd.device_id === did)?.items || []
      const mains = feedItems.filter(i => i.label === '主料')
      const connMainItem = mains.length > 0 ? mains.reduce((max, i) => i.feed_qty > max.feed_qty ? i : max) : null
      revenue += (econ?.revenue || 0)
      connMain += (connMainItem?.feed_qty || 0)
      mainLoad += mains.reduce((s, i) => s + i.feed_qty, 0)
      totalFeed += feedItems.reduce((s, i) => s + i.feed_qty, 0)
      processCost += (b.process_details?.find(pd => pd.device_id === did)?.process_cost || 0)
      inputSum += (util?.input || 0) * d  // device_utilization.input 仍为日值，需 ×days
      if (!mainName && connMainItem) mainName = connMainItem.name
      // 进料明细聚合
      for (const item of feedItems) {
        if (!feedAgg[item.product_id]) feedAgg[item.product_id] = { name: item.name, label: item.label, totalQty: 0, price: item.price, totalCost: 0, tonCost: item.ton_cost ?? 0 }
        feedAgg[item.product_id].totalQty += item.feed_qty
        feedAgg[item.product_id].totalCost += item.cost
      }
      // 产品聚合
      const econMap = new Map((econ?.products || []).map(p => [p.product_name, p]))
      const prods = b.all_product_outputs[did] || {}
      for (const [name, p] of Object.entries(prods)) {
        const isSummary = name.includes('汇总')
        if (!productAgg[name]) productAgg[name] = { totalOutput: 0, price: p.price, totalRev: 0, isFinal: econMap.has(name), isSummary }
        productAgg[name].totalOutput += (p.output || 0)
        const ep = econMap.get(name)
        if (ep) productAgg[name].totalRev += ep.revenue || 0
      }
    }
    return { revenue, connMain, mainLoad, totalFeed, processCost, inputSum, mainName, productAgg, feedAgg }
  }
  // 动态获取所有加工装置ID（从所有批次的 economic_analysis 汇总，排除常减压）
  const allProcDevices = [...new Set(
    details.flatMap(b => b.economic_analysis.filter(e => e.device_id !== DEV_CJY).map(e => e.device_id))
  )]
  const procAggs = allProcDevices.map(did => ({ deviceId: did, agg: aggDevice(did) }))
  // 判断是否有减一线分流
  const hasJian1Split = details.some(b => b.jian1_to_diesel > 0 || b.jian1_to_wax > 0)
  const procStartIdx = hasJian1Split ? 3 : 2
  const profitStepIdx = procStartIdx + procAggs.length
  // 效益汇总
  const totalRevenue = details.reduce((s, b) => s + (b.costs.total_revenue || 0), 0)
  const totalCrudeCost = details.reduce((s, b) => s + (b.costs.crude_cost || 0), 0)
  const totalEnergyCost = details.reduce((s, b) => s + (b.costs.energy_cost || 0), 0)
  const totalProfit = details.reduce((s, b) => s + (b.costs.total_profit || 0), 0)
  // 原油类型汇总
  const crudeTypes = [...new Set(details.map(b => b.crude_type))]

  // 产品聚合表渲染（总量口径；收率 = 总产量 / 基准总量；汇总行单独显示在 tfoot）
  const renderProductAggTable = (
    agg: Record<string, { totalOutput: number; price: number; totalRev: number; isFinal: boolean; isSummary: boolean }>,
    basisTotal: number,
    isCjy: boolean
  ) => {
    const allRows = Object.entries(agg).sort((a, b) => isCjy ? cjySortIndex(a[0]) - cjySortIndex(b[0]) : 0)
    const rows = allRows.filter(([, r]) => !r.isSummary)
    const summaryRows = allRows.filter(([, r]) => r.isSummary)
    if (!rows.length) return <div className="text-xs text-slate-400">无产物数据</div>
    const totalOut = rows.reduce((s, [, r]) => s + r.totalOutput, 0)
    const totalYield = basisTotal > 0 ? (totalOut / basisTotal * 100) : 0
    const totalRev = rows.reduce((s, [, r]) => s + r.totalRev, 0)
    const totalTonRev = rows.reduce((s, [name, r]) => {
      const yld = basisTotal > 0 ? (r.totalOutput / basisTotal * 100) : 0
      return s + (r.isFinal ? (yld / 100) * r.price : 0)
    }, 0)
    return (
      <div className="overflow-x-auto rounded border border-slate-100">
        <table className="w-full text-[12px]">
          <thead>
            <tr className="bg-slate-50 text-slate-500">
              <th className="text-left font-medium px-2 py-1.5">产品</th>
              <th className="text-right font-medium px-2 py-1.5">总产量(吨)</th>
              <th className="text-right font-medium px-2 py-1.5">加权收率%</th>
              <th className="text-right font-medium px-2 py-1.5">价格(元/吨)</th>
              <th className="text-right font-medium px-2 py-1.5">吨收(元/吨)</th>
              <th className="text-right font-medium px-2 py-1.5">总收益(元)</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(([name, r]) => {
              const yld = basisTotal > 0 ? (r.totalOutput / basisTotal * 100) : 0
              const tonRev = r.isFinal ? (yld / 100) * r.price : 0
              return (
                <tr key={name} className={`border-t border-slate-50 ${name.includes('减一线') ? 'bg-amber-50/70' : ''}`}>
                  <td className="px-2 py-1.5 text-slate-700">
                    {name.includes('减一线') && <span className="mr-1 text-amber-600">★</span>}
                    {name}
                    {!r.isFinal && <span className="ml-1 text-[10px] text-slate-300">中间料</span>}
                  </td>
                  <td className="px-2 py-1.5 text-right font-mono text-slate-700">{fNum(r.totalOutput)}</td>
                  <td className="px-2 py-1.5 text-right font-mono text-slate-600">{yld.toFixed(2)}%</td>
                  <td className="px-2 py-1.5 text-right font-mono text-slate-600">{r.price > 0 ? fNum(r.price) : '—'}</td>
                  <td className="px-2 py-1.5 text-right font-mono">
                    {r.isFinal ? <span className="text-cyan-700">{fNum(tonRev)}</span> : <span className="text-slate-300">—</span>}
                  </td>
                  <td className="px-2 py-1.5 text-right font-mono">
                    {r.totalRev > 0 ? <span className="text-emerald-700">{fNum(r.totalRev)}</span> : <span className="text-slate-300">—</span>}
                  </td>
                </tr>
              )
            })}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-slate-200 bg-slate-50/60 font-medium text-slate-700">
              <td className="px-2 py-1.5">合计</td>
              <td className="px-2 py-1.5 text-right font-mono">{fNum(totalOut)}</td>
              <td className="px-2 py-1.5 text-right font-mono">{totalYield.toFixed(2)}%</td>
              <td className="px-2 py-1.5" />
              <td className="px-2 py-1.5 text-right font-mono text-cyan-700">{fNum(totalTonRev)}</td>
              <td className="px-2 py-1.5 text-right font-mono text-emerald-700">{fNum(totalRev)}</td>
            </tr>
            {summaryRows.map(([name, r]) => {
              const yld = basisTotal > 0 ? (r.totalOutput / basisTotal * 100) : 0
              return (
                <tr key={name} className="border-t border-slate-100 bg-amber-50/40 text-slate-600">
                  <td className="px-2 py-1.5 text-slate-700 font-medium">{name}</td>
                  <td className="px-2 py-1.5 text-right font-mono">{fNum(r.totalOutput)}</td>
                  <td className="px-2 py-1.5 text-right font-mono">{yld.toFixed(2)}%</td>
                  <td className="px-2 py-1.5" />
                  <td className="px-2 py-1.5" />
                  <td className="px-2 py-1.5" />
                </tr>
              )
            })}
          </tfoot>
        </table>
      </div>
    )
  }

  return (
    <div className="rounded-lg border-2 border-amber-200 bg-amber-50/30 overflow-hidden">
      {/* 卡头 */}
      <button onClick={() => setShow(v => !v)}
        className="w-full flex items-center gap-2 px-3 py-2.5 bg-amber-100/40 hover:bg-amber-100/60 transition-colors">
        {show ? <ChevronDown className="w-4 h-4 text-amber-600" /> : <ChevRight className="w-4 h-4 text-amber-600" />}
        <span className="text-base">📊</span>
        <span className="text-[13px] font-bold text-amber-800">
          全批次汇总 · {details.length}个批次 · {crudeTypes.map(crudeName).join(' / ')}
        </span>
        <span className="text-[11px] text-slate-400 font-mono shrink-0">总天数 {fNum(totalDays)} 天</span>
        <span className="ml-auto text-[14px] font-mono font-bold text-emerald-700">
          总利润 {fNum(totalProfit)} 元
        </span>
      </button>
      {show && (
      <div className="px-4 pb-4 pt-2 space-y-3">
        {/* ① 原油投入 → 常减压负荷 */}
        <FlowStep step="①" icon={Gauge} title="原油投入 → 常减压装置负荷（汇总）" tone="cyan">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
            <StatCell label="加权日均加工量" value={`${fNum(wAvgDaily)} 吨/天`} />
            <StatCell label="总投入量" value={`${fNum(totalInput)} 吨`} accent="text-slate-600" />
            <StatCell label="总天数" value={`${fNum(totalDays)} 天`} accent="text-slate-600" />
            <StatCell label="原油类型" value={crudeTypes.map(crudeName).join(' / ')} accent="text-slate-600" />
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px]">
            <span className="text-slate-500">总加工 <span className="font-mono font-bold text-slate-700">{fNum(cjyInputSum)}</span> 吨</span>
            <span className="text-slate-500">加权日均 <span className="font-mono font-bold text-slate-700">{fNum(totalDays > 0 ? cjyInputSum / totalDays : 0)}</span> 吨/天</span>
          </div>
        </FlowStep>

        {/* ② 常减压产物（收率汇总） */}
        <FlowStep step="②" icon={Factory} title="常减压产物 · 收率（汇总）" tone="teal">
          {renderProductAggTable(cjyProductAgg, totalInput, true)}
          <div className="mt-1 text-[10px] text-slate-400">加权收率 = 产品总产量 / 常减压总投入量</div>
        </FlowStep>

        {/* ③ 减一线分流汇总 */}
        {(j1Diesel > 0 || j1Wax > 0) && (
          <FlowStep step="③" icon={ArrowDown} title="减一线分流（汇总）" tone="blue">
            <div className="flex h-7 rounded-md overflow-hidden border border-slate-200">
              {j1Diesel > 0 && (
                <div className="bg-gradient-to-r from-blue-400 to-blue-500 grid place-items-center text-white text-xs font-medium"
                  style={{ width: `${(j1Diesel / (j1Diesel + j1Wax)) * 100}%` }}>
                  {j1Diesel / (j1Diesel + j1Wax) > 0.15 ? `→柴加 ${fNum(j1Diesel)}吨` : ''}
                </div>
              )}
              {j1Wax > 0 && (
                <div className="bg-gradient-to-r from-purple-400 to-purple-500 grid place-items-center text-white text-xs font-medium"
                  style={{ width: `${(j1Wax / (j1Diesel + j1Wax)) * 100}%` }}>
                  {j1Wax / (j1Diesel + j1Wax) > 0.15 ? `→蜡加 ${fNum(j1Wax)}吨` : ''}
                </div>
              )}
            </div>
            <div className="flex justify-between text-[11px] text-slate-500 mt-1">
              <span>合计分流 {fNum(j1Diesel + j1Wax)} 吨</span>
              <span>柴加 {fNum(j1Diesel)} · 蜡加 {fNum(j1Wax)}</span>
            </div>
          </FlowStep>
        )}

        {/* ④⑤... 加工装置汇总（动态渲染） */}
        {procAggs.map(({ deviceId, agg }, i) => {
          if (agg.revenue <= 0) return null
          const tone = PROC_TONES[i % PROC_TONES.length]
          const devName = details[0]?.economic_analysis.find(e => e.device_id === deviceId)?.device_name
            || details[0]?.device_utilization[deviceId]?.name || deviceId
          return (
            <FlowStep key={deviceId} step={CIRCLED[procStartIdx + i]} icon={Factory}
              title={`${devName} · 投入产出（汇总）`} tone={tone}>
              <div className="grid grid-cols-3 gap-2">
                <StatCell label={`连接主料${agg.mainName ? `（${agg.mainName}）` : ''}`} value={agg.connMain > 0 ? `${fNum(agg.connMain)} 吨` : '—'} />
                <StatCell label="主料负荷量" value={`${fNum(agg.mainLoad)} 吨`} accent="text-slate-600" />
                <StatCell label="总进料量" value={`${fNum(agg.totalFeed)} 吨`} accent="text-cyan-700" />
              </div>
              <DeviceProcessingTimeline details={details} deviceId={deviceId} tone={tone} crudeName={crudeName} />
              <div className="text-[11px] text-slate-500 font-mono bg-slate-50 rounded px-2 py-1">
                总加工成本 {fNum(agg.processCost)} 元
              </div>
              {/* 进料原料明细汇总 */}
              {Object.keys(agg.feedAgg).length > 0 && (
                <div className="mt-1.5">
                  <div className="text-[11px] font-medium text-slate-600 mb-1">进料原料明细（汇总）</div>
                  <div className="overflow-x-auto rounded border border-slate-100">
                    <table className="w-full text-[11px]">
                      <thead>
                        <tr className="bg-slate-50 text-slate-500">
                          <th className="text-left font-medium px-2 py-1">物料</th>
                          <th className="text-center font-medium px-2 py-1">类型</th>
                          <th className="text-right font-medium px-2 py-1">总用量(吨)</th>
                          <th className="text-right font-medium px-2 py-1">单价(元/吨)</th>
                          <th className="text-right font-medium px-2 py-1">总成本(元)</th>
                        </tr>
                      </thead>
                      <tbody>
                        {Object.values(agg.feedAgg).map((item, j) => (
                          <tr key={j} className="border-t border-slate-50">
                            <td className="px-2 py-1 text-slate-700">{item.name}</td>
                            <td className="px-2 py-1 text-center">
                              <span className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-medium ${item.label === '主料' ? 'bg-blue-50 text-blue-700' : 'bg-amber-50 text-amber-700'}`}>{item.label}</span>
                            </td>
                            <td className="px-2 py-1 text-right font-mono text-slate-700">{fNum(item.totalQty)}</td>
                            <td className="px-2 py-1 text-right font-mono text-slate-500">{fNum(item.price)}</td>
                            <td className="px-2 py-1 text-right font-mono text-rose-600">{fNum(item.totalCost)}</td>
                          </tr>
                        ))}
                      </tbody>
                      <tfoot>
                        <tr className="border-t-2 border-slate-200 bg-slate-50/60 font-medium">
                          <td className="px-2 py-1 text-slate-600" colSpan={2}>进料合计</td>
                          <td className="px-2 py-1 text-right font-mono text-slate-700">{fNum(Object.values(agg.feedAgg).reduce((s, it) => s + it.totalQty, 0))}</td>
                          <td className="px-2 py-1" />
                          <td className="px-2 py-1 text-right font-mono text-rose-700">{fNum(Object.values(agg.feedAgg).reduce((s, it) => s + it.totalCost, 0))}</td>
                        </tr>
                      </tfoot>
                    </table>
                  </div>
                </div>
              )}
              {renderProductAggTable(agg.productAgg, agg.totalFeed, false)}
              <div className="flex items-center justify-between text-[12px] bg-emerald-50/60 rounded px-2 py-1.5 border border-emerald-100">
                <span className="text-slate-600">装置总收入</span>
                <span className="font-mono font-bold text-emerald-700">{fNum(agg.revenue)} 元</span>
              </div>
            </FlowStep>
          )
        })}

        {/* 效益汇总 */}
        <FlowStep step={CIRCLED[profitStepIdx]} icon={Receipt} title="效益核算（汇总）" tone="emerald">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
            <StatCell label="总销售收入" value={`${fNum(totalRevenue)} 元`} accent="text-cyan-700" />
            <StatCell label="总原料成本" value={`${fNum(totalCrudeCost)} 元`} accent="text-rose-600" />
            <StatCell label="总加工成本" value={`${fNum(totalEnergyCost)} 元`} accent="text-rose-600" />
            <StatCell label="总利润" value={`${fNum(totalProfit)} 元`} accent="text-emerald-700" />
          </div>
          <div className="mt-2 text-[11px] text-slate-500 font-mono bg-white rounded px-2 py-1.5 border border-slate-100">
            总利润 = 总销售收入 − 总原料成本 − 总加工成本 = {fNum(totalRevenue)} − {fNum(totalCrudeCost)} − {fNum(totalEnergyCost)} = <span className="font-bold text-emerald-700">{fNum(totalProfit)}</span> 元
          </div>
          {totalInput > 0 && (
            <div className="mt-2 grid grid-cols-2 md:grid-cols-4 gap-2">
              <StatCell label="收入吨收" value={`${fNum(totalRevenue / totalInput)} 元/吨`} accent="text-cyan-700" />
              <StatCell label="原料吨成本" value={`${fNum(totalCrudeCost / totalInput)} 元/吨`} accent="text-rose-600" />
              <StatCell label="加工吨成本" value={`${fNum(totalEnergyCost / totalInput)} 元/吨`} accent="text-rose-600" />
              <StatCell label="利润吨收" value={`${fNum(totalProfit / totalInput)} 元/吨`} accent="text-emerald-700" />
            </div>
          )}
        </FlowStep>
      </div>
      )}
    </div>
  )
}

// 流程步骤容器：编号 + 图标 + 标题 + 内容，配流向色调
function FlowStep({ step, icon: Icon, title, tone, children }: {
  step: string
  icon: React.ComponentType<{ className?: string }>
  title: string
  tone: 'cyan' | 'teal' | 'blue' | 'purple' | 'emerald' | 'slate'
  children: React.ReactNode
}) {
  const toneMap = {
    cyan: 'from-cyan-500 to-cyan-600',
    teal: 'from-teal-500 to-teal-600',
    blue: 'from-blue-500 to-blue-600',
    purple: 'from-purple-500 to-purple-600',
    emerald: 'from-emerald-500 to-emerald-600',
    slate: 'from-slate-500 to-slate-600',
  } as const
  return (
    <div className="rounded-lg bg-white border border-slate-100 p-3">
      <div className="flex items-center gap-2 mb-2">
        <span className={`w-6 h-6 rounded-md grid place-items-center bg-gradient-to-br ${toneMap[tone]} text-white text-[11px] font-bold shrink-0`}>{step}</span>
        <Icon className="w-3.5 h-3.5 text-slate-400 shrink-0" />
        <span className="text-[12px] font-semibold text-slate-700">{title}</span>
      </div>
      {children}
    </div>
  )
}

// 常减压产物工艺顺序（常一→常二→常三→减一→…→减五→减压渣油→直馏石脑油）；
// 未在表中的产品（柴加/蜡加产物）返回大值，保持其原 dict 插入顺序稳定靠后。
const CJY_PRODUCT_ORDER: Record<string, number> = {
  '常一线': 1, '常二线': 2, '常三线': 3,
  '减一线': 4, '减二线': 5, '减三线': 6, '减四线': 7, '减五线': 8,
  '减压渣油': 9, '直馏石脑油': 10,
}
function cjySortIndex(name: string): number {
  return CJY_PRODUCT_ORDER[name] ?? 999
}

// 产物收率表（all_product_outputs 结构：{产品名: {output, yield_rate, yield_type, yield_reason, price}}）
// econProducts 非空时按产品名合并最终产品的销售收入；中间料/未售产品收益显示"—"。
// 产量列与汇总行统一按"日值"展示（output ÷ days），与物料平衡口径一致。
// 吨收 = 收率 × 价格：每吨原料投入产出该产品的收益贡献（元/吨）。
function ProductYieldTable({ products, highlightName, econProducts, days = 1 }: {
  products: Record<string, CalcProductOutput>
  highlightName?: string
  econProducts?: CalcEconProduct[]
  days?: number
}) {
  // 分离汇总行（名称含"汇总"）与普通行
  const isCjy = Object.keys(products).some(n => n in CJY_PRODUCT_ORDER)
  const allRows = Object.entries(products).sort((a, b) => {
    if (isCjy) return cjySortIndex(a[0]) - cjySortIndex(b[0])
    return 0
  })
  const rows = allRows.filter(([name]) => !name.includes('汇总'))
  const summaryRows = allRows.filter(([name]) => name.includes('汇总'))
  const econMap = new Map((econProducts || []).map(p => [p.product_name, p]))
  if (!rows.length) return <div className="text-xs text-slate-400">无产物数据</div>
  const d = days || 1
  const totalOutput = rows.reduce((s, [, p]) => s + (p.output || 0), 0)
  const totalYield = rows.reduce((s, [, p]) => s + (p.yield_rate || 0), 0)
  const totalRev = rows.reduce((s, [name]) => {
    const e = econMap.get(name)
    return s + (e?.revenue || 0)
  }, 0)
  // 吨收合计：所有最终产品的 (收率% × 价格) 之和
  const totalTonRev = rows.reduce((s, [name, p]) => {
    const e = econMap.get(name)
    return s + (e ? (p.yield_rate / 100) * p.price : 0)
  }, 0)
  return (
    <div className="overflow-x-auto rounded border border-slate-100">
      <table className="w-full text-[12px]">
        <thead>
          <tr className="bg-slate-50 text-slate-500">
            <th className="text-left font-medium px-2 py-1.5">产品</th>
            <th className="text-right font-medium px-2 py-1.5">产量(吨)</th>
            <th className="text-right font-medium px-2 py-1.5">收率%</th>
            <th className="text-right font-medium px-2 py-1.5">价格(元/吨)</th>
            <th className="text-right font-medium px-2 py-1.5">吨收(元/吨)</th>
            <th className="text-right font-medium px-2 py-1.5">收益(元)</th>
            <th className="text-left font-medium px-2 py-1.5">收率类型/原因</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([name, p]) => {
            const isHL = highlightName && name.includes(highlightName)
            const econ = econMap.get(name)
            const revenue = econ?.revenue
            const isFinal = !!econ
            const tonRev = isFinal ? (p.yield_rate / 100) * p.price : 0
            return (
              <tr key={name} className={`border-t border-slate-50 ${isHL ? 'bg-amber-50/70' : ''}`}>
                <td className="px-2 py-1.5 text-slate-700">
                  {isHL && <span className="mr-1 text-amber-600">★</span>}
                  {name}
                  {!isFinal && <span className="ml-1 text-[10px] text-slate-300">中间料</span>}
                </td>
                <td className="px-2 py-1.5 text-right font-mono text-slate-700">{fNum(p.output)}</td>
                <td className="px-2 py-1.5 text-right font-mono text-slate-600">{p.yield_rate}%</td>
                <td className="px-2 py-1.5 text-right font-mono text-slate-600">
                  {p.price > 0 ? fNum(p.price) : '—'}
                </td>
                <td className="px-2 py-1.5 text-right font-mono">
                  {isFinal ? <span className="text-cyan-700">{fNum(tonRev)}</span>
                    : <span className="text-slate-300">—</span>}
                </td>
                <td className="px-2 py-1.5 text-right font-mono">
                  {revenue != null ? <span className="text-emerald-700">{fNum(revenue)}</span>
                    : <span className="text-slate-300">—</span>}
                </td>
                <td className="px-2 py-1.5 text-[11px] text-slate-400">
                  {p.yield_type}{p.yield_reason ? ` · ${p.yield_reason}` : ''}
                </td>
              </tr>
            )
          })}
        </tbody>
        <tfoot>
          <tr className="border-t-2 border-slate-200 bg-slate-50/60 font-medium text-slate-700">
            <td className="px-2 py-1.5">合计</td>
            <td className="px-2 py-1.5 text-right font-mono">{fNum(totalOutput)}</td>
            <td className="px-2 py-1.5 text-right font-mono">{totalYield.toFixed(2)}%</td>
            <td className="px-2 py-1.5" />
            <td className="px-2 py-1.5 text-right font-mono text-cyan-700">{fNum(totalTonRev)}</td>
            <td className="px-2 py-1.5 text-right font-mono text-emerald-700">{fNum(totalRev)}</td>
            <td className="px-2 py-1.5" />
          </tr>
          {summaryRows.map(([name, p]) => (
            <tr key={name} className="border-t border-slate-100 bg-amber-50/40 text-slate-600">
              <td className="px-2 py-1.5 text-slate-700 font-medium">{name}</td>
              <td className="px-2 py-1.5 text-right font-mono">{fNum(p.output)}</td>
              <td className="px-2 py-1.5 text-right font-mono">{p.yield_rate}%</td>
              <td className="px-2 py-1.5" />
              <td className="px-2 py-1.5" />
              <td className="px-2 py-1.5" />
              <td className="px-2 py-1.5 text-[11px] text-slate-400">
                {p.yield_type}{p.yield_reason ? ` · ${p.yield_reason}` : ''}
              </td>
            </tr>
          ))}
        </tfoot>
      </table>
    </div>
  )
}

// 物料平衡：装置投入 vs 产物总产量，校验收率是否守恒。
// 量纲统一为"批次值"：physicalInput/effectiveInput/products.output 均为批次值
// 常减压 basis=物理投入(=有效)；柴加/蜡加 basis=有效输入(收率作用基准)，物理投入单独标注。
function MaterialBalance({ physicalInput, effectiveInput, products }: {
  physicalInput: number
  effectiveInput?: number       // 柴加/蜡加有效输入；常减压不传(=物理投入)
  products: Record<string, CalcProductOutput>
}) {
  const totalOutput = Object.entries(products)
    .filter(([name]) => !name.includes('汇总'))
    .reduce((s, [, p]) => s + (p.output || 0), 0)
  const physBatch = physicalInput
  const effBatch = effectiveInput != null ? effectiveInput : physBatch
  const loss = effBatch - totalOutput
  const balanceRate = effBatch > 0 ? (totalOutput / effBatch * 100) : 0
  const hasCoeff = effectiveInput != null && Math.abs(effectiveInput - physicalInput) > 0.01
  return (
    <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] bg-slate-50 rounded px-2.5 py-1.5 border border-slate-100">
      <span className="text-slate-500">物料平衡：</span>
      <span>投入 <span className="font-mono text-slate-700">{fNum(physBatch)}</span> 吨
        {hasCoeff && <> ÷系数 <span className="font-mono text-slate-700">{(physicalInput / (effectiveInput || 1)).toFixed(3)}</span> = 有效 <span className="font-mono text-slate-700">{fNum(effBatch)}</span> 吨</>}
      </span>
      <span>→ 产物 <span className="font-mono text-cyan-700 font-semibold">{fNum(totalOutput)}</span> 吨</span>
      <span>损耗 <span className="font-mono text-rose-600">{fNum(Math.max(loss, 0))}</span> 吨</span>
      <span>平衡率 <span className={`font-mono font-bold ${balanceRate >= 99 ? 'text-emerald-700' : balanceRate >= 90 ? 'text-amber-600' : 'text-rose-600'}`}>{balanceRate.toFixed(1)}%</span></span>
    </div>
  )
}

// 减一线分流条（柴加 / 蜡加）
function Jian1SplitBar({ diesel, wax, mode }: { diesel: number; wax: number; mode: string }) {
  const total = diesel + wax
  if (total <= 0) return <div className="text-xs text-slate-400">无减一线分流</div>
  return (
    <div>
      <div className="flex h-7 rounded-md overflow-hidden border border-slate-200">
        {diesel > 0 && (
          <div className="bg-gradient-to-r from-blue-400 to-blue-500 grid place-items-center text-white text-xs font-medium"
            style={{ width: `${(diesel / total) * 100}%` }}>
            {diesel / total > 0.15 ? `→柴加 ${fNum(diesel)}吨` : ''}
          </div>
        )}
        {wax > 0 && (
          <div className="bg-gradient-to-r from-purple-400 to-purple-500 grid place-items-center text-white text-xs font-medium"
            style={{ width: `${(wax / total) * 100}%` }}>
            {wax / total > 0.15 ? `→蜡加 ${fNum(wax)}吨` : ''}
          </div>
        )}
      </div>
      <div className="flex justify-between text-[11px] text-slate-500 mt-1">
        <span>切换模式：<span className="font-medium text-slate-700">{MODE_CN[mode] || mode}</span></span>
        <span>合计 <span className="font-mono text-slate-700">{fNum(total)}</span> 吨</span>
      </div>
    </div>
  )
}

// 柴加/蜡加装置计算块：连接主料 → 主料负荷量 → 总进料量 → 进料原料明细 → 产物收率表 → 装置收入
function DeviceCalcBlock({ revenue, products, econProducts, days = 1, util, feedItems, tonRevenue, processCost }: {
  revenue: number
  products: Record<string, CalcProductOutput>
  econProducts: CalcEconProduct[]
  days?: number
  util?: CalcDeviceUtil
  feedItems?: CalcFeedItem[]
  tonRevenue?: number
  processCost?: number  // 装置批次加工成本（元）
}) {
  const mainFeeds = feedItems?.filter(i => i.label === '主料') ?? []
  const mainFeedQty = mainFeeds.reduce((s, i) => s + i.feed_qty, 0)          // 全部主料总和（批次值）
  const totalInput = feedItems?.reduce((s, i) => s + i.feed_qty, 0) ?? 0       // 总进料（主料+辅料，批次值）
  const connMainFeed = mainFeeds.length > 0
    ? mainFeeds.reduce((max, i) => i.feed_qty > max.feed_qty ? i : max)        // 连接主料（feed_qty 最大的主料）
    : null
  // 1吨主料效益 = (批次收入 - 批次原料成本 - 批次加工成本) / 批次主料投入量
  const batchFeedCost = feedItems?.reduce((s, i) => s + i.cost, 0) ?? 0
  const tonMainProfit = (mainFeedQty > 0 && processCost != null)
    ? (revenue - batchFeedCost - processCost) / mainFeedQty
    : null
  return (
    <div className="space-y-2">
      <div className="grid grid-cols-3 gap-2">
        <StatCell label={`连接主料${connMainFeed ? `（${connMainFeed.name}）` : ''}`} value={connMainFeed ? `${fNum(connMainFeed.feed_qty)} 吨` : '—'} />
        <StatCell label="主料负荷量" value={`${fNum(mainFeedQty)} 吨`} accent="text-slate-600" />
        <StatCell label="总进料量" value={`${fNum(totalInput)} 吨`} accent="text-cyan-700" />
      </div>
      <div className="text-[11px] text-slate-500 font-mono bg-slate-50 rounded px-2 py-1">
        {connMainFeed ? `${connMainFeed.name} ${fNum(connMainFeed.feed_qty)}` : '—'} → 主料合计 {fNum(mainFeedQty)} → 总进料 <span className="font-bold text-cyan-700">{fNum(totalInput)}</span> 吨（辅料 {fNum(totalInput - mainFeedQty)}）
      </div>
      {/* 进料原料明细 */}
      {feedItems && feedItems.length > 0 && (
        <div className="mt-1.5">
          <div className="text-[11px] font-medium text-slate-600 mb-1">进料原料明细</div>
          <div className="overflow-x-auto rounded border border-slate-100">
            <table className="w-full text-[11px]">
              <thead>
                <tr className="bg-slate-50 text-slate-500">
                  <th className="text-left font-medium px-2 py-1">物料</th>
                  <th className="text-center font-medium px-2 py-1">类型</th>
                  <th className="text-right font-medium px-2 py-1">用量(吨)</th>
                  <th className="text-right font-medium px-2 py-1">单价(元/吨)</th>
                  <th className="text-right font-medium px-2 py-1">吨消耗(元/吨)</th>
                  <th className="text-right font-medium px-2 py-1">成本(元)</th>
                </tr>
              </thead>
              <tbody>
                {feedItems.map((item, i) => (
                  <tr key={i} className="border-t border-slate-50">
                    <td className="px-2 py-1 text-slate-700">{item.name}</td>
                    <td className="px-2 py-1 text-center">
                      <span className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-medium ${
                        item.label === '主料' ? 'bg-blue-50 text-blue-700' : 'bg-amber-50 text-amber-700'
                      }`}>{item.label}</span>
                    </td>
                    <td className="px-2 py-1 text-right font-mono text-slate-700">{fNum(item.feed_qty)}</td>
                    <td className="px-2 py-1 text-right font-mono text-slate-500">{fNum(item.price)}</td>
                    <td className="px-2 py-1 text-right font-mono text-amber-600">{fNum(item.ton_cost ?? 0)}</td>
                    <td className="px-2 py-1 text-right font-mono text-rose-600">{fNum(item.cost)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-slate-200 bg-slate-50/60 font-medium">
                  <td className="px-2 py-1 text-slate-600" colSpan={2}>进料合计</td>
                  <td className="px-2 py-1 text-right font-mono text-slate-700">{fNum(feedItems.reduce((s, it) => s + it.feed_qty, 0))}</td>
                  <td className="px-2 py-1" />
                  <td className="px-2 py-1 text-right font-mono text-amber-700">{fNum(feedItems.reduce((s, it) => s + (it.ton_cost ?? 0), 0))}</td>
                  <td className="px-2 py-1 text-right font-mono text-rose-700">{fNum(feedItems.reduce((s, it) => s + it.cost, 0))}</td>
                </tr>
              </tfoot>
            </table>
          </div>
        </div>
      )}
      {/* 负荷加工天数 & 日处理量（月负荷超容按满负荷计，未超容按月平均计） */}
      {util && util.processing_days != null && (() => {
        const cap = util.safety_stock_thrd ?? 0
        const daily = util.daily_consumption ?? 0
        // 满负荷口径：daily_consumption ≈ safety_stock_thrd（后端月负荷超容时按满负荷重算）
        const isFullLoad = cap > 0 && Math.abs(daily - cap) < 0.5
        const loadLabel = isFullLoad ? '满负荷加工天数' : '月平均负荷加工天数'
        const dailyLabel = isFullLoad ? '满负荷日处理量' : '月平均日处理量'
        return (
        <div className="grid grid-cols-2 gap-2">
          <div className="rounded-lg border border-blue-100 bg-blue-50/40 px-3 py-2">
            <div className="text-[10px] text-slate-500">{loadLabel}</div>
            <div className="font-mono font-bold text-blue-700 text-[15px]">{util.processing_days.toFixed(2)} <span className="text-[11px] font-normal text-slate-400">天</span></div>
            <div className="text-[10px] text-slate-400 mt-0.5">批次天数 {days}天 · 日处理 {fNum(daily)} 吨/天{isFullLoad && <span className="text-amber-600 ml-1">·满负荷</span>}</div>
          </div>
          <div className="rounded-lg border border-purple-100 bg-purple-50/40 px-3 py-2">
            <div className="text-[10px] text-slate-500">{dailyLabel}</div>
            <div className="font-mono font-bold text-purple-700 text-[15px]">{fNum(daily)} <span className="text-[11px] font-normal text-slate-400">吨/天</span></div>
            <div className="text-[10px] text-slate-400 mt-0.5">总主料负荷能力 {fNum(cap)} 吨/天</div>
          </div>
        </div>
        )
      })()}
      <ProductYieldTable products={products} econProducts={econProducts} days={days} />
      <MaterialBalance physicalInput={totalInput} effectiveInput={totalInput} products={products} />
      <div className="flex items-center justify-between text-[12px] bg-emerald-50/60 rounded px-2 py-1.5 border border-emerald-100">
        <span className="text-slate-600">装置收入(元){tonRevenue != null && <span className="ml-2 text-slate-400">· 吨收 <span className="font-mono text-cyan-700">{fNum(tonRevenue)}</span> 元/吨</span>}</span>
        <span className="font-mono font-bold text-emerald-700">{fNum(revenue)} 元</span>
      </div>
      {tonMainProfit != null && (
        <div className="text-[12px] bg-amber-50/60 rounded px-2 py-1.5 border border-amber-100">
          <div className="flex items-center justify-between">
            <span className="text-slate-600">1吨主料效益{connMainFeed && <span className="ml-1 text-slate-400">({connMainFeed.name})</span>}</span>
            <span className="font-mono font-bold text-amber-700">{fNum(tonMainProfit)} 元/吨</span>
          </div>
          <div className="mt-1 text-[10px] text-slate-400 leading-relaxed">
            = 收入吨收 {fNum(revenue / mainFeedQty)} − 原料吨成本 {fNum(batchFeedCost / mainFeedQty)} − 加工吨成本 {fNum((processCost ?? 0) / mainFeedQty)}
          </div>
        </div>
      )}
    </div>
  )
}

// 用 Fragment 包裹可展开行，避免破坏 table 语义
function FragmentRow({ children }: { children: React.ReactNode }) {
  return <>{children}</>
}

// ── 公共卡片头：慧炼范式渐变徽章 + 标题 + 右侧说明槽 ──────────────────
// 每个内容卡用，替代散落的 w-4 text-blue-500 单色小图标
export function CardHead({
  icon: Icon, title, accent = 'from-blue-500 to-blue-600', hint, right,
}: {
  icon: React.ComponentType<{ className?: string }>
  title: string
  accent?: string
  hint?: string
  right?: React.ReactNode
}) {
  return (
    <div className="flex items-center gap-2">
      <span className={`w-7 h-7 rounded-lg grid place-items-center bg-gradient-to-br ${accent} shadow-sm shrink-0`}>
        <Icon className="w-4 h-4 text-white" />
      </span>
      <span className="text-[13px] font-semibold text-slate-800">{title}</span>
      {hint && <span className="text-[11px] text-slate-400 ml-1">{hint}</span>}
      {right && <span className="ml-auto">{right}</span>}
    </div>
  )
}

// ── KPI 卡子组件（导出供排产/批次/预测三页共用）────────────────────────
export function KpiCard({ icon: Icon, label, value, sub, accent }: {
  icon: React.ComponentType<{ className?: string }>; label: string; value: string; sub: string; accent: string
}) {
  return (
    <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm transition-shadow hover:shadow-md">
      <div className="flex items-center gap-2 mb-2">
        <span className={`w-7 h-7 rounded-lg grid place-items-center bg-gradient-to-br ${accent} shadow-sm`}>
          <Icon className="w-4 h-4 text-white" />
        </span>
        <span className="text-[12px] text-slate-500">{label}</span>
      </div>
      <div className="text-2xl font-bold text-slate-900 font-mono">{value}</div>
      <div className="text-[11px] text-slate-400 mt-0.5">{sub}</div>
    </div>
  )
}

// 紧凑统计格（航煤工况卡用，比 KpiCard 更轻）
export function StatCell({ label, value, accent }: { label: string; value: string; accent?: string }) {
  return (
    <div className="rounded-lg border border-slate-100 bg-slate-50/40 px-3 py-2">
      <div className="text-[11px] text-slate-400">{label}</div>
      <div className={`text-[15px] font-mono font-semibold mt-0.5 ${accent || 'text-slate-800'}`}>{value}</div>
    </div>
  )
}

// ③ 最优航煤时段搜索表格（行可展开，显示逐批次航煤增量/净收益计算明细）
function WindowSearchTable({ ws, deltaH, hangmeiStart, mDays, hmPrice, rlydmxPrice,
  fNum, MODE_SHORT }: {
  ws: HangmeiWindowDetail[]
  deltaH: number; hangmeiStart: number; mDays: number
  hmPrice: number; rlydmxPrice: number
  fNum: (n: number) => string
  MODE_SHORT: Record<string, string>
}) {
  const [expanded, setExpanded] = useState<number | null>(0) // 默认展开最优行
  // 找出所有并列最优（可行候选中净收益最高的）
  const feasibleWs = ws.filter(w => w.feasible !== false)
  const maxBenefit = feasibleWs.length > 0 ? Math.max(...feasibleWs.map(w => w.total_benefit)) : 0
  const tiedCount = feasibleWs.filter(w => w.total_benefit === maxBenefit).length
  return (
    <div className="mt-4 pt-3 border-t border-slate-100">
      <div className="text-[12px] font-semibold text-slate-700 mb-1">
        ③ 最优航煤时段搜索
        <span className="ml-2 text-[10px] font-normal text-slate-400">
          航煤工况只需开 M 天补缺口，从哪天开始开最划算？
        </span>
        {tiedCount > 1 && (
          <span className="ml-2 inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] bg-amber-50 text-amber-600 border border-amber-200">
            {tiedCount} 个并列最优
          </span>
        )}
      </div>
      <div className="text-[11px] text-slate-400 mb-2">
        逐个候选起始位置（=各批次边界）试算：从该天起开航煤工况，逐天累加航煤增量直到补满缺口
        <span className="font-mono text-red-600 mx-1">{fNum(deltaH)}</span>吨，
        得到该位置的 M 和净收益。最优起始第
        <span className="font-mono text-sky-700 font-semibold mx-1">{hangmeiStart.toFixed(1)}</span>天
        → 开<span className="font-mono text-sky-700 font-semibold mx-1">{mDays.toFixed(1)}</span>天航煤工况
        （第 {hangmeiStart.toFixed(1)} ~ {(hangmeiStart + mDays).toFixed(1)} 天）
      </div>
      <div className="overflow-x-auto rounded-lg border border-slate-100">
        <table className="w-full text-[11px]">
          <thead>
            <tr className="bg-slate-50 text-slate-500 border-b border-slate-100">
              <th className="w-6 text-center font-medium px-1 py-1.5" />
              <th className="text-center font-medium px-2 py-1.5">起始天</th>
              <th className="text-center font-medium px-2 py-1.5">航煤工况区间</th>
              <th className="text-right font-medium px-2 py-1.5">M（天）</th>
              <th className="text-right font-medium px-2 py-1.5">航煤增量（吨）</th>
              <th className="text-left font-medium px-2 py-1.5">覆盖批次</th>
              <th className="text-right font-medium px-2 py-1.5">净收益（元）</th>
              <th className="text-center font-medium px-2 py-1.5">状态</th>
            </tr>
          </thead>
          <tbody>
            {ws.map((w, i) => {
              const infeasible = w.feasible === false
              const endDay = w.start + (w.m_days != null ? w.m_days : 0)
              const isOpen = expanded === i
              const cb = w.covered_batches || []
              const isTiedOptimal = !infeasible && w.total_benefit === maxBenefit
              let cumHm = 0, cumBen = 0
              return (
                <Fragment key={i}>
                  <tr
                    className={`border-b border-slate-50 cursor-pointer hover:bg-slate-50/60 ${isTiedOptimal ? 'bg-sky-50/40' : ''} ${infeasible ? 'opacity-60' : ''}`}
                    onClick={() => setExpanded(isOpen ? null : i)}
                  >
                    <td className="text-center px-1 py-1.5 text-slate-400 select-none">
                      {isOpen ? '▾' : '▸'}
                    </td>
                    <td className={`text-center px-2 py-1.5 font-mono ${isTiedOptimal ? 'text-sky-700 font-bold' : 'text-slate-500'}`}>
                      {w.start.toFixed(1)}
                    </td>
                    <td className="text-center px-2 py-1.5 font-mono text-slate-600">
                      第{w.start.toFixed(1)}~{endDay.toFixed(1)}天
                    </td>
                    <td className="text-right px-2 py-1.5 font-mono text-slate-600">
                      {w.m_days != null ? w.m_days.toFixed(2) : '-'}
                    </td>
                    <td className={`text-right px-2 py-1.5 font-mono ${infeasible ? 'text-red-500' : 'text-sky-700'}`}>
                      {infeasible ? `${fNum(w.hm_total || 0)}（不够）` : `+${fNum(w.hm_total || 0)}`}
                    </td>
                    <td className="text-left px-2 py-1.5 text-slate-500 max-w-[140px] truncate" title={cb.map(b => `${b.crude}(${b.days}天)`).join(' + ')}>
                      {cb.map(b => b.crude).join('+') || '-'}
                    </td>
                    <td className={`text-right px-2 py-1.5 font-mono ${isTiedOptimal ? 'text-sky-700 font-semibold' : 'text-slate-500'}`}>
                      {w.total_benefit >= 0 ? '+' : ''}{fNum(w.total_benefit)}
                    </td>
                    <td className="text-center px-2 py-1.5">
                      {isTiedOptimal
                        ? <span className="text-sky-600 font-semibold text-[10px]">★ 最优</span>
                        : infeasible
                          ? <span className="text-red-400 text-[10px]">✗ 不可行</span>
                          : <span className="text-slate-300 text-[10px]">可行</span>}
                    </td>
                  </tr>
                  {isOpen && (
                    <tr className="bg-slate-50/40">
                      <td colSpan={8} className="px-4 py-2">
                        <div className="text-[10px] text-slate-400 mb-1.5">
                          展开明细：航煤增量 = Σ(日加工量 × 航煤收率差 × 覆盖天数)；净收益 = Σ(航煤增产收益 − DMX减产损失)
                        </div>
                        <table className="w-full text-[10px] font-mono">
                          <thead>
                            <tr className="text-slate-400 border-b border-slate-200">
                              <th className="text-left font-medium py-1 pr-2">批次</th>
                              <th className="text-left font-medium py-1 pr-2">油种</th>
                              <th className="text-center font-medium py-1 pr-2">方向</th>
                              <th className="text-right font-medium py-1 pr-2">日加工量</th>
                              <th className="text-right font-medium py-1 pr-2">收率差</th>
                              <th className="text-right font-medium py-1 pr-2">航煤日增量</th>
                              <th className="text-right font-medium py-1 pr-2">覆盖天数</th>
                              <th className="text-right font-medium py-1 pr-2">航煤增量</th>
                              <th className="text-right font-medium py-1 pr-2">累计增量</th>
                              <th className="text-right font-medium py-1 pr-2">批次净收益</th>
                              <th className="text-right font-medium py-1">累计净收益</th>
                            </tr>
                          </thead>
                          <tbody>
                            {cb.map((b, j) => {
                              cumHm += b.hm_delta || 0
                              cumBen += b.benefit || 0
                              const dailyDelta = b.hm_daily_delta || 0
                              const di = b.daily_input || 0
                              const yL = b.yield_low || 0
                              const yH = b.yield_high || 0
                              const yDiff = (yH - yL) * 100
                              return (
                                <tr key={j} className="border-b border-slate-100 last:border-0 text-slate-600">
                                  <td className="py-1 pr-2 text-slate-400">{b.batch_id}</td>
                                  <td className="py-1 pr-2 text-slate-700">{b.crude}</td>
                                  <td className="py-1 pr-2 text-center">
                                    <span className={`inline-block px-1 py-0.5 rounded text-[9px] ${b.mode === 'X_ZERO' ? 'bg-purple-50 text-purple-600' : 'bg-blue-50 text-blue-600'}`}>
                                      {MODE_SHORT[b.mode || ''] || b.mode || '-'}
                                    </span>
                                  </td>
                                  <td className="py-1 pr-2 text-right text-slate-500">{fNum(di)}</td>
                                  <td className="py-1 pr-2 text-right text-slate-400">
                                    {dailyDelta > 0 ? `${(yH*100).toFixed(2)}%−${(yL*100).toFixed(2)}%=${yDiff.toFixed(2)}%` : '—'}
                                  </td>
                                  <td className="py-1 pr-2 text-right text-slate-500" title={dailyDelta > 0 ? `${fNum(di)} × ${yDiff.toFixed(2)}% = ${fNum(dailyDelta)}` : ''}>
                                    {dailyDelta > 0 ? `${fNum(dailyDelta)} 吨/天` : '—（无增量）'}
                                  </td>
                                  <td className="py-1 pr-2 text-right text-slate-500">{b.days.toFixed(2)} 天</td>
                                  <td className="py-1 pr-2 text-right text-sky-600">
                                    {dailyDelta > 0 ? `+${fNum(b.hm_delta || 0)}` : '0'}
                                  </td>
                                  <td className="py-1 pr-2 text-right text-slate-400">→ {fNum(cumHm)}</td>
                                  <td className={`py-1 pr-2 text-right ${(b.benefit || 0) >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                                    {(b.benefit || 0) >= 0 ? '+' : ''}{fNum(b.benefit || 0)}
                                  </td>
                                  <td className="py-1 text-right text-slate-400">→ {fNum(cumBen)}</td>
                                </tr>
                              )
                            })}
                            <tr className="font-semibold text-slate-700 border-t border-slate-300">
                              <td colSpan={7} className="py-1 pr-2 text-right">合计 →</td>
                              <td className="py-1 pr-2 text-right text-sky-700">+{fNum(w.hm_total || 0)}</td>
                              <td className="py-1 pr-2" />
                              <td colSpan={2} className="py-1 text-right text-sky-700">
                                {w.total_benefit >= 0 ? '+' : ''}{fNum(w.total_benefit)}
                              </td>
                            </tr>
                          </tbody>
                        </table>
                        {infeasible && (
                          <div className="mt-1.5 text-[10px] text-red-500">
                            ✗ 从第{w.start.toFixed(1)}天起到月底，航煤增量累计 {fNum(w.hm_total || 0)} 吨 &lt; 缺口 {fNum(deltaH)} 吨，无法补满
                          </div>
                        )}
                        <div className="mt-1 text-[10px] text-slate-400">
                          航煤价格 {fNum(hmPrice)} 元/吨，DMX价格 {fNum(rlydmxPrice)} 元/吨。
                          航煤日增量 = 日加工量 × (航煤收率 − 非航煤收率)；批次净收益 = 航煤日增量 × 航煤价格 × 天数 + DMX日减量 × DMX价格 × 天数
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className="mt-2 grid grid-cols-1 gap-1 text-[10px] text-slate-400">
        <div>· 点击任意行展开，查看该候选的航煤增量和净收益逐批次累加过程</div>
        <div>· 起始天 = 航煤工况从该天开始开；航煤工况区间 = [起始天, 起始天+M)</div>
        <div>· M = 从起始天起逐天累加航煤增量直到补满缺口所需的天数；不同起始位置覆盖的批次收率不同，M 因位置而异</div>
        <div>· 不可行 = 从该天起到月底，航煤增量之和仍不够补缺口；净收益 = 航煤增产收益 − DMX减产损失</div>
      </div>
    </div>
  )
}

// 航煤工况卡内可折叠分区（②③④ 等明细默认收起，减少首屏信息量）。
// 标题左侧带序号，右侧带净额/摘要（可选），点击标题行切换展开/收起。
function CollapsibleSection({
  step, title, summary, defaultOpen = false, children,
}: {
  step: string; title: string; summary?: React.ReactNode
  defaultOpen?: boolean; children: React.ReactNode
}) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <div className="mt-4 pt-3 border-t border-slate-100 first:mt-3 first:pt-0 first:border-0">
      <button onClick={() => setOpen(o => !o)}
        className="w-full flex items-center gap-2 text-left group">
        {open
          ? <ChevronDown className="w-3.5 h-3.5 text-slate-400 shrink-0" />
          : <ChevRight className="w-3.5 h-3.5 text-slate-400 shrink-0" />}
        <span className="text-[12px] font-semibold text-slate-700 shrink-0">
          <span className="text-slate-400 mr-1">{step}</span>{title}
        </span>
        {summary != null && <span className="ml-auto text-[11px] font-mono text-slate-500 shrink-0">{summary}</span>}
      </button>
      {open && <div className="mt-2">{children}</div>}
    </div>
  )
}

// 航煤工况卡（排产页/业务决策台共用）。仅 hangmei_summary.enabled 时由调用方决定渲染。
export function HangmeiCard({ hm }: { hm: HangmeiSummary }) {
  const [show, setShow] = useState(false)
  const pct = (v: number) => (v * 100).toFixed(2) + '%'
  const ratio = hm.yield_low > 0 ? hm.yield_high / hm.yield_low : 0
  const ws = hm.window_search || []
  const hasWindowSearch = ws.length > 0
  const maxBenefit = ws.length > 0 ? Math.max(...ws.map(w => w.total_benefit)) : 0
  const minBenefit = ws.length > 0 ? Math.min(...ws.map(w => w.total_benefit)) : 0
  const range = maxBenefit - minBenefit
  // 常规产出：优先用 H_default 字段，兜底用 actual_H（M=0 时两者相同）
  const H_default = hm.H_default != null ? hm.H_default : hm.actual_H
  const needHangmei = hm.active && hm.m_days > 0

  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <button onClick={() => setShow(v => !v)}
        className="w-full flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60 hover:bg-slate-50 transition-colors">
        {show ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
        <CardHead icon={Plane} title="航煤工况" accent="from-sky-500 to-sky-600"
          hint={needHangmei
            ? `首批次 ${MODE_SHORT[hm.first_mode] || hm.first_mode} · 需切入航煤工况以满足目标`
            : '常规工况已满足目标航煤产出，无需切入航煤工况'}
          right={needHangmei
            ? <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-sky-100 text-sky-700 text-[11px] font-medium"><PlaneTakeoff className="w-3 h-3" />航煤期 {hm.m_tons != null ? `${fNum(hm.m_tons)} 吨` : `${hm.m_days.toFixed(1)} 天`}</span>
            : <span className="px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-600 text-[11px]">常规已满足</span>} />
      </button>
      {show && (
      <div className="p-4">

      {/* ── 情况一：不需要航煤工况 ── */}
      {!needHangmei && (
        <div className="mt-3 p-3 rounded-lg border border-emerald-100 bg-emerald-50/30">
          <div className="flex items-center gap-2 text-[12px]">
            <span className="text-emerald-600 font-medium">✓ 无需航煤工况</span>
          </div>
          <div className="mt-2 text-[12px] text-slate-600 leading-relaxed">
            当前减一线切换方案下，常规工况全月航煤产出为
            <span className="font-mono font-semibold text-emerald-700 mx-1">{fNum(H_default)} 吨</span>
            ，已超过目标
            <span className="font-mono text-slate-700 mx-1">{fNum(hm.target)} 吨</span>
            （超出 {fNum(H_default - hm.target)} 吨），无需切入航煤工况。
          </div>
          <div className="mt-2 text-[11px] text-slate-400">
            说明：减一线方向（X_ZERO/Y_ZERO）影响{(hm.active_devices || []).map(d => d.device_name).join('、') || '主动装置'}的航煤收率，当高收率方向批次较多时，常规产出即可满足目标。
          </div>
        </div>
      )}

      {/* ── 情况二：需要航煤工况 ── */}
      {needHangmei && (
        <>
          {/* 第1步：为什么需要航煤工况 */}
          {(() => {
            // 区分不可行的两种情况：
            // (a) actual_H < target：即使全月航煤也不够（真正不可行）
            // (b) actual_H >= target 但 effective_H < target：理论上够，实际口径/折减后不足
            const isTrulyInfeasible = hm.feasible === false && (hm.actual_H ?? 0) < hm.target
            const isReducedInfeasible = hm.feasible === false && (hm.actual_H ?? 0) >= hm.target
            return (
          <div className={`mt-3 p-3 rounded-lg border ${isTrulyInfeasible ? 'border-red-200 bg-red-50/30' : isReducedInfeasible ? 'border-orange-200 bg-orange-50/30' : 'border-amber-100 bg-amber-50/30'}`}>
            <div className="flex items-center gap-2 text-[12px] mb-1">
              <span className={`${isTrulyInfeasible ? 'text-red-600' : isReducedInfeasible ? 'text-orange-600' : 'text-amber-600'} font-medium`}>
                {isTrulyInfeasible ? '⚠ 全月航煤工况仍不足目标'
                  : isReducedInfeasible ? '⚠ 航煤工况已安排，折减后实际不足'
                  : '⚠ 需切入航煤工况'}
              </span>
            </div>
            <div className="text-[12px] text-slate-600 leading-relaxed">
              常规工况全月航煤产出仅
              <span className="font-mono font-semibold text-amber-700 mx-1">{fNum(H_default)} 吨</span>
              ，不足目标
              <span className="font-mono text-slate-700 mx-1">{fNum(hm.target)} 吨</span>
              ，缺口
              <span className="font-mono font-semibold text-red-600 mx-1">{fNum(hm.target - H_default)} 吨</span>
              ，需切入航煤工况增产。
            </div>
            {isTrulyInfeasible && (hm.hangmei_gap ?? 0) === 0 && (
              <div className="mt-2 text-[11px] text-red-600 leading-relaxed">
                ⚠ 即使全月均切入航煤工况，最大航煤产出仅
                <span className="font-mono font-semibold mx-1">{fNum(hm.actual_H!)} 吨</span>
                ，仍不足目标
                <span className="font-mono mx-1">{fNum(hm.target)} 吨</span>
                ，缺口
                <span className="font-mono font-semibold mx-1">{fNum(hm.target - (hm.actual_H ?? 0))} 吨</span>
                。已选增量最大的方案，但无法完全满足目标。
              </div>
            )}
            {isReducedInfeasible && (hm.hangmei_gap ?? 0) > 0 && (
              <div className="mt-2 text-[11px] text-orange-600 leading-relaxed">
                ⚠ 航煤工况理论产出
                <span className="font-mono font-semibold mx-1">{fNum(hm.actual_H!)} 吨</span>
                已达目标，但实际物料平衡计算后产出仅
                <span className="font-mono font-semibold mx-1">{fNum(hm.effective_H!)} 吨</span>
                ，不足目标
                <span className="font-mono mx-1">{fNum(hm.target)} 吨</span>
                ，缺口
                <span className="font-mono font-semibold mx-1">{fNum(hm.hangmei_gap!)} 吨</span>
                。原因：估算口径与实际物料平衡存在偏差，部分原料因装置加工能力不足缓存在中间罐未加工。
              </div>
            )}
          </div>
            )
          })()}

          {/* 第2步：H_default 计算（各批次常规产出明细） */}
          {hm.h_default_details && hm.h_default_details.length > 0 && (
            <CollapsibleSection step="①" title="常规产出 H_default 计算"
              summary={<span>H_default = <span className="text-amber-700">{fNum(H_default)} 吨</span></span>}>
              <div className="text-[11px] text-slate-400 mb-2">
                按各批次实际减一线方向分段累加：H_default = Σ 各装置(天数 × 有效进料 × 非航煤收率)
              </div>
              <div className="overflow-x-auto rounded-lg border border-slate-100">
                <table className="w-full text-[11px]">
                  <thead>
                    <tr className="bg-slate-50 text-slate-500 border-b border-slate-100">
                      <th className="text-center font-medium px-2 py-1.5" rowSpan={2}>批次</th>
                      <th className="text-left font-medium px-2 py-1.5" rowSpan={2}>油种</th>
                      <th className="text-center font-medium px-2 py-1.5" rowSpan={2}>方向</th>
                      <th className="text-right font-medium px-2 py-1.5" rowSpan={2}>天数</th>
                      <th className="text-center font-medium px-2 py-1.5 border-l border-slate-200" colSpan={3}>{(hm.active_devices || []).map(d => d.device_name).join('+') || '主动装置'}航煤</th>
                      <th className="text-center font-medium px-2 py-1.5 border-l border-slate-200" colSpan={3}>{(hm.passive_devices || []).map(d => d.device_name).join('+') || '被动装置'}航煤</th>
                      <th className="text-right font-medium px-2 py-1.5 border-l border-slate-200" rowSpan={2}>合计产出</th>
                    </tr>
                    <tr className="bg-slate-50 text-slate-500 border-b border-slate-100">
                      <th className="text-right font-medium px-2 py-1.5 border-l border-slate-200">进料(吨/日)</th>
                      <th className="text-right font-medium px-2 py-1.5">收率</th>
                      <th className="text-right font-medium px-2 py-1.5">产出(吨)</th>
                      <th className="text-right font-medium px-2 py-1.5 border-l border-slate-200">进料(吨/日)</th>
                      <th className="text-right font-medium px-2 py-1.5">收率</th>
                      <th className="text-right font-medium px-2 py-1.5">产出(吨)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {hm.h_default_details.map((d, i) => (
                      <tr key={i} className="border-b border-slate-50 last:border-0">
                        <td className="text-center px-2 py-1.5 text-slate-400 font-mono">{d.batch_id}</td>
                        <td className="text-left px-2 py-1.5 text-slate-700">{d.crude_type}</td>
                        <td className="text-center px-2 py-1.5">
                          <span className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-medium ${d.mode === 'X_ZERO' ? 'bg-purple-50 text-purple-700' : 'bg-blue-50 text-blue-700'}`}>
                            {MODE_SHORT[d.mode] || d.mode}
                          </span>
                        </td>
                        <td className="text-right px-2 py-1.5 font-mono text-slate-600">{d.days.toFixed(2)}</td>
                        <td className="text-right px-2 py-1.5 font-mono text-slate-600 border-l border-slate-100">{fNum(d.cyjq_effective_input ?? d.daily_input)}</td>
                        <td className="text-right px-2 py-1.5 font-mono text-slate-500">{(d.yield_low * 100).toFixed(2)}%</td>
                        <td className="text-right px-2 py-1.5 font-mono text-slate-600">{fNum(d.cyjq_output ?? 0)}</td>
                        <td className="text-right px-2 py-1.5 font-mono text-slate-600 border-l border-slate-100">{fNum(d.lyjq_effective_input ?? 0)}</td>
                        <td className="text-right px-2 py-1.5 font-mono text-slate-500">{((d.lyjq_yield_low ?? 0) * 100).toFixed(2)}%</td>
                        <td className="text-right px-2 py-1.5 font-mono text-slate-600">{fNum(d.lyjq_output ?? 0)}</td>
                        <td className="text-right px-2 py-1.5 font-mono font-semibold text-slate-700 border-l border-slate-100">{fNum(d.batch_output)}</td>
                      </tr>
                    ))}
                    <tr className="bg-amber-50/40 font-semibold">
                      <td colSpan={10} className="text-right px-2 py-1.5 text-slate-600">H_default 合计 →</td>
                      <td className="text-right px-2 py-1.5 font-mono text-amber-700 border-l border-slate-100">{fNum(H_default)}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div className="mt-1.5 text-[10px] text-slate-400">
                {(hm.active_devices || []).map(d => d.device_name).join('、') || '主动装置'}航煤收率随减一线方向变化；{(hm.passive_devices || []).map(d => d.device_name).join('、') || '被动装置'}航煤收率不受航煤工况影响（始终常规收率）。进料量为装置有效进料（含辅料），与经济效益分析同口径。
              </div>
            </CollapsibleSection>
          )}

          {/* 第2.5步：M 天数推导过程（按最优窗口实际覆盖批次逐天累加） */}
          {hm.m_calc && (
            <CollapsibleSection step="②" title="航煤工况天数 M 推导"
              summary={<span>M = <span className="text-sky-700">{hm.m_tons != null ? `${fNum(hm.m_tons)} 吨` : `${hm.m_days.toFixed(2)} 天`}</span> · 偏差 {fNum(hm.deviation)} 吨</span>}>
              <div className="overflow-x-auto rounded-lg border border-slate-100">
                <table className="w-full text-[11px]">
                  <thead>
                    <tr className="bg-slate-50 text-slate-500 border-b border-slate-100">
                      <th className="text-center font-medium px-2 py-1.5">批次</th>
                      <th className="text-left font-medium px-2 py-1.5">油种</th>
                      <th className="text-center font-medium px-2 py-1.5">方向</th>
                      <th className="text-right font-medium px-2 py-1.5">日加工量</th>
                      <th className="text-right font-medium px-2 py-1.5">非航煤收率</th>
                      <th className="text-right font-medium px-2 py-1.5">航煤收率</th>
                      <th className="text-right font-medium px-2 py-1.5">航煤日增量</th>
                      <th className="text-right font-medium px-2 py-1.5">覆盖天数</th>
                      <th className="text-right font-medium px-2 py-1.5">航煤增量</th>
                    </tr>
                  </thead>
                  <tbody>
                    {hm.m_calc.covered_batches.map((c, i) => {
                      const di = c.daily_input || 0
                      const yL = c.yield_low || 0
                      const yH = c.yield_high || 0
                      const delta = c.hm_daily_delta || 0
                      return (
                        <tr key={i} className="border-b border-slate-50 last:border-0">
                          <td className="text-center px-2 py-1.5 text-slate-400 font-mono">{c.batch_id}</td>
                          <td className="text-left px-2 py-1.5 text-slate-700">{c.crude}</td>
                          <td className="text-center px-2 py-1.5">
                            <span className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-medium ${c.mode === 'X_ZERO' ? 'bg-purple-50 text-purple-700' : 'bg-blue-50 text-blue-700'}`}>
                              {MODE_SHORT[c.mode || ''] || c.mode || '-'}
                            </span>
                          </td>
                          <td className="text-right px-2 py-1.5 font-mono text-slate-500">{fNum(di)}</td>
                          <td className="text-right px-2 py-1.5 font-mono text-slate-400">{(yL * 100).toFixed(2)}%</td>
                          <td className="text-right px-2 py-1.5 font-mono text-sky-600">{(yH * 100).toFixed(2)}%</td>
                          <td className="text-right px-2 py-1.5 font-mono text-slate-600" title={`${fNum(di)} × (${(yH*100).toFixed(2)}% − ${(yL*100).toFixed(2)}%) = ${fNum(delta)}`}>
                            {fNum(delta)} 吨/天
                          </td>
                          <td className="text-right px-2 py-1.5 font-mono text-slate-600">{c.days.toFixed(2)} 天</td>
                          <td className="text-right px-2 py-1.5 font-mono font-semibold text-sky-700">+{fNum(c.hm_delta || 0)} 吨</td>
                        </tr>
                      )
                    })}
                    <tr className="bg-sky-50/40 font-semibold">
                      <td colSpan={8} className="text-right px-2 py-1.5 text-slate-600">航煤总增量 Σ →</td>
                      <td className="text-right px-2 py-1.5 font-mono text-sky-700">+{fNum(hm.m_calc.hm_total)} 吨</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div className="mt-2 p-3 rounded-lg border border-slate-100 bg-slate-50/40 text-[11px] font-mono text-slate-600 space-y-1">
                <div>缺口 delta_H = {fNum(hm.m_calc.target)} − {fNum(hm.m_calc.H_default)} = <span className="text-red-600 font-semibold">{fNum(hm.m_calc.delta_H)}</span> 吨</div>
                <div>航煤总增量 = Σ(各批次日增量 × 覆盖天数) = <span className="text-sky-700 font-semibold">{fNum(hm.m_calc.hm_total)}</span> 吨</div>
                <div className="pt-1 border-t border-slate-200">M = Σ(各批次覆盖天数) = <span className="text-sky-700 font-bold text-[13px]">{hm.m_calc.M.toFixed(4)} 天</span>{hm.m_calc.M_tons != null && <span className="text-sky-600">（{fNum(hm.m_calc.M_tons)} 吨主料）</span>}</div>
                <div>N = {hm.total_days.toFixed(1)} − {hm.m_calc.M.toFixed(2)} = <span className="text-slate-800 font-semibold">{hm.n_days.toFixed(2)} 天</span></div>
              </div>
              <div className="mt-2 grid grid-cols-2 md:grid-cols-4 gap-3">
                <StatCell label="M 航煤工况" value={hm.m_tons != null ? `${fNum(hm.m_tons)} 吨` : `${hm.m_days.toFixed(2)} 天`} accent="text-sky-700" />
                <StatCell label="N 非航煤" value={hm.n_tons != null ? `${fNum(hm.n_tons)} 吨` : `${hm.n_days.toFixed(2)} 天`} />
                <StatCell label="航煤后理论产出" value={`${fNum(hm.actual_H)} 吨`} accent="text-sky-700" />
                <StatCell label="实际产出(物料平衡)" value={`${fNum(hm.effective_H ?? hm.actual_H)} 吨`}
                  accent={(hm.hangmei_gap ?? 0) > 0 ? 'text-orange-600' : 'text-emerald-600'} />
              </div>
              {(hm.hangmei_gap ?? 0) > 0 && (
                <div className="mt-2 p-2.5 rounded-lg border border-orange-200 bg-orange-50 text-[11px] text-orange-800 leading-relaxed">
                  ⚠ 航煤工况理论产出
                  <span className="font-mono font-semibold mx-1">{fNum(hm.actual_H)} 吨</span>
                  已达目标，但实际物料平衡计算后产出仅
                  <span className="font-mono font-semibold mx-1">{fNum(hm.effective_H!)} 吨</span>
                  ，缺口
                  <span className="font-mono font-semibold mx-1">{fNum(hm.hangmei_gap!)} 吨</span>
                  。原因：估算口径与实际物料平衡存在偏差，部分原料因装置加工能力不足缓存在中间罐未加工。
                </div>
              )}
            </CollapsibleSection>
          )}

          {/* 第3步：最优时段搜索 */}
          {hasWindowSearch && (
            <CollapsibleSection step="③" title="最优时段搜索"
              summary={<span>起始第 {(hm.hangmei_start || 0).toFixed(1)} 天 · {ws.length} 个候选</span>}>
              <WindowSearchTable ws={ws} deltaH={hm.m_calc?.delta_H || 0}
                hangmeiStart={hm.hangmei_start || 0} mDays={hm.m_days}
                hmPrice={hm.hm_price || 0} rlydmxPrice={hm.rlydmx_price || 0}
                fNum={fNum} MODE_SHORT={MODE_SHORT} />
            </CollapsibleSection>
          )}

          {/* 第4步：边际贡献（已计入总效益，仅供展示） */}
          {hm.net_benefit != null && (
            <CollapsibleSection step="④" title="航煤工况边际贡献"
              defaultOpen
              summary={
                <span className={hm.net_benefit >= 0 ? 'text-emerald-600 font-semibold' : 'text-red-600 font-semibold'}>
                  {hm.net_benefit >= 0 ? '+' : ''}{fNum(hm.net_benefit)} 元
                </span>
              }>
              <div className="text-[11px] text-slate-400 mb-2">
                航煤工况期（M={hm.m_days.toFixed(2)}天）改变{(hm.active_devices || []).map(d => d.device_name).join('、') || '主动装置'}各产品收率；{(hm.passive_devices || []).map(d => d.device_name).join('、') || '被动装置'}不受影响。
                增产收益与减产损失已自动反映在组合总效益中，下表为各产品收入变化的拆解。
                <span className="text-[10px] text-slate-400 ml-1">(已计入总效益，不可叠加)</span>
              </div>
              <div className="p-3 rounded-lg border border-sky-100 bg-sky-50/30">
                {/* 净增益汇总 */}
                <div className="flex items-center gap-2 mb-3">
                  <TrendingUp className="w-4 h-4 text-sky-600" />
                  <span className="text-[12px] font-semibold text-slate-700">净增益</span>
                  <span className="ml-2 text-[11px] text-slate-400">
                    = 增产 <span className="text-sky-700 font-mono">+{fNum(hm.hm_benefit || 0)}</span>
                    {' '}− 减产 <span className="text-amber-600 font-mono">{fNum(hm.rlydmx_loss || 0)}</span>
                  </span>
                  <span className={`ml-auto text-[18px] font-mono font-bold ${hm.net_benefit >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>
                    {hm.net_benefit >= 0 ? '+' : ''}{fNum(hm.net_benefit)} 元
                  </span>
                </div>

                {/* 动态产品明细表：按装置分组从 product_deltas_detail 渲染 */}
                {hm.product_deltas_detail && hm.product_deltas_detail.length > 0 ? (
                  (() => {
                    // 按 device_name 分组，未标注的归入首个主动装置名（向后兼容）
                    const _fallbackName = (hm.active_devices || [])[0]?.device_name || '主动装置'
                    const groups: Record<string, HangmeiProductDelta[]> = {}
                    for (const d of hm.product_deltas_detail) {
                      const g = d.device_name || _fallbackName
                      ;(groups[g] = groups[g] || []).push(d)
                    }
                    const groupNames = Object.keys(groups)
                    return (
                      <div className="space-y-3">
                        {groupNames.map(gName => (
                          <div key={gName}>
                            <div className="text-[10px] font-medium text-slate-500 mb-1 px-1 flex items-center gap-1.5">
                              <span className="inline-block w-1.5 h-1.5 rounded-full bg-slate-300" />
                              {gName}
                              <span className="text-slate-300 font-normal">（{groups[gName].length} 个产品）</span>
                            </div>
                            <div className="overflow-x-auto rounded-lg border border-slate-100">
                              <table className="w-full text-[11px]">
                                <thead>
                                  <tr className="bg-slate-50 text-slate-500 border-b border-slate-100">
                                    <th className="text-left font-medium px-2 py-1.5">产品</th>
                                    <th className="text-right font-medium px-2 py-1.5">非航煤收率</th>
                                    <th className="text-right font-medium px-2 py-1.5">航煤收率</th>
                                    <th className="text-right font-medium px-2 py-1.5">收率变化</th>
                                    <th className="text-right font-medium px-2 py-1.5">价格</th>
                                    <th className="text-right font-medium px-2 py-1.5">收入变化</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {groups[gName].map(d => {
                                    const isGain = d.delta_revenue >= 0
                                    const yDelta = d.yield_high - d.yield_low
                                    const changed = d.changed !== false  // 默认 true（向后兼容）
                                    return (
                                      <tr key={d.product_id} className={`border-b border-slate-50 last:border-0 ${changed ? '' : 'opacity-50'}`}>
                                        <td className="px-2 py-1.5 text-slate-700">
                                          <span className={`inline-block w-2 h-2 rounded-full mr-1.5 ${!changed ? 'bg-slate-300' : isGain ? 'bg-sky-500' : 'bg-amber-400'}`} />
                                          {d.product_name}
                                        </td>
                                        <td className="text-right px-2 py-1.5 font-mono text-slate-400">{(d.yield_low * 100).toFixed(2)}%</td>
                                        <td className={`text-right px-2 py-1.5 font-mono ${changed ? 'text-sky-600' : 'text-slate-400'}`}>{(d.yield_high * 100).toFixed(2)}%</td>
                                        <td className={`text-right px-2 py-1.5 font-mono ${!changed ? 'text-slate-300' : isGain ? 'text-sky-600' : 'text-amber-600'}`}>
                                          {yDelta >= 0 ? '+' : ''}{(yDelta * 100).toFixed(2)}%
                                        </td>
                                        <td className="text-right px-2 py-1.5 font-mono text-slate-500">{fNum(d.price)}</td>
                                        <td className={`text-right px-2 py-1.5 font-mono ${changed ? 'font-semibold' : ''} ${!changed ? 'text-slate-300' : isGain ? 'text-sky-700' : 'text-amber-600'}`}>
                                          {changed ? `${isGain ? '+' : '−'}${fNum(Math.abs(d.delta_revenue))}` : '—'}
                                        </td>
                                      </tr>
                                    )
                                  })}
                                </tbody>
                              </table>
                            </div>
                          </div>
                        ))}
                        <div className="flex items-center justify-end gap-2 pt-1 px-1 text-[11px]">
                          <span className="text-slate-500">净增益 = 增产收益 − 减产损失 →</span>
                          <span className={`font-mono font-bold ${hm.net_benefit >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>
                            {hm.net_benefit >= 0 ? '+' : ''}{fNum(hm.net_benefit)}
                          </span>
                        </div>
                      </div>
                    )
                  })()
                ) : (
                  /* 向后兼容：后端未返回 product_deltas_detail 时 fallback 到固定两列 */
                  <div className="grid grid-cols-2 gap-3 text-[11px]">
                    <div className="flex items-center gap-2 p-2 rounded bg-white/60">
                      <span className="w-2 h-2 rounded-full bg-sky-500 shrink-0" />
                      <div>
                        <div className="text-slate-500">增产收益</div>
                        <div className="font-mono text-sky-700 font-semibold">+{fNum(hm.hm_benefit || 0)} 元</div>
                        <div className="text-slate-400">航煤 {fNum(hm.hm_price || 0)} 元/吨</div>
                      </div>
                    </div>
                    <div className="flex items-center gap-2 p-2 rounded bg-white/60">
                      <span className="w-2 h-2 rounded-full bg-amber-400 shrink-0" />
                      <div>
                        <div className="text-slate-500">减产损失</div>
                        <div className="font-mono text-amber-600 font-semibold">−{fNum(hm.rlydmx_loss || 0)} 元</div>
                        <div className="text-slate-400">DMX {fNum(hm.rlydmx_price || 0)} 元/吨</div>
                      </div>
                    </div>
                  </div>
                )}
                <div className="mt-2 text-[10px] text-slate-400 leading-relaxed">
                  航煤工况仅改变{(hm.active_devices || []).map(d => d.device_name).join('、') || '主动装置'}产品收率，进料成本和加工成本不变，因此边际贡献 = 收入变化。
                  收入变化 = 日加工量 × (航煤收率 − 非航煤收率) × M天数 × 价格。
                  表中按装置列出全部产品；灰色行收率无变化，收入影响为 0。各产品变化之和即为净增益，已计入组合总效益。
                </div>
              </div>
            </CollapsibleSection>
          )}
        </>
      )}

      {/* 收率对比（始终展示） */}
      <div className="mt-3 pt-3 border-t border-slate-100 flex items-center gap-4 text-[12px] flex-wrap">
        <span className="text-slate-400">收率对比（{hm.product_name}）</span>
        <span className="inline-flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-sky-500" />
          <span className="text-slate-500">航煤工况</span>
          <span className="font-mono text-sky-700 font-semibold">{pct(hm.yield_high)}</span>
        </span>
        <span className="text-slate-300">vs</span>
        <span className="inline-flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-slate-300" />
          <span className="text-slate-500">常规</span>
          <span className="font-mono text-slate-600">{pct(hm.yield_low)}</span>
        </span>
        {ratio > 0 && (
          <span className="ml-auto text-[11px] text-slate-400">
            航煤工况收率约为常规的 <span className="font-mono text-sky-700">{ratio.toFixed(1)}</span> 倍
          </span>
        )}
      </div>
      </div>
      )}
    </div>
  )
}

// ── 经济效益说明报告化渲染 ─────────────────────────────────────────────
// 后端 _build_economic_explanation 生成结构化文本（带【】/编号段落/分隔线/──子段），
// 这里按行解析为结构化卡片，替代原 <pre> 等宽代码块。
export function EconReport({ text }: { text: string }) {
  const lines = text.split('\n')
  const out: React.ReactNode[] = []
  lines.forEach((raw, i) => {
    const line = raw.replace(/\s+$/, '')
    if (!line.trim()) return
    const trimmed = line.trim()

    // ═ 分隔线 → 渲染为水平分隔线
    if (/^═+$/.test(trimmed)) {
      out.push(<div key={i} className="border-t border-slate-200 my-1" />)
      return
    }
    // ── 子段标题（如 ── 进料明细 ──）
    const subsec = /^\s*──\s*(.+?)\s*──$/.exec(raw)
    if (subsec) {
      out.push(<div key={i} className="ml-2 text-[11px] font-semibold text-slate-500 mt-1.5 mb-0.5">{subsec[1]}</div>)
      return
    }
    // ────... 子分隔线
    if (/^\s*─{10,}/.test(raw)) {
      out.push(<div key={i} className="ml-2 border-t border-dashed border-slate-100 my-0.5" />)
      return
    }
    // 【主标题】（含尾部括号标签）
    if (/^【经济效益分析说明】/.test(trimmed)) {
      const tagMatch = /（.+）$/.exec(trimmed)
      out.push(
        <div key={i} className="flex items-center gap-2 pt-1">
          <span className="text-[14px] font-bold text-slate-800">{trimmed.replace(/[【】]/g, '').replace(/（.+）$/, '')}</span>
          {tagMatch && <span className="text-[11px] px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 font-medium">{tagMatch[0]}</span>}
        </div>
      )
      return
    }
    // 【N】装置名（device_id）
    const dev = /^【(\d+)】(.+)$/.exec(trimmed)
    if (dev) {
      out.push(
        <div key={i} className="flex items-center gap-2 mt-2 pt-1.5 border-t border-slate-50">
          <span className="w-5 h-5 rounded-md grid place-items-center bg-indigo-100 text-indigo-700 text-[11px] font-bold shrink-0">{dev[1]}</span>
          <span className="text-[13px] font-semibold text-slate-800">{dev[2]}</span>
        </div>
      )
      return
    }
    // 总收益行 → 高亮卡
    const rev = /^总收益[：:](.+)$/.exec(trimmed)
    if (rev) {
      out.push(
        <div key={i} className="flex items-center gap-2 rounded-lg border border-amber-200 bg-gradient-to-br from-amber-50/70 to-orange-50/40 px-4 py-2.5">
          <Coins className="w-4 h-4 text-amber-600 shrink-0" />
          <span className="text-[12px] text-amber-700/80 font-medium">总收益</span>
          <span className="font-mono text-[15px] font-bold text-amber-700">{rev[1].trim()}</span>
        </div>
      )
      return
    }
    // 中文数字段标题 一、二、三、四、五
    if (/^[一二三四五六七八九十]+、/.test(trimmed)) {
      out.push(
        <div key={i} className="flex items-center gap-2 pt-2.5 pb-0.5">
          <span className="text-[13px] font-bold text-slate-800">{trimmed}</span>
        </div>
      )
      return
    }
    // ⚠ 警告行（月负荷超容）
    if (/^\s*⚠/.test(raw)) {
      out.push(<div key={i} className="ml-4 text-[12px] text-amber-700 font-medium bg-amber-50/60 rounded px-2 py-1">{trimmed}</div>)
      return
    }
    // ✓ 正常行（月负荷未超容）
    if (/^\s*✓/.test(raw)) {
      out.push(<div key={i} className="ml-4 text-[12px] text-emerald-700">{trimmed}</div>)
      return
    }
    // └ 子来源行（跨装置聚合的产品来源）
    const subsrc = /^\s+└\s*(.+)$/.exec(raw)
    if (subsrc) {
      out.push(<div key={i} className="ml-10 text-[11px] text-slate-400 font-mono">{subsrc[1].trim()}</div>)
      return
    }
    // 产品明细行  - 产品名...
    const prod = /^\s*-\s*(.+)$/.exec(raw)
    if (prod) {
      out.push(
        <div key={i} className="ml-4 inline-flex items-start gap-1.5 rounded-md bg-slate-50 border border-slate-100 px-2 py-1">
          <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 mt-1 shrink-0" />
          <span className="text-[11.5px] text-slate-600 font-mono">{prod[1].trim()}</span>
        </div>
      )
      return
    }
    // 编号产品行  N. 产品名...（总体汇总中的产品列表）
    const numProd = /^\s*(\d+)\.\s*(.+)$/.exec(raw)
    if (numProd) {
      out.push(
        <div key={i} className="ml-4 flex items-start gap-2 py-0.5">
          <span className="text-[11px] text-slate-400 font-mono shrink-0">{numProd[1]}.</span>
          <span className="text-[11.5px] text-slate-600 font-mono">{numProd[2].trim()}</span>
        </div>
      )
      return
    }
    // 【主料】/【辅料】 进料行
    const feedLabel = /^\s*【(主料|辅料)】(.+)$/.exec(raw)
    if (feedLabel) {
      const isMain = feedLabel[1] === '主料'
      out.push(
        <div key={i} className="ml-4 flex items-start gap-1.5 py-0.5">
          <span className={`text-[10px] px-1 rounded shrink-0 mt-0.5 ${isMain ? 'bg-blue-50 text-blue-600' : 'bg-amber-50 text-amber-600'}`}>{feedLabel[1]}</span>
          <span className="text-[11.5px] text-slate-600 font-mono">{feedLabel[2].trim()}</span>
        </div>
      )
      return
    }
    // 效益核算/成本/收入等结果行（含中文冒号）
    const result = /^\s{2,}([\u4e00-\u9fa5].+[：:])(.+)$/.exec(raw)
    if (result) {
      const isProfit = /利润|效益|收益/.test(result[1])
      const isCost = /成本/.test(result[1])
      out.push(
        <div key={i} className="ml-4 flex items-baseline gap-2 py-0.5">
          <span className="text-[12px] text-slate-500">{result[1]}</span>
          <span className={`text-[12px] font-mono font-medium ${isProfit ? 'text-emerald-700' : isCost ? 'text-rose-600' : 'text-slate-700'}`}>{result[2].trim()}</span>
        </div>
      )
      return
    }
    // 折减明细表头行（含装置/月负荷/能力等列名）
    if (/^\s+装置\s+月负荷/.test(raw)) {
      out.push(<div key={i} className="ml-4 text-[11px] text-slate-400 font-mono py-0.5">{trimmed}</div>)
      return
    }
    // 折减明细数据行（⚠/✓ 开头的表格行）
    if (/^\s+[⚠✓]\s/.test(raw)) {
      out.push(<div key={i} className="ml-4 text-[11px] font-mono py-0.5">{trimmed}</div>)
      return
    }
    // 合计行
    if (/^\s*合计/.test(raw)) {
      out.push(<div key={i} className="ml-4 text-[12px] font-mono font-semibold text-slate-700 py-0.5">{trimmed}</div>)
      return
    }
    // 普通行（缩进2格以上的详情）
    const indent = raw.match(/^(\s*)/)?.[1].length || 0
    const ml = Math.min(indent * 3, 24)
    out.push(<div key={i} className="text-[12px] text-slate-600 leading-relaxed" style={{ marginLeft: `${ml}px` }}>{trimmed}</div>)
  })
  return <div className="space-y-0.5">{out}</div>
}

// ── 分装置损益汇总（供效益预测页展示装置级收入−成本=利润）──────────────
export function DeviceProfitSummary({ breakdown }: {
  breakdown?: EconomicBreakdown | null
}) {
  const [show, setShow] = useState(false)
  if (!breakdown || !breakdown.devices?.length) return null

  // NaN / undefined 防御
  const safe = (v: any, fallback = 0) => (v != null && typeof v === 'number' && !Number.isNaN(v) ? v : fallback)

  // 总利润（与 KPI 卡一致）
  const totalProfit = safe(breakdown.totals?.profit)
  const totalRevenue = safe(breakdown.totals?.product_revenue)
  const totalCost = safe(breakdown.totals?.total_cost)

  return (
    <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
      <button onClick={() => setShow(v => !v)}
        className="w-full flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60 hover:bg-slate-50 transition-colors">
        {show ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevRight className="w-4 h-4 text-slate-400" />}
        <CardHead icon={Factory} title="加工装置损益汇总" accent="from-emerald-500 to-emerald-600"
          hint="各加工装置：收入 − 原料成本 − 加工成本 = 装置效益，不含常减压装置" />
      </button>
      {show && (
      <div className="p-4">
      {/* 装置损益卡片 */}
      <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-3">
        {breakdown.devices.map(d => {
          const devProfit = safe(d.revenue) - safe(d.crude_cost) - safe(d.energy_cost)
          const mainName = d.main_feed_name || ''
          return (
            <div key={d.device_id} className="rounded-lg border border-slate-200 bg-slate-50/40 p-3.5">
              <div className="text-[13px] font-semibold text-slate-800 mb-2.5">{d.device_name}</div>
              <div className="space-y-1.5 text-[12px]">
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">连接主料{mainName && <span className="text-slate-400">（{mainName}）</span>}</span>
                  <span className="font-mono text-slate-500">{fNum(safe(d.main_feed_qty) || safe(d.input_amount))} 吨</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">主料负荷量</span>
                  <span className="font-mono text-slate-600">{fNum(safe(d.main_load_qty))} 吨</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-slate-500">总进料量</span>
                  <span className="font-mono text-slate-700">{fNum(safe(d.total_feed_qty) || safe(d.effective_input))} 吨</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-blue-600">销售收入</span>
                  <span className="font-mono text-blue-700 font-semibold">+{fNum(safe(d.revenue))} 元</span>
                </div>
                {/* 产出明细（收率/产量/收入，与 economic_explanation 同源） */}
                {d.products?.length > 0 && (
                  <div className="mt-1 pt-1.5 border-t border-dashed border-slate-200">
                    <div className="text-[11px] text-slate-400 mb-1">产出明细</div>
                    <div className="space-y-0.5">
                      {[...d.products].sort((a,b) => b.revenue - a.revenue).map(p => (
                        <div key={p.product_name} className="flex items-baseline gap-1.5 text-[11px] leading-tight">
                          <span className="text-slate-700 truncate flex-1">{p.product_name}</span>
                          {p.overall_yield > 0 && (
                            <span className="font-mono text-slate-400 shrink-0">综合收率{p.overall_yield.toFixed(1)}%</span>
                          )}
                          <span className="font-mono text-slate-600 shrink-0">{fNum(p.output)} 吨</span>
                          <span className="font-mono text-blue-600 shrink-0">{fNum(p.revenue)} 元</span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
                <div className="flex justify-between items-center">
                  <span className="text-rose-600">原料成本</span>
                  <span className="font-mono text-rose-600">−{fNum(safe(d.crude_cost))} 元</span>
                </div>
                {/* 进料明细（配比/单价/成本，与 economic_explanation 同源） */}
                {d.feeds?.length > 0 && (
                  <div className="mt-1 pt-1.5 border-t border-dashed border-slate-200">
                    <div className="text-[11px] text-slate-400 mb-1">进料明细</div>
                    <div className="space-y-0.5">
                      {d.feeds.map(f => (
                        <div key={f.name} className="flex items-baseline gap-1.5 text-[11px] leading-tight">
                          <span className={`inline-flex items-center px-1 rounded text-[9px] font-medium shrink-0 ${
                            f.label === '主料' ? 'bg-blue-100 text-blue-700' : 'bg-slate-100 text-slate-500'
                          }`}>{f.label || '料'}</span>
                          <span className="text-slate-700 truncate flex-1">{f.name}</span>
                          <span className="font-mono text-slate-500 shrink-0">{f.ratio.toFixed(1)}%</span>
                          <span className="font-mono text-slate-600 shrink-0">{fNum(f.quantity)} 吨</span>
                          <span className="font-mono text-rose-500 shrink-0">{fNum(f.cost)} 元</span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
                <div className="flex justify-between items-center">
                  <span className="text-amber-600">加工成本</span>
                  <span className="font-mono text-amber-600">−{fNum(safe(d.energy_cost))} 元</span>
                </div>
                <div className="border-t border-slate-200 pt-1.5 mt-1.5 flex justify-between items-center font-semibold">
                  <span className={devProfit >= 0 ? 'text-emerald-700' : 'text-red-600'}>装置效益</span>
                  <span className={`font-mono ${devProfit >= 0 ? 'text-emerald-700' : 'text-red-600'}`}>
                    {devProfit >= 0 ? '+' : ''}{fNum(devProfit)} 元
                  </span>
                </div>
              </div>
              {/* 装置级瀑布条：收入 vs 成本(原料+加工)，差值=利润 */}
              {(() => {
                const rev = safe(d.revenue)
                const crude = safe(d.crude_cost)
                const energy = safe(d.energy_cost)
                const cost = crude + energy
                const costPct = rev > 0 ? Math.min((cost / rev) * 100, 100) : 0
                const crudePctOfCost = cost > 0 ? (crude / cost) * 100 : 0
                const energyPctOfCost = cost > 0 ? (energy / cost) * 100 : 0
                return (
                  <div className="mt-3 space-y-1">
                    <div className="flex items-center gap-1.5">
                      <span className="text-[10px] text-blue-500 w-8 shrink-0">收入</span>
                      <div className="flex-1 h-3 rounded overflow-hidden bg-slate-100">
                        <div className="bg-blue-400 h-full" style={{ width: '100%' }} title={`收入 ${fNum(rev)}`} />
                      </div>
                      <span className="font-mono text-[10px] text-blue-700 w-16 text-right shrink-0">{fNum(rev)}</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <span className="text-[10px] text-rose-500 w-8 shrink-0">成本</span>
                      <div className="flex-1 h-3 rounded overflow-hidden bg-slate-100">
                        <div className="flex h-full" style={{ width: `${costPct}%` }} title={`成本 ${fNum(cost)}`}>
                          <div className="bg-rose-400 h-full" style={{ width: `${crudePctOfCost}%` }} title={`原料 ${fNum(crude)}`} />
                          <div className="bg-amber-400 h-full" style={{ width: `${energyPctOfCost}%` }} title={`加工 ${fNum(energy)}`} />
                        </div>
                      </div>
                      <span className="font-mono text-[10px] text-rose-600 w-16 text-right shrink-0">{fNum(cost)}</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <span className="text-[10px] text-emerald-600 w-8 shrink-0">效益</span>
                      <div className="flex-1 h-3 rounded overflow-hidden bg-slate-100 relative flex justify-end">
                        {devProfit >= 0 ? (
                          <div className="bg-emerald-400 h-full" style={{ width: `${Math.min((devProfit / rev) * 100, 100)}%` }} title={`效益 ${fNum(devProfit)}`} />
                        ) : (
                          <div className="bg-red-400 h-full" style={{ width: `${Math.min((-devProfit / rev) * 100, 100)}%` }} title={`亏损 ${fNum(-devProfit)}`} />
                        )}
                      </div>
                      <span className={`font-mono text-[10px] w-16 text-right shrink-0 ${devProfit >= 0 ? 'text-emerald-700' : 'text-red-600'}`}>
                        {devProfit >= 0 ? '+' : ''}{fNum(devProfit)}
                      </span>
                    </div>
                    <div className="flex justify-between text-[9px] text-slate-400 px-9">
                      <span><span className="inline-block w-2 h-2 bg-rose-400 rounded-sm align-middle mr-0.5" />原料</span>
                      <span><span className="inline-block w-2 h-2 bg-amber-400 rounded-sm align-middle mr-0.5" />加工</span>
                    </div>
                  </div>
                )
              })()}
            </div>
          )
        })}
      </div>
      {/* 总效益行 */}
      <div className="mt-3 flex items-center justify-between rounded-lg border border-emerald-200 bg-gradient-to-r from-emerald-50/60 to-white px-4 py-2.5">
        <div className="flex items-center gap-2">
          <Wallet className="w-4 h-4 text-emerald-600" />
          <span className="text-[13px] font-semibold text-emerald-800">{breakdown.devices.length}套装置合计</span>
        </div>
        <div className="text-right">
          <div className="font-mono text-[15px] font-bold text-emerald-700">
            {totalProfit >= 0 ? '+' : ''}{fNum(totalProfit)} 元
          </div>
          <div className="text-[11px] text-slate-400">
            收入 {fNum(totalRevenue)} − 成本 {fNum(totalCost)}
          </div>
        </div>
      </div>
      </div>
      )}
    </div>
  )
}

// 供页面在 loading 时复用的占位
export function SolveResultLoading() {
  return (
    <div className="flex items-center justify-center py-16 text-slate-400">
      <Loader2 className="w-5 h-5 animate-spin mr-2" />求解中…
    </div>
  )
}
