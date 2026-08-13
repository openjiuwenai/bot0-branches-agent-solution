"use client";
import useApiData from "@/components/useApiData";
import NoDataHint from "@/components/NoDataHint";
import { fmt, fmtTon, crudeColor } from "@/lib/api";

export default function ParametersPage() {
  const { data, loading, error, noData } = useApiData();
  if (loading) return <div className="text-center py-20 text-slate-400">{"\u52a0\u8f7d\u4e2d..."}</div>;
  if (error) return <div className="text-red-400 p-4">{"\u9519\u8bef"}: {error}</div>;
  if (noData) return <NoDataHint />;
  if (!data) return null;
  const p = data.parameters;

  return (
    <div className="space-y-4">
      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">{"\u52a0\u5de5\u8ba1\u5212 (PROC)"}</h2>
        <table className="w-full text-xs">
          <thead>
            <tr className="text-slate-400 border-b border-slate-700">
              <th className="text-left py-2 px-2">{"\u6cb9\u79cd"}</th>
              <th className="text-left py-2 px-2">{"\u4ea7\u5730"}</th>
              <th className="text-left py-2 px-2">{"\u53ef\u5355\u70bc"}</th>
              <th className="text-right py-2 px-2">{"\u6807\u51c6\u8d1f\u8377 t/h"}</th>
              <th className="text-right py-2 px-2">{"\u8ba1\u5212\u91cf t"}</th>
            </tr>
          </thead>
          <tbody>
            {Object.entries(p.proc).map(([c, v]) => (
              <tr key={c} className="border-b border-slate-800">
                <td className="py-1.5 px-2"><span className="inline-flex items-center gap-1"><span className="w-2 h-2 rounded-full" style={{ background: crudeColor(c) }} />{c}</span></td>
                <td className="py-1.5 px-2 text-slate-400">{p.origin[c] === "imp" ? "\u8fdb\u53e3" : "\u56fd\u5185"}</td>
                <td className="py-1.5 px-2 text-slate-400">{p.can_single[c] ? "\u662f" : "\u5426"}</td>
                <td className="py-1.5 px-2 text-right text-slate-400">{p.rate_hr[c] || "\u2014"}</td>
                <td className="py-1.5 px-2 text-right font-mono">{fmt(v)}</td>
              </tr>
            ))}
            <tr className="border-b border-slate-800 font-semibold">
              <td className="py-1.5 px-2" colSpan={4}>{"\u5c0f\u8ba1 (PROC_TOTAL)"}</td>
              <td className="py-1.5 px-2 text-right font-mono">{fmt(p.proc_total)}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">{"\u5230\u6e2f\u8ba1\u5212 (ARRIVALS)"}</h2>
        <table className="w-full text-xs">
          <thead>
            <tr className="text-slate-400 border-b border-slate-700">
              <th className="text-left py-2 px-2">#</th>
              <th className="text-left py-2 px-2">{"\u6cb9\u79cd"}</th>
              <th className="text-right py-2 px-2">{"\u6570\u91cf t"}</th>
              <th className="text-right py-2 px-2">{"\u9760\u6cca\u65e5"}</th>
            </tr>
          </thead>
          <tbody>
            {p.arrivals.map((a, i) => (
              <tr key={i} className="border-b border-slate-800">
                <td className="py-1.5 px-2 text-slate-500">{i + 1}</td>
                <td className="py-1.5 px-2"><span className="inline-flex items-center gap-1"><span className="w-2 h-2 rounded-full" style={{ background: crudeColor(a.crude) }} />{a.crude}</span></td>
                <td className="py-1.5 px-2 text-right font-mono">{fmt(a.ton)}</td>
                <td className="py-1.5 px-2 text-right">{a.berth_day ? `\u7b2c${a.berth_day}\u5929` : "\u2014"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">{"\u7f50\u53c2\u6570 (TANKS)"}</h2>
        <table className="w-full text-xs">
          <thead>
            <tr className="text-slate-400 border-b border-slate-700">
              <th className="text-left py-2 px-2">{"\u7f50\u53f7"}</th>
              <th className="text-left py-2 px-2">{"\u7c7b\u578b"}</th>
              <th className="text-right py-2 px-2">{"\u5bb9\u91cf"}</th>
              <th className="text-right py-2 px-2">{"\u5e95\u6cb9"}</th>
              <th className="text-right py-2 px-2">{"\u53ef\u7528\u5bb9\u91cf"}</th>
              <th className="text-left py-2 px-2">{"\u5141\u8bb8\u6cb9\u79cd"}</th>
              <th className="text-left py-2 px-2">{"\u5f53\u524d\u6cb9\u79cd"}</th>
              <th className="text-right py-2 px-2">{"\u5f53\u524d\u5b58\u91cf"}</th>
              <th className="text-left py-2 px-2">{"\u5206\u7ec4"}</th>
            </tr>
          </thead>
          <tbody>
            {[...p.gtanks, ...p.ttanks].map((t) => {
              const tk = p.tanks[t];
              return (
                <tr key={t} className="border-b border-slate-800">
                  <td className="py-1.5 px-2 font-mono">{t}</td>
                  <td className="py-1.5 px-2"><span className={tk.is_g ? "text-blue-400" : "text-slate-400"}>{tk.is_g ? "G\u4f9b\u6599" : "T\u50a8\u7f50"}</span></td>
                  <td className="py-1.5 px-2 text-right font-mono">{fmt(tk.cap)}</td>
                  <td className="py-1.5 px-2 text-right font-mono text-slate-500">{fmt(tk.heel)}</td>
                  <td className="py-1.5 px-2 text-right font-mono">{fmt(tk.avail_cap)}</td>
                  <td className="py-1.5 px-2 text-slate-400">{tk.allow}</td>
                  <td className="py-1.5 px-2">{tk.crude ? <span className="inline-flex items-center gap-1"><span className="w-2 h-2 rounded-full" style={{ background: crudeColor(tk.crude) }} />{tk.crude}</span> : "\u2014"}</td>
                  <td className="py-1.5 px-2 text-right font-mono">{fmt(tk.ton)}</td>
                  <td className="py-1.5 px-2 text-slate-500">{p.main_tanks.includes(t) ? "\u4e3b\u529b" : p.blend_tanks.includes(t) ? "\u63ba\u70bc" : ""}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">{"\u63ba\u70bc\u914d\u65b9 (RECIPES)"}</h2>
        <table className="w-full text-xs">
          <thead>
            <tr className="text-slate-400 border-b border-slate-700">
              <th className="text-left py-2 px-2">{"\u914d\u65b9\u53f7"}</th>
              <th className="text-left py-2 px-2">{"\u4e3b\u529b\u6cb9\u79cd"}</th>
              <th className="text-right py-2 px-2">{"\u603b\u8d1f\u8377\u4e0a\u9650 t/h"}</th>
              <th className="text-left py-2 px-2">{"\u63ba\u70bc\u7ec4\u5206"}</th>
            </tr>
          </thead>
          <tbody>
            {Object.entries(p.recipes).map(([rid, rc]) => (
              <tr key={rid} className="border-b border-slate-800">
                <td className="py-1.5 px-2">{rid}</td>
                <td className="py-1.5 px-2"><span className="inline-flex items-center gap-1"><span className="w-2 h-2 rounded-full" style={{ background: crudeColor(rc.main) }} />{rc.main}</span></td>
                <td className="py-1.5 px-2 text-right font-mono">{rc.cap_hr}</td>
                <td className="py-1.5 px-2 text-slate-400">{rc.blends.length === 0 ? <span className="text-slate-600">—</span> : rc.blends.map((b, i) => <span key={i}>{b.crude} 负荷[{b.cands.join(",")}]t/h{i < rc.blends.length - 1 ? "；" : ""}</span>)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="panel p-4">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">{"\u5168\u5c40\u53c2\u6570"}</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-2 text-xs">
          {Object.entries(p.global_params).map(([k, v]) => {
            const labels: Record<string, string> = {
              UNLOAD_TPH: "\u5378\u6cb9\u901f\u5ea6(\u5428/\u5c0f\u65f6)",
              TRANSFER_TPH: "T\u2192G\u8f93\u6cb9\u901f\u5ea6(\u5428/\u5c0f\u65f6)",
              GG_TPH: "G\u2192G\u8f93\u6cb9\u901f\u5ea6(\u5428/\u5c0f\u65f6)",
              MIN_BATCH_H: "\u5355\u6279\u6b21\u6700\u77ed\u5c0f\u65f6\u6570",
              MAX_BATCH_H: "\u5355\u6279\u6b21\u6700\u957f\u5c0f\u65f6\u6570",
              EDGE_MIN_BATCH_H: "\u6708\u521d\u6708\u672b\u6279\u6b21\u6700\u77ed\u5c0f\u65f6\u6570",
              MAX_UNLOAD_TANKS: "\u6700\u5927\u4e00\u6b21\u5378\u6cb9\u7f50\u6570",
              SWITCH_RESID_TON: "\u6362\u6cb9\u6b8b\u4f59\u9608\u503c(\u5428)",
              CLEAR_MARGIN: "\u817e\u5bb9\u5b89\u5168\u4f59\u91cf(\u5428)",
              TIME_LIMIT: "\u5355\u8f6e\u6c42\u89e3\u65f6\u9650(\u79d2)",
              N_ROUNDS: "\u591a\u8f6e\u8c03\u5ea6\u8f6e\u6570",
              N_PREFERRED: "\u4f18\u9009\u89e3\u6570\u91cf",
            };
            return (
              <div key={k} className="flex justify-between items-center border-b border-slate-800 py-1.5 px-2">
                <span className="text-slate-300">{labels[k] || k} <span className="text-slate-500 text-[10px]">{k}</span></span>
                <span className="font-mono text-blue-300">{v}</span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
