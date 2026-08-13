"use client";
import useApiData from "@/components/useApiData";
import NoDataHint from "@/components/NoDataHint";
import { fmt, crudeColor } from "@/lib/api";

export default function Phase0Page() {
  const { data, loading, error, noData } = useApiData();
  if (loading) return <div className="text-center py-20 text-slate-400">{"\u52a0\u8f7d\u4e2d..."}</div>;
  if (error) return <div className="text-red-400 p-4">{"\u9519\u8bef"}: {error}</div>;
  if (noData) return <NoDataHint />;
  if (!data) return null;
  const ph = data.phase0;
  const p = data.parameters;

  return (
    <div className="space-y-4">
      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">
          {"\u6765\u8239\u5206\u914d"} ({Object.keys(ph.ship_assignments).length} {"\u8258"})
        </h2>
        <table className="w-full text-xs">
          <thead><tr className="text-slate-400 border-b border-slate-700">
            <th className="text-left py-2 px-2">{"\u8239\u5e8f"}</th><th className="text-left py-2 px-2">{"\u6cb9\u79cd"}</th>
            <th className="text-right py-2 px-2">{"\u6570\u91cf"}</th><th className="text-right py-2 px-2">{"\u9760\u6cca\u65e5"}</th>
            <th className="text-left py-2 px-2">{"\u5206\u914d\u7f50"}</th><th className="text-left py-2 px-2">{"\u7c7b\u578b"}</th>
            <th className="text-left py-2 px-2">{"\u5907\u6ce8"}</th>
          </tr></thead>
          <tbody>
            {Object.entries(ph.ship_assignments).map(([idx, a]) => {
              const arr = p.arrivals[parseInt(idx)];
              return <tr key={idx} className="border-b border-slate-800">
                <td className="py-1.5 px-2 text-slate-500">{parseInt(idx) + 1}</td>
                <td className="py-1.5 px-2"><span className="inline-flex items-center gap-1"><span className="w-2 h-2 rounded-full" style={{ background: crudeColor(arr?.crude || "") }} />{arr?.crude || "\u2014"}</span></td>
                <td className="py-1.5 px-2 text-right font-mono">{fmt(arr?.ton)}</td>
                <td className="py-1.5 px-2 text-right">{arr?.berth_day ? `\u7b2c${arr.berth_day}\u5929` : "\u2014"}</td>
                <td className="py-1.5 px-2 font-mono">{a.tank || "\u65e0"}</td>
                <td className="py-1.5 px-2"><span className={a.is_g ? "text-blue-400" : "text-slate-400"}>{a.is_g ? "G\u4f9b\u6599\u7f50" : "T\u50a8\u7f50"}</span></td>
                <td className="py-1.5 px-2 text-slate-500">{a.switch ? `\u6362\u6cb9(\u65e7:${a.old_crude})` : ""}</td>
              </tr>;
            })}
          </tbody>
        </table>
      </div>

      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">
          {"\u817e\u5bb9\u7ea6\u675f"} ({ph.cp_constraints.length} {"\u6761"})
        </h2>
        <table className="w-full text-xs">
          <thead><tr className="text-slate-400 border-b border-slate-700">
            <th className="text-left py-2 px-2">#</th><th className="text-left py-2 px-2">{"\u6cb9\u79cd"}</th>
            <th className="text-right py-2 px-2">{"\u9700\u6c42\u91cf t"}</th>
            <th className="text-right py-2 px-2">deadline h</th>
            <th className="text-right py-2 px-2">deadline {"\u5929"}</th>
          </tr></thead>
          <tbody>
            {ph.cp_constraints.map((c, i) => <tr key={i} className="border-b border-slate-800">
              <td className="py-1.5 px-2 text-slate-500">{i + 1}</td>
              <td className="py-1.5 px-2"><span className="inline-flex items-center gap-1"><span className="w-2 h-2 rounded-full" style={{ background: crudeColor(c.crude) }} />{c.crude}</span></td>
              <td className="py-1.5 px-2 text-right font-mono">{fmt(c.need_tons)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{c.deadline_hour}</td>
              <td className="py-1.5 px-2 text-right text-slate-400">{(c.deadline_hour / 24).toFixed(2)}d</td>
            </tr>)}
          </tbody>
        </table>
      </div>

      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">
          {"\u5378\u6cb9\u505c\u4ea7\u7a97\u53e3"} ({ph.no_process_windows.length} {"\u6761"})
        </h2>
        <div className="space-y-2">
          {ph.no_process_windows.map((w, i) => {
            const dur = w.end_h - w.start_h;
            const leftPct = (w.start_h / (p.days * 24)) * 100;
            const widthPct = (dur / (p.days * 24)) * 100;
            return <div key={i} className="flex items-center gap-2 text-xs">
              <span className="w-16 text-slate-400"><span className="inline-flex items-center gap-1"><span className="w-2 h-2 rounded-full" style={{ background: crudeColor(w.crude) }} />{w.crude}</span></span>
              <div className="flex-1 relative h-6 bg-slate-800 rounded">
                <div className="absolute h-full bg-amber-600/60 rounded text-amber-200 flex items-center px-1 text-[10px]" style={{ left: `${leftPct}%`, width: `${Math.max(widthPct, 0.5)}%` }}>{dur}h</div>
              </div>
              <span className="w-24 text-right text-slate-500 font-mono">{w.start_h}h~{w.end_h}h</span>
            </div>;
          })}
        </div>
      </div>
    </div>
  );
}
