"use client";
import { useState } from "react";
import useApiData from "@/components/useApiData";
import NoDataHint from "@/components/NoDataHint";
import { fmt, crudeColor } from "@/lib/api";

export default function CompliancePage() {
  const { data, loading, error, noData } = useApiData();
  const [round, setRound] = useState(0);

  if (loading) return <div className="text-center py-20 text-slate-400">{"\u52a0\u8f7d\u4e2d..."}</div>;
  if (error) return <div className="text-red-400 p-4">{"\u9519\u8bef"}: {error}</div>;
  if (noData) return <NoDataHint />;
  if (!data) return null;

  const { rounds, selected_idx } = data;
  const compList = data.compliance_by_round || [data.compliance];
  const comp = compList[round] || compList[0];

  return (
    <div className="space-y-4">
      {/* 多轮评分表 */}
      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">{"\u591a\u8f6e\u8bc4\u5206\u5bf9\u6bd4"}</h2>
        <table className="w-full text-xs">
          <thead><tr className="text-slate-400 border-b border-slate-700">
            <th className="text-left py-2 px-2">{"\u8f6e\u6b21"}</th><th className="text-left py-2 px-2">{"\u79cd\u5b50"}</th>
            <th className="text-center py-2 px-2">{"\u8fbe\u6807"}</th>
            <th className="text-right py-2 px-2">{"\u5b9e\u9645\u52a0\u5de5"}</th>
            <th className="text-right py-2 px-2">{"\u7f3a\u53e3"}</th>
            <th className="text-center py-2 px-2">{"Benders"}</th>
            <th className="text-center py-2 px-2">{"\u6279\u6b21"}</th>
            <th className="text-center py-2 px-2">{"\u4e3b\u7f50\u6362\u6cb9"}</th><th className="text-center py-2 px-2">{"\u5378\u6cb9T\u7f50"}</th>
            <th className="text-center py-2 px-2">{"\u4f18\u9009"}</th><th className="text-center py-2 px-2">{"\u9009\u4e2d"}</th>
            <th className="text-center py-2 px-2">{"\u67e5\u770b"}</th>
          </tr></thead>
          <tbody>
            {rounds.map((r, i) => {
              const sc = r.score;
              const isSelected = selected_idx.includes(i);
              const c = compList[i];
              const rGap = r.total_gap ?? sc.total_gap ?? 0;
              const rIters = r.benders_iters ?? 0;
              const rConv = r.benders_converged ?? false;
              return <tr key={i} className={`border-b border-slate-800 ${round === i ? "bg-blue-950/40" : ""} ${isSelected ? "bg-blue-950/20" : ""}`}>
                <td className="py-1.5 px-2 font-mono">{r.round}</td>
                <td className="py-1.5 px-2 text-slate-500 font-mono">{r.seed}</td>
                <td className="py-1.5 px-2 text-center"><span className={sc.compliant_crudes === (c?.n_judged || data.compliance.n_judged) ? "text-green-400" : "text-red-400"}>{c ? `${c.compliant}/${c.n_judged}` : `${sc.compliant_crudes}/${data.compliance.n_judged}`}</span></td>
                <td className="py-1.5 px-2 text-right font-mono">{(sc.real_total || 0).toLocaleString()}</td>
                <td className={`py-1.5 px-2 text-right font-mono ${rGap > 1 ? "text-red-400" : "text-green-400"}`}>{rGap > 1 ? rGap.toLocaleString() : "0"}</td>
                <td className="py-1.5 px-2 text-center"><span className={rConv ? "text-green-400" : "text-amber-400"}>{rConv ? "\u2713" : `${rIters}\u8f6e`}</span></td>
                <td className="py-1.5 px-2 text-center">{r.n_batches}</td>
                <td className="py-1.5 px-2 text-center">{sc.main_switches}</td>
                <td className="py-1.5 px-2 text-center">{sc.unload_to_t_count}</td>
                <td className="py-1.5 px-2 text-center">{sc.is_preferred ? "\u2605" : ""}</td>
                <td className="py-1.5 px-2 text-center text-blue-400">{isSelected ? "\u2713" : ""}</td>
                <td className="py-1.5 px-2 text-center">
                  <button onClick={() => setRound(i)} className={`px-2 py-0.5 rounded text-[10px] ${round === i ? "bg-blue-600 text-white" : "bg-slate-700 text-slate-300 hover:bg-slate-600"}`}>
                    {"\u8fbe\u6807\u660e\u7ec6"}
                  </button>
                </td>
              </tr>;
            })}
          </tbody>
        </table>
      </div>

      {/* 达标明细 — 按选中轮次 */}
      <div className="panel p-4">
        <div className="flex items-center gap-3 mb-3">
          <h2 className="text-sm font-semibold text-slate-300">
            {"\u8fbe\u6807\u6838\u5bf9 \u2014 \u8f6e\u6b21"} {rounds[round]?.round || round + 1}
          </h2>
          {selected_idx.includes(round) && <span className="text-xs text-blue-400 bg-blue-950/40 px-2 py-0.5 rounded">{"\u9009\u4e2d\u8f93\u51fa"}</span>}
          {rounds[round]?.score.is_preferred && <span className="text-xs text-yellow-400 bg-yellow-950/40 px-2 py-0.5 rounded">{"\u4f18\u9009\u89e3 \u2605"}</span>}
        </div>

        {/* 总量卡片 */}
        <div className="grid grid-cols-3 gap-4 mb-4">
          <div className="border border-slate-700 rounded p-3 text-center">
            <div className="text-xs text-slate-400">{"\u8ba1\u5212\u603b\u91cf"}</div>
            <div className="text-xl font-bold text-white">{fmt(comp.plan_total)}t</div>
            <div className="text-xs text-slate-500">{"\u76ee\u6807"} {fmt(comp.proc_total)}t</div>
          </div>
          <div className="border border-slate-700 rounded p-3 text-center">
            <div className="text-xs text-slate-400">{"\u5b9e\u9645\u603b\u91cf"}</div>
            <div className={`text-xl font-bold ${comp.real_total >= comp.proc_total - 1 ? "text-green-400" : "text-red-400"}`}>{fmt(comp.real_total)}t</div>
            <div className="text-xs text-slate-500">{comp.real_total >= comp.proc_total - 1 ? "\u8fbe\u6807" : `\u7f3a\u53e3 ${fmt(comp.proc_total - comp.real_total)}t`}</div>
          </div>
          <div className="border border-slate-700 rounded p-3 text-center">
            <div className="text-xs text-slate-400">{"\u8fbe\u6807\u6cb9\u79cd"}</div>
            <div className={`text-xl font-bold ${comp.compliant === comp.n_judged ? "text-green-400" : "text-red-400"}`}>{comp.compliant}/{comp.n_judged}</div>
          </div>
        </div>

        {/* 各油种达标明细 */}
        <table className="w-full text-xs">
          <thead><tr className="text-slate-400 border-b border-slate-700">
            <th className="text-left py-2 px-2">{"\u6cb9\u79cd"}</th><th className="text-left py-2 px-2">{"\u53e3\u5f84"}</th>
            <th className="text-right py-2 px-2">{"\u8ba1\u5212\u91cf"}</th><th className="text-right py-2 px-2">{"\u5b9e\u73b0\u91cf"}</th>
            <th className="text-right py-2 px-2">{"\u8981\u6c42"}</th>
            <th className="text-center py-2 px-2">{"\u8ba1\u5212\u8fbe\u6807"}</th><th className="text-center py-2 px-2">{"\u5b9e\u73b0\u8fbe\u6807"}</th>
            <th className="text-right py-2 px-2">{"\u7f3a\u53e3"}</th>
          </tr></thead>
          <tbody>
            {comp.details.map((d) => {
              const shortfall = Math.max(0, d.req - d.real);
              return <tr key={d.crude} className="border-b border-slate-800">
                <td className="py-1.5 px-2"><span className="inline-flex items-center gap-1"><span className="w-2 h-2 rounded-full" style={{ background: crudeColor(d.crude) }} />{d.crude}</span></td>
                <td className="py-1.5 px-2 text-slate-400">{d.kind}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmt(d.plan)}</td>
                <td className={`py-1.5 px-2 text-right font-mono ${d.real >= d.req - 1 ? "text-green-400" : "text-red-400"}`}>{fmt(d.real)}</td>
                <td className="py-1.5 px-2 text-right font-mono text-slate-400">{fmt(d.req)}</td>
                <td className="py-1.5 px-2 text-center">{d.plan >= d.req - 1 ? <span className="text-green-400">{"\u2713"}</span> : <span className="text-red-400">{"\u2717"}</span>}</td>
                <td className="py-1.5 px-2 text-center">{d.ok ? <span className="text-green-400">{"\u2713"}</span> : <span className="text-red-400">{"\u2717"}</span>}</td>
                <td className={`py-1.5 px-2 text-right font-mono ${shortfall > 1 ? "text-red-400" : "text-slate-600"}`}>{shortfall > 1 ? fmt(shortfall) : "\u2014"}</td>
              </tr>;
            })}
            <tr className="border-b border-slate-800 font-semibold">
              <td className="py-1.5 px-2" colSpan={2}>{"\u5408\u8ba1"}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmt(comp.plan_total)}</td>
              <td className={`py-1.5 px-2 text-right font-mono ${comp.real_total >= comp.proc_total - 1 ? "text-green-400" : "text-red-400"}`}>{fmt(comp.real_total)}</td>
              <td className="py-1.5 px-2 text-right font-mono text-slate-400">{fmt(comp.proc_total)}</td>
              <td colSpan={2}></td>
              <td className={`py-1.5 px-2 text-right font-mono ${comp.proc_total - comp.real_total > 1 ? "text-red-400" : "text-slate-600"}`}>{comp.proc_total - comp.real_total > 1 ? fmt(comp.proc_total - comp.real_total) : "\u2014"}</td>
            </tr>
          </tbody>
        </table>
      </div>

      {/* 每日缺口明细 — 按选中轮次 */}
      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">
          {"\u6bcf\u65e5\u7f3a\u53e3\u660e\u7ec6 \u2014 \u8f6e\u6b21"} {rounds[round]?.round || round + 1}
        </h2>
        <table className="w-full text-xs">
          <thead><tr className="text-slate-400 border-b border-slate-700">
            <th className="text-left py-2 px-2">{"\u5929"}</th><th className="text-right py-2 px-2">{"\u8ba1\u5212h"}</th>
            <th className="text-right py-2 px-2">{"\u5b9e\u9645h"}</th><th className="text-right py-2 px-2">{"\u8ba1\u5212\u91cf"}</th>
            <th className="text-right py-2 px-2">{"\u5b9e\u9645\u91cf"}</th><th className="text-right py-2 px-2">{"\u7f3a\u53e3\u91cf"}</th>
            <th className="text-right py-2 px-2">{"\u7f3a\u53e3h"}</th><th className="text-left py-2 px-2">{"\u72b6\u6001"}</th>
          </tr></thead>
          <tbody>
            {data.tank_grids[round]?.daily_summary.map((d) => <tr key={d.day} className={`border-b border-slate-800 ${d.gap_proc > 1 ? "bg-red-950/20" : ""}`}>
              <td className="py-1.5 px-2">d{d.day}</td>
              <td className="py-1.5 px-2 text-right">{d.planned_h}h</td>
              <td className="py-1.5 px-2 text-right">{d.actual_time}h</td>
              <td className="py-1.5 px-2 text-right font-mono text-slate-400">{fmt(d.planned_proc)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmt(d.actual_proc)}</td>
              <td className={`py-1.5 px-2 text-right font-mono ${d.gap_proc > 1 ? "text-red-400" : "text-slate-600"}`}>{d.gap_proc > 1 ? fmt(d.gap_proc) : "0"}</td>
              <td className={`py-1.5 px-2 text-right ${d.gap_h > 0.1 ? "text-red-400" : "text-slate-600"}`}>{d.gap_h > 0.1 ? `${d.gap_h}h` : "\u2014"}</td>
              <td className="py-1.5 px-2">{d.planned_h === 0 ? <span className="text-slate-600">{"\u7a7a\u95f2"}</span> : d.gap_proc > 1 ? <span className="text-red-400">{"\u7f3a\u53e3"}</span> : <span className="text-green-400">OK</span>}</td>
            </tr>)}
          </tbody>
        </table>
      </div>
    </div>
  );
}
