"use client";
import { useState, useEffect, useRef } from "react";
import useApiData from "@/components/useApiData";
import NoDataHint from "@/components/NoDataHint";
import { fmt, fmtTon, crudeColor } from "@/lib/api";
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, ReferenceLine, Cell,
} from "recharts";

export default function OverviewPage() {
  const { data, loading, error, noData } = useApiData();
  const [selRound, setSelRound] = useState(-1);
  const [logs, setLogs] = useState<string[]>([]);
  const [showLogs, setShowLogs] = useState(false);
  const logEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetch("/api/status", { cache: "no-store" })
      .then((r) => r.json())
      .then((d) => {
        if (d.logs && d.logs.length > 0) setLogs(d.logs);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (showLogs && logEndRef.current) {
      logEndRef.current.scrollTop = logEndRef.current.scrollHeight;
    }
  }, [logs, showLogs]);

  if (loading)
    return <div className="text-center py-20 text-slate-400">{"\u52a0\u8f7d\u4e2d... (\u9996\u6b21\u9700\u8fd0\u884c CP-SAT\uff0c\u7ea6 4-5 \u5206\u949f)"}</div>;
  if (error) return <div className="text-red-400 p-4">{"\u9519\u8bef"}: {error}</div>;
  if (noData) return <NoDataHint />;
  if (!data) return null;

  const { parameters: p, rounds, tank_grids, compliance: comp, selected_idx } = data;

  if (!rounds || rounds.length === 0) {
    return (
      <div className="space-y-4">
        <div className="panel p-6 text-center">
          <div className="text-amber-400 text-lg mb-2">{"\u26a0 \u672c\u6b21\u8fd0\u884c\u65e0\u53ef\u884c\u89e3"}</div>
          <div className="text-slate-400 text-sm">
            {"\u6240\u6709\u6863\u4f4d\u5747\u4e0d\u53ef\u884c\uff08INFEASIBLE\uff09\uff0c\u53ef\u80fd\u539f\u56e0\uff1a"}
            <br />
            {"\u2022 \u5230\u6e2f\u8239\u671f\u8fc7\u65e9\uff0c\u817e\u5bb9\u7ea6\u675f\u65e0\u6cd5\u6ee1\u8db3"}
            <br />
            {"\u2022 \u52a0\u5de5\u8ba1\u5212\u91cf\u8d85\u51fa CDU \u6708\u5ea6\u4ea7\u80fd"}
            <br />
            {"\u2022 \u6cb9\u79cd\u53ef\u7528\u91cf\u4e0d\u8db3\u8ba1\u5212\u91cf"}
            <br />
            <br />
            {"\u8bf7\u68c0\u67e5\u6a21\u677f\u6570\u636e\u6216\u67e5\u770b\u8fd0\u884c\u65e5\u5fd7\u4e86\u89e3\u8be6\u60c5"}
          </div>
        </div>
        {logs.length > 0 && (
          <div className="panel p-4">
            <div className="flex items-center justify-between mb-2">
              <h2 className="text-sm font-semibold text-slate-300">{"\u8fd0\u884c\u65e5\u5fd7"} ({logs.length} {"\u884c"})</h2>
              <button
                onClick={() => setShowLogs(!showLogs)}
                className="px-2 py-1 rounded text-xs bg-slate-700 text-slate-300 hover:bg-slate-600"
              >
                {showLogs ? "\u6536\u8d77" : "\u5c55\u5f00"}
              </button>
            </div>
            {showLogs && (
              <div ref={logEndRef} className="max-h-96 overflow-y-auto font-mono text-xs leading-relaxed bg-slate-950 rounded p-2">
                {logs.map((line, i) => (
                  <div key={i} className={
                    line.includes("INFEASIBLE") || line.includes("Failed") ? "text-red-400" :
                    line.includes("\u6863") || line.includes("\u9636\u68af") ? "text-amber-400" :
                    "text-slate-400"
                  }>
                    {line}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    );
  }

  const bestIdx = selected_idx[0] ?? 0;
  const roundIdx = selRound < 0 ? bestIdx : Math.min(selRound, rounds.length - 1);
  const tg = tank_grids[roundIdx];
  const rd = rounds[roundIdx];
  const sc = rd?.score;

  const dailyData = (tg?.daily_summary || []).map((d) => ({
    day: `d${d.day}`,
    actual: d.actual_proc,
    planned: d.planned_proc,
    gap: d.gap_proc,
    gapCrudes: d.gap_crudes || [],
  }));

  const bestGap = rd?.total_gap ?? 0;
  const bendersConverged = rd?.benders_converged ?? false;
  const bendersIters = rd?.benders_iters ?? 0;

  // 指标卡跟随轮次切换
  const cards = [
    { label: "\u6392\u4ea7\u5e74\u6708", value: `${p.year}-${String(p.month).padStart(2, "0")}`, sub: `${p.days} \u5929` },
    { label: "\u8fbe\u6807", value: `${sc?.compliant_crudes ?? 0}/${comp.n_judged}`, sub: (sc?.compliant_crudes ?? 0) === comp.n_judged ? "\u5168\u8fbe\u6807" : "\u6709\u7f3a\u53e3", ok: (sc?.compliant_crudes ?? 0) === comp.n_judged },
    { label: "\u6279\u6b21\u6570", value: rd?.n_batches ?? "\u2014", sub: `\u4e0a\u9650 ${data.batch_estimate.cap}` },
    { label: "Benders", value: bendersConverged ? "\u6536\u655b" : `${bendersIters}\u8f6e`, sub: bendersConverged ? "\u65e0\u7f3a\u53e3" : `\u7f3a\u53e3${bestGap}t`, ok: bendersConverged },
    { label: "\u5b9e\u9645\u52a0\u5de5", value: sc?.real_total != null ? sc.real_total.toLocaleString() : "\u2014", sub: "t" },
    { label: "\u544a\u8b66", value: tg?.warnings.length ?? 0, sub: tg?.warnings.length ? "\u9700\u5173\u6ce8" : "\u65e0", ok: !tg?.warnings.length },
  ];

  return (
    <div className="space-y-4">
      {/* 下载按钮 */}
      {data.xlsx_files && data.xlsx_files.length > 0 && (
        <div className="flex items-center gap-3">
          <span className="text-sm text-slate-400">{"\u7ed3\u679c\u6587\u4ef6\uff1a"}</span>
          {data.xlsx_files.map((f: string, i: number) => (
            <a
              key={f}
              href={`/api/download/${i + 1}`}
              className="px-3 py-1.5 rounded text-xs bg-emerald-700 text-white hover:bg-emerald-600 transition flex items-center gap-1"
            >
              {"\u2193"} {f}
            </a>
          ))}
        </div>
      )}

      {/* 轮次切换 */}
      <div className="flex items-center gap-1 flex-wrap">
        <span className="text-xs text-slate-500 mr-1">{"\u8f6e\u6b21:"}</span>
        {rounds.map((r, i) => {
          const isBest = i === bestIdx;
          const isSel = i === roundIdx;
          return (
            <button
              key={i}
              onClick={() => setSelRound(i)}
              className={`px-2.5 py-1 rounded text-xs transition ${
                isSel
                  ? "bg-blue-600 text-white"
                  : isBest
                    ? "bg-green-900/50 text-green-400 hover:bg-green-900/70"
                    : "bg-slate-800 text-slate-400 hover:bg-slate-700"
              }`}
            >
              R{r.round}
              {isBest ? " \u2605" : ""}
              {r.benders_converged ? "" : " \u26a0"}
            </button>
          );
        })}
      </div>

      {/* 指标卡 — 跟随轮次 */}
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
        {cards.map((c) => (
          <div key={c.label} className="panel p-3">
            <div className="text-xs text-slate-400">{c.label}</div>
            <div className={`text-2xl font-bold mt-1 ${c.ok === true ? "text-green-400" : c.ok === false ? "text-red-400" : "text-white"}`}>
              {c.value}
            </div>
            <div className="text-xs text-slate-500 mt-0.5">{c.sub}</div>
          </div>
        ))}
      </div>

      {/* 每日CDU加工量 */}
      <div className="panel p-4">
        <div className="flex items-center justify-between mb-2">
          <h2 className="text-sm font-semibold text-slate-300">
            {"\u6bcf\u65e5 CDU \u52a0\u5de5\u91cf (\u8ba1\u5212 vs \u5b9e\u9645)"}
            {roundIdx === bestIdx ? <span className="ml-2 text-xs text-green-400">{"\u2605 \u6700\u4f18\u89e3"}</span> : <span className="ml-2 text-xs text-slate-500">{`\u8f6e\u6b21 ${rd?.round}`}</span>}
          </h2>
          <div className="flex gap-3 text-xs text-slate-500">
            <span>{"\u6279\u6b21"} {rd?.n_batches}</span>
            <span>{"\u7f3a\u53e3"} {bestGap > 1 ? <span className="text-red-400">{bestGap.toFixed(0)}t</span> : <span className="text-green-400">0t</span>}</span>
            <span>{"Benders"} {bendersConverged ? <span className="text-green-400">{"\u6536\u655b"}</span> : `${bendersIters}\u8f6e`}</span>
          </div>
        </div>
        <ResponsiveContainer width="100%" height={280}>
          <BarChart data={dailyData}>
            <XAxis dataKey="day" tick={{ fontSize: 10, fill: "#94a3b8" }} interval={1} />
            <YAxis tick={{ fontSize: 10, fill: "#94a3b8" }} />
            <Tooltip
              contentStyle={{ background: "#1e293b", border: "1px solid #334155", fontSize: 12 }}
              formatter={(v: number, name: string) => fmtTon(v)}
              labelFormatter={(label: string) => {
                const item = dailyData.find((d) => d.day === label);
                if (item && item.gapCrudes.length > 0) {
                  const lines = item.gapCrudes.map((g) => `${g.crude}: \u7f3a${g.gap.toLocaleString()}t`);
                  return [label, lines.join("  |  ")];
                }
                return [label, ""];
              }}
            />
            <Bar dataKey="planned" fill="#334155" name={"\u8ba1\u5212"} radius={[2, 2, 0, 0]} />
            <Bar dataKey="actual" name={"\u5b9e\u9645"} radius={[2, 2, 0, 0]}>
              {dailyData.map((d, i) => (
                <Cell key={i} fill={d.gap > 1 ? "#ef4444" : "#3b82f6"} />
              ))}
            </Bar>
            <ReferenceLine y={715 * 24} stroke="#64748b" strokeDasharray="3 3" label={{ value: "\u6ee1\u8d1f\u8377 17160t", fontSize: 10, fill: "#64748b" }} />
          </BarChart>
        </ResponsiveContainer>
        <div className="flex gap-4 text-xs text-slate-500 mt-2">
          <span className="flex items-center gap-1"><span className="w-3 h-3 bg-blue-500 inline-block rounded-sm" /> {"\u5b9e\u9645\u8fbe\u6807"}</span>
          <span className="flex items-center gap-1"><span className="w-3 h-3 bg-red-500 inline-block rounded-sm" /> {"\u5b9e\u9645\u6709\u7f3a\u53e3"}</span>
          <span className="flex items-center gap-1"><span className="w-3 h-3 bg-slate-700 inline-block rounded-sm" /> {"\u8ba1\u5212"}</span>
        </div>
      </div>

      {/* 加工序列表 */}
      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">
          {"\u52a0\u5de5\u5e8f\u5217"}
          {roundIdx === bestIdx ? <span className="ml-2 text-xs text-green-400">{"\u2605 \u6700\u4f18\u89e3"}</span> : <span className="ml-2 text-xs text-slate-500">{`\u8f6e\u6b21 ${rd?.round}`}</span>}
        </h2>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-slate-400 border-b border-slate-700">
                <th className="text-left py-2 px-2">#</th>
                <th className="text-left py-2 px-2">{"\u8d77\u59cb\u5929"}</th>
                <th className="text-left py-2 px-2">{"\u65f6\u957fh"}</th>
                <th className="text-left py-2 px-2">{"\u914d\u65b9"}</th>
                <th className="text-right py-2 px-2">{"\u603b\u8d1f\u8377"}</th>
                <th className="text-right py-2 px-2">{"\u52a0\u5de5\u91cf"}</th>
                <th className="text-left py-2 px-2">{"\u6765\u6e90"}</th>
              </tr>
            </thead>
            <tbody>
              {rd?.seq.map((s, i) => (
                <tr key={i} className="border-b border-slate-800 hover:bg-slate-800/50">
                  <td className="py-1.5 px-2 text-slate-500">{i + 1}</td>
                  <td className="py-1.5 px-2">{(s.start_h / 24).toFixed(2)}d</td>
                  <td className="py-1.5 px-2">{s.dur_h}h</td>
                  <td className="py-1.5 px-2">
                    <span className="inline-flex items-center gap-1">
                      <span className="w-2 h-2 rounded-full" style={{ background: crudeColor(s.comps[0]?.crude) }} />
                      {s.comp_str}
                    </span>
                  </td>
                  <td className="py-1.5 px-2 text-right text-slate-400">{s.total_load}t/h</td>
                  <td className="py-1.5 px-2 text-right">{fmtTon(s.tons)}</td>
                  <td className="py-1.5 px-2 text-slate-500">{s.si > 0 ? "\u5230\u6e2f\u7247" : "\u521d\u59cb\u7247"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* 告警 */}
      {tg && tg.warnings.length > 0 && (
        <div className="panel p-4">
          <h2 className="text-sm font-semibold text-slate-300 mb-3">
            {"\u544a\u8b66"} ({tg.warnings.length})
            {roundIdx !== bestIdx && <span className="ml-2 text-xs text-slate-500">{`\u8f6e\u6b21 ${rd?.round}`}</span>}
          </h2>
          <div className="space-y-1 max-h-60 overflow-y-auto">
            {tg.warnings.map((w, i) => (
              <div key={i} className="text-xs text-amber-400 bg-amber-950/30 border border-amber-900/50 rounded px-2 py-1">
                {w}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 运行日志 */}
      {logs.length > 0 && (
        <div className="panel p-4">
          <div className="flex items-center justify-between mb-2">
            <h2 className="text-sm font-semibold text-slate-300">{"\u8fd0\u884c\u65e5\u5fd7"} ({logs.length} {"\u884c"})</h2>
            <button
              onClick={() => setShowLogs(!showLogs)}
              className="px-2 py-1 rounded text-xs bg-slate-700 text-slate-300 hover:bg-slate-600"
            >
              {showLogs ? "\u6536\u8d77" : "\u5c55\u5f00"}
            </button>
          </div>
          {showLogs && (
            <div
              ref={logEndRef}
              className="max-h-96 overflow-y-auto font-mono text-xs leading-relaxed bg-slate-950 rounded p-2"
            >
              {logs.map((line, i) => (
                <div key={i} className={
                  line.includes("Benders") ? "text-blue-400" :
                  line.includes("\u6863") || line.includes("\u9636\u68af") ? "text-amber-400" :
                  line.includes("converged") || line.includes("\u6536\u655b") || line.includes("OK") ? "text-green-400" :
                  line.includes("Failed") || line.includes("INFEASIBLE") || line.includes("error") ? "text-red-400" :
                  line.includes("Result") ? "text-cyan-400" :
                  "text-slate-400"
                }>
                  {line}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
