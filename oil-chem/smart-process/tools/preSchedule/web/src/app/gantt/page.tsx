"use client";
import { useState } from "react";
import useApiData from "@/components/useApiData";
import NoDataHint from "@/components/NoDataHint";
import { fmtTon, crudeColor } from "@/lib/api";

export default function GanttPage() {
  const { data, loading, error, noData } = useApiData();
  const [round, setRound] = useState(0);

  if (loading) return <div className="text-center py-20 text-slate-400">{"\u52a0\u8f7d\u4e2d..."}</div>;
  if (error) return <div className="text-red-400 p-4">{"\u9519\u8bef"}: {error}</div>;
  if (noData) return <NoDataHint />;
  if (!data) return null;

  const rd = data.rounds[round];
  if (!rd) return <div>{"\u65e0\u8f6e\u6b21\u6570\u636e"}</div>;
  const totalH = data.parameters.days * 24;
  const seq = rd.seq;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-sm text-slate-400">{"\u8f6e\u6b21"}:</span>
        {data.rounds.map((r, i) => (
          <button key={i} onClick={() => setRound(i)}
            className={`px-3 py-1 rounded text-xs transition ${round === i ? "bg-blue-600 text-white" : "bg-slate-800 text-slate-400 hover:bg-slate-700"}`}>
            {"\u8f6e"}{r.round} {r.score.is_preferred ? "\u2605" : ""}
          </button>
        ))}
        <span className="ml-auto text-xs text-slate-500">
          {rd.n_batches} {"\u6279"} | {"\u8fbe\u6807"} {rd.score.compliant_crudes}/{data.compliance.n_judged} | seed={rd.seed}
        </span>
      </div>

      <div className="panel p-4 overflow-x-auto">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">{"CDU \u52a0\u5de5\u5e8f\u5217\u7518\u7279\u56fe"}</h2>
        <div className="min-w-[1000px]">
          <div className="relative h-5 mb-1 border-b border-slate-700">
            {Array.from({ length: data.parameters.days + 1 }, (_, d) => (
              <div key={d} className="absolute top-0 bottom-0 border-l border-slate-700 text-[10px] text-slate-500 pl-1" style={{ left: `${(d * 24 / totalH) * 100}%` }}>{d > 0 ? `d${d}` : ""}</div>
            ))}
          </div>
          <div className="relative h-10 bg-slate-800/50 rounded">
            {seq.map((s, i) => {
              const leftPct = (s.start_h / totalH) * 100;
              const widthPct = (s.dur_h / totalH) * 100;
              const mainCrude = s.comps[0]?.crude || "";
              return <div key={i} className="absolute top-1 bottom-1 rounded flex items-center justify-center text-[10px] text-white overflow-hidden cursor-pointer hover:ring-2 hover:ring-white/50"
                style={{ left: `${leftPct}%`, width: `${Math.max(widthPct, 0.3)}%`, background: crudeColor(mainCrude) }}
                title={`${s.comp_str} | 第${(s.start_h / 24).toFixed(1)}天~第${(s.end_h / 24).toFixed(1)}天 (${s.start_h}h~${s.end_h}h) | ${s.dur_h}h | ${fmtTon(s.tons)}`}>
                <span className="truncate px-1">{s.comps[0]?.crude}{s.is_blend && `+${s.comps.slice(1).map((c) => c.crude).join("+")}`}</span>
              </div>;
            })}
          </div>
          <div className="relative h-5 mt-1">
            {data.parameters.arrivals.map((a, i) => {
              if (!a.berth_day) return null;
              const h = (a.berth_day - 1) * 24;
              return <div key={i} className="absolute top-0 bottom-0 w-0.5 bg-yellow-500" style={{ left: `${(h / totalH) * 100}%` }} title={`${a.crude} \u5230\u6e2f ${fmtTon(a.ton)} \u7b2c${a.berth_day}\u5929`}>
                <div className="absolute -top-0 -left-1 w-2 h-2 bg-yellow-500 rounded-full" />
              </div>;
            })}
          </div>
        </div>
        <div className="flex flex-wrap gap-3 mt-3 text-xs text-slate-400">
          {Array.from(new Set(seq.flatMap((s) => s.comps.map((c) => c.crude)))).map((c) => (
            <span key={c} className="flex items-center gap-1"><span className="w-3 h-3 rounded" style={{ background: crudeColor(c) }} />{c}</span>
          ))}
          <span className="flex items-center gap-1"><span className="w-0.5 h-3 bg-yellow-500 inline-block" />{"\u5230\u6e2f"}</span>
        </div>
      </div>

      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">{"\u6279\u6b21\u8be6\u60c5"}</h2>
        <table className="w-full text-xs">
          <thead><tr className="text-slate-400 border-b border-slate-700">
            <th className="text-left py-2 px-2">#</th><th className="text-left py-2 px-2">{"\u8d77\u6b62"}</th>
            <th className="text-right py-2 px-2">{"\u65f6\u957f"}</th><th className="text-left py-2 px-2">{"\u7ec4\u5206(\u8d1f\u8377)"}</th>
            <th className="text-right py-2 px-2">{"\u603b\u8d1f\u8377"}</th><th className="text-right py-2 px-2">{"\u52a0\u5de5\u91cf"}</th>
            <th className="text-left py-2 px-2">{"\u7c7b\u578b"}</th>
          </tr></thead>
          <tbody>
            {seq.map((s, i) => <tr key={i} className="border-b border-slate-800 hover:bg-slate-800/50">
              <td className="py-1.5 px-2 text-slate-500">{i + 1}</td>
              <td className="py-1.5 px-2 font-mono text-slate-400">第{(s.start_h / 24).toFixed(1)}天 ~ 第{(s.end_h / 24).toFixed(1)}天 <span className="text-slate-600">({s.start_h}h~{s.end_h}h)</span></td>
              <td className="py-1.5 px-2 text-right">{s.dur_h}h</td>
              <td className="py-1.5 px-2">{s.comps.map((c, j) => <span key={j} className="mr-2"><span className="inline-block w-2 h-2 rounded-full mr-1" style={{ background: crudeColor(c.crude) }} />{c.crude}({c.load_hr})</span>)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{s.total_load}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmtTon(s.tons)}</td>
              <td className="py-1.5 px-2 text-slate-500">{s.is_blend ? "\u63ba\u70bc" : "\u5355\u70bc"} / {s.si > 0 ? "\u5230\u6e2f" : "\u521d\u59cb"}</td>
            </tr>)}
          </tbody>
        </table>
      </div>
    </div>
  );
}
