'use client'

import {
  ClipboardCheck, Coins, Droplets, Flame, Calculator, Wallet,
  Factory, Package, CalendarDays, AlertTriangle, Info,
} from 'lucide-react'
import {
  SolveResp, EconomicBreakdown,
  CardHead, KpiCard, HangmeiCard, EconReport,
  fNum, MODE_CN, DEV_LYJQ, SolveBatchWithShutdown,
} from './SolveResult'

// ── 业务决策台结果展示（面向生产技术部生产规划人员）──────────────────────
// 与 SolveResult（开发者视角）同源数据，但：
//  · 用业务语言组织（决策结论 + 损益拆解 + 排产执行 + 航煤工况 + 假设说明）
//  · 隐藏切换组合枚举/按天明细等校验用细节
//  · 效益数字取自 economic_breakdown（结构化，与 economic_explanation 文本同源同值）
export function BusinessResult({
  result, elapsedMs, crudeName,
}: {
  result: SolveResp
  elapsedMs: number
  crudeName: (id: string) => string
}) {
  if (!result.success || !result.combination_results?.length) {
    return (
      <div className="p-5 rounded-xl border border-amber-200 bg-amber-50/40">
        <div className="flex items-center gap-2 text-amber-800">
          <AlertTriangle className="w-5 h-5" />
          <span className="font-semibold">求解失败</span>
        </div>
        <p className="text-sm text-amber-700 mt-2">{result.message}</p>
        {/* 停工冲突明细（后端 shutdown.conflicts 透传，逐日列出冲突原因） */}
        {result.shutdown?.conflicts?.length ? (
          <div className="mt-3">
            <div className="text-xs font-medium text-amber-800 mb-1.5">停工冲突明细</div>
            <div className="flex flex-wrap gap-1.5">
              {result.shutdown.conflicts.map((c, i) => (
                <span key={i} className="inline-flex items-center gap-1 px-2 py-1 rounded-md bg-white/70 border border-amber-200 text-[11px] text-amber-700">
                  <span className="font-mono font-semibold">第{c.day}天</span>
                  <span className="text-amber-500">·</span>
                  <span>{c.reason}</span>
                </span>
              ))}
            </div>
            <p className="text-[11px] text-amber-600 mt-2">
              柴加与蜡加同日停工时减一线无去向，请错开两装置的停工日期段。
            </p>
          </div>
        ) : null}
      </div>
    )
  }

  // optimal_combination 是瘦 dict，完整 batch_results 从 combination_results 按 id 查
  const optimalCombo = result.optimal_combination
    ? (result.combination_results.find(c => c.combination_id === result.optimal_combination!.combination_id) ?? null)
    : null
  if (!optimalCombo) {
    // 求解成功但无可行方案（所有组合均超容）：展示提示卡而非空白
    const theoryBest = [...result.combination_results].sort((a, b) => b.total_revenue - a.total_revenue)[0]
    return (
      <div className="p-5 rounded-xl border border-amber-200 bg-amber-50/40">
        <div className="flex items-center gap-2 text-amber-800">
          <AlertTriangle className="w-5 h-5" />
          <span className="font-semibold">本月无可行排产方案</span>
        </div>
        <p className="text-sm text-amber-700 mt-2">
          {result.message}。所有 {result.total_combinations} 种切换组合均存在装置超容，无法执行。
          {theoryBest && (
            <>理论收益最高的组合 #{theoryBest.combination_id} 为 <span className="font-mono">{fNum(theoryBest.total_revenue / 10000)} 万元</span>（基于超负荷进料量计算，仅供产能瓶颈参考）。</>
          )}
        </p>
      </div>
    )
  }

  // 效益拆解（结构化）；缺省时回退到 optimal_revenue
  const bd: EconomicBreakdown | undefined = result.economic_breakdown
  const t = bd?.totals
  const profit = t?.profit ?? result.optimal_revenue
  const crudeCost = t?.crude_cost ?? 0
  const energyCost = t?.energy_cost ?? 0
  const productRev = t?.product_revenue ?? 0
  const totalCost = t?.total_cost ?? (crudeCost + energyCost)
  const totalInput = t?.total_input ?? optimalCombo.batch_results.reduce((s, b) => s + b.total_input, 0)
  const wan = (n: number) => `${fNum(n / 10000)} 万元`
  const yuan = (n: number) => `${fNum(n)} 元`

  return (
    <div className="space-y-4">
      {/* ① 决策结论卡：一句话结论 + 损益 KPI 行 */}
      <div className="p-5 rounded-xl border border-emerald-200 bg-gradient-to-br from-emerald-50/60 to-white shadow-sm">
        <CardHead icon={ClipboardCheck} title="决策结论" accent="from-emerald-500 to-emerald-600"
          hint={`求解耗时 ${(elapsedMs / 1000).toFixed(2)} 秒`} />
        <div className="mt-3 text-[15px] text-slate-700 leading-relaxed">
          <span className="text-slate-500">建议方案：</span>
          本月分 <b className="text-slate-900">{result.batches.length}</b> 批加工
          <b className="font-mono text-slate-900"> {fNum(totalInput)} </b>吨原料，预计实现效益
          <b className="font-mono text-emerald-700"> {wan(profit)}</b>
          。
        </div>
        <div className="mt-1 text-[12px] text-slate-400">
          经全部 {result.total_combinations} 种切换组合评估，组合 #{optimalCombo.combination_id} 效益最优
        </div>
        {/* 双价格月：选方案(上月价) → 算效益(本月价) 两步 */}
        {result.selection_revenue != null && result.selection_revenue > 0 && result.selection_price_month && result.final_price_month && (
          <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px] rounded-lg bg-indigo-50/60 border border-indigo-100 px-3 py-2">
            <span className="text-indigo-600 font-medium">① 选方案</span>
            <span className="text-slate-500">按 {result.selection_price_month} 价格评估 → 组合 #{optimalCombo.combination_id}，选优效益</span>
            <span className="font-mono text-slate-700">{fNum(result.selection_revenue / 10000)} 万元</span>
            <span className="text-slate-300">→</span>
            <span className="text-emerald-600 font-medium">② 算效益</span>
            <span className="text-slate-500">按 {result.final_price_month} 价格核算实际效益</span>
            <span className="font-mono text-emerald-700">{fNum((result.optimal_revenue ?? 0) / 10000)} 万元</span>
            <span className={'font-mono ' + (((result.optimal_revenue ?? 0) - result.selection_revenue) >= 0 ? 'text-emerald-600' : 'text-rose-600')}>
              （{((result.optimal_revenue ?? 0) - result.selection_revenue) >= 0 ? '+' : ''}{fNum(((result.optimal_revenue ?? 0) - result.selection_revenue) / 10000)} 万元）
            </span>
          </div>
        )}
        <div className="mt-4 grid grid-cols-2 md:grid-cols-5 gap-3">
          <KpiCard icon={Coins} label="销售收入" value={wan(productRev)} sub={yuan(productRev)} accent="from-blue-500 to-blue-600" />
          <KpiCard icon={Droplets} label="原料成本" value={wan(crudeCost)} sub={yuan(crudeCost)} accent="from-rose-500 to-rose-600" />
          <KpiCard icon={Flame} label="加工成本" value={wan(energyCost)} sub={yuan(energyCost)} accent="from-amber-500 to-orange-500" />
          <KpiCard icon={Calculator} label="总成本" value={wan(totalCost)} sub={yuan(totalCost)} accent="from-slate-500 to-slate-600" />
          <KpiCard icon={Wallet} label="总利润" value={wan(profit)} sub={yuan(profit)} accent="from-emerald-500 to-emerald-600" />
        </div>
      </div>

      {/* ② 效益拆解卡：装置贡献 + 产品产出（结构化，替代开发者页的纯文字说明） */}
      {bd && bd.devices.length > 0 && (
        <div className="p-4 rounded-xl border border-[#E6EAF1] bg-white shadow-sm space-y-4">
          <CardHead icon={Factory} title="效益拆解 · 装置贡献" accent="from-blue-500 to-blue-600"
            hint="各装置加工量与贡献收入占比" />
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
                <th className="text-left font-medium px-3 py-2">装置</th>
                <th className="text-right font-medium px-3 py-2">加工量(吨)</th>
                <th className="text-right font-medium px-3 py-2">贡献收入(元)</th>
                <th className="text-right font-medium px-3 py-2 w-24">收入占比</th>
              </tr>
            </thead>
            <tbody>
              {bd.devices.map(d => (
                <tr key={d.device_id} className="border-b border-slate-50 last:border-0">
                  <td className="px-3 py-2 text-slate-700">{d.device_name}</td>
                  <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(d.input_amount)}</td>
                  <td className="px-3 py-2 text-right font-mono text-emerald-700">{fNum(d.revenue)}</td>
                  <td className="px-3 py-2 text-right">
                    <div className="flex items-center justify-end gap-2">
                      <div className="w-14 h-1.5 rounded-full bg-slate-100 overflow-hidden">
                        <div className="h-full bg-blue-500" style={{ width: `${Math.min(d.share, 100)}%` }} />
                      </div>
                      <span className="font-mono text-[12px] text-slate-600 w-12 text-right">{d.share.toFixed(1)}%</span>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <CardHead icon={Package} title="效益拆解 · 产品产出" accent="from-purple-500 to-purple-600"
            hint="各产品产量、单价与收入占比" />
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
                <th className="text-left font-medium px-3 py-2">产品</th>
                <th className="text-right font-medium px-3 py-2">产量(吨)</th>
                <th className="text-right font-medium px-3 py-2">单价(元/吨)</th>
                <th className="text-right font-medium px-3 py-2">收入(元)</th>
                <th className="text-right font-medium px-3 py-2 w-24">收入占比</th>
              </tr>
            </thead>
            <tbody>
              {bd.products.map(p => (
                <tr key={p.product_name} className="border-b border-slate-50 last:border-0">
                  <td className="px-3 py-2 text-slate-700">{p.product_name}</td>
                  <td className="px-3 py-2 text-right font-mono text-slate-700">{fNum(p.quantity)}</td>
                  <td className="px-3 py-2 text-right font-mono text-slate-500">{fNum(p.price)}</td>
                  <td className="px-3 py-2 text-right font-mono text-emerald-700">{fNum(p.revenue)}</td>
                  <td className="px-3 py-2 text-right">
                    <div className="flex items-center justify-end gap-2">
                      <div className="w-14 h-1.5 rounded-full bg-slate-100 overflow-hidden">
                        <div className="h-full bg-purple-500" style={{ width: `${Math.min(p.share, 100)}%` }} />
                      </div>
                      <span className="font-mono text-[12px] text-slate-600 w-12 text-right">{p.share.toFixed(1)}%</span>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* ③ 排产执行卡：精简批次时间线（油种/起止天/加工量/减一线去向，无组合枚举与按天明细） */}
      <div className="rounded-xl border border-[#E6EAF1] bg-white shadow-sm overflow-hidden">
        <div className="flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60">
          <CardHead icon={CalendarDays} title="排产执行计划" accent="from-blue-500 to-blue-600"
            hint={`共 ${optimalCombo.batch_results.length} 个批次 · 按油种连续聚合`} />
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-slate-50 text-[12px] text-slate-500 border-b border-slate-100">
              <th className="text-center font-medium px-3 py-2 w-12">#</th>
              <th className="text-left font-medium px-3 py-2">油种</th>
              <th className="text-center font-medium px-3 py-2">起止天</th>
              <th className="text-right font-medium px-3 py-2">加工量(吨)</th>
              <th className="text-center font-medium px-3 py-2">减一线方向</th>
              <th className="text-right font-medium px-3 py-2">批次效益(元)</th>
            </tr>
          </thead>
          <tbody>
            {optimalCombo.batch_results.map(b => {
              const batchMeta = result.batches.find(x => x.batch_id === b.batch_id) as SolveBatchWithShutdown | undefined
              const shutdownDevs = batchMeta?.shutdown_intervals ? Object.keys(batchMeta.shutdown_intervals) : []
              const isShutdown = shutdownDevs.length > 0
              return (
              <tr key={b.batch_id} className={`border-b border-slate-50 last:border-0 hover:bg-slate-50/40 ${isShutdown ? 'bg-red-50/30' : ''} ${b.is_hangmei_period ? 'bg-sky-50/40' : ''}`}>
                <td className="px-3 py-2 text-center text-slate-400 font-mono text-xs">
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
                <td className={`px-3 py-2 text-right font-mono ${b.revenue < 0 ? 'text-red-600' : 'text-emerald-700'}`}>{fNum(b.revenue)}</td>
              </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {/* ④ 航煤工况卡（复用 SolveResult 同款，仅 hangmei_summary.enabled 时展示） */}
      {result.hangmei_summary?.enabled && <HangmeiCard hm={result.hangmei_summary} />}

      {/* ⑤ 关键假设说明（仅渲染 economic_explanation 第5节，避免与拆解卡重复） */}
      {result.economic_explanation && (() => {
        const idx = result.economic_explanation.indexOf('5. 关键假设与说明')
        const assumptions = idx >= 0 ? result.economic_explanation.slice(idx) : ''
        if (!assumptions) return null
        return (
          <div className="p-5 rounded-xl border border-[#E6EAF1] bg-white shadow-sm">
            <CardHead icon={Info} title="关键假设与说明" accent="from-slate-500 to-slate-600" />
            <div className="mt-3 space-y-2">
              <EconReport text={assumptions} />
            </div>
          </div>
        )
      })()}
    </div>
  )
}
