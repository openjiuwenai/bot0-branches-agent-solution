"use client";
import { useState } from "react";
import useApiData from "@/components/useApiData";
import NoDataHint from "@/components/NoDataHint";
import { fmt, fmtTon, crudeColor } from "@/lib/api";
import type { GridCell } from "@/lib/types";

function cellBg(cell: GridCell): string {
  if (cell.proc && cell.proc > 0) return crudeColor(cell.crude || "") + "60";
  if (cell.no_feed_h && cell.no_feed_h > 0) return "#47556960";
  if (cell.recv && cell.recv > 0) return "#22c55e30";
  return "#1e293b60";
}

export default function GridPage() {
  const { data, loading, error, noData } = useApiData();
  const [round, setRound] = useState(0);
  const [selectedCell, setSelectedCell] = useState<{ tank: string; day: number } | null>(null);

  if (loading) return <div className="text-center py-20 text-slate-400">{"\u52a0\u8f7d\u4e2d..."}</div>;
  if (error) return <div className="text-red-400 p-4">{"\u9519\u8bef"}: {error}</div>;
  if (noData) return <NoDataHint />;
  if (!data) return null;

  const tg = data.tank_grids[round];
  if (!tg) return <div>{"\u65e0\u8f6e\u6b21\u6570\u636e"}</div>;
  const p = data.parameters;
  const allTanks = [...p.gtanks, ...p.ttanks];
  const days = p.days;
  const sel = selectedCell ? tg.grid[selectedCell.tank]?.[String(selectedCell.day)] : null;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-sm text-slate-400">{"\u8f6e\u6b21"}:</span>
        {data.tank_grids.map((t, i) => (
          <button key={i} onClick={() => setRound(i)}
            className={`px-3 py-1 rounded text-xs transition ${round === i ? "bg-blue-600 text-white" : "bg-slate-800 text-slate-400 hover:bg-slate-700"}`}>
            {"\u8f6e"}{t.round}
          </button>
        ))}
      </div>

      <div className="panel p-4 overflow-x-auto">
        <h2 className="text-sm font-semibold text-slate-300 mb-3">{"\u7f50 \u00d7 \u5929 \u7269\u6d41\u7f51\u683c"}</h2>
        <div className="min-w-[1200px]">
          <div className="flex">
            <div className="w-20 shrink-0 text-xs text-slate-400 text-right pr-2 py-1">{"\u7f50\\\u5929"}</div>
            <div className="flex-1 flex">
              {Array.from({ length: days }, (_, d) => <div key={d} className="flex-1 text-center text-[9px] text-slate-500 py-1 min-w-[28px]">{d + 1}</div>)}
            </div>
          </div>
          {allTanks.map((t) => {
            const tk = p.tanks[t];
            const isG = tk.is_g;
            return <div key={t} className="flex items-center border-b border-slate-800/50">
              <div className="w-20 shrink-0 text-right pr-2 py-0.5">
                <span className={`text-xs font-mono ${isG ? "text-blue-400" : "text-slate-500"}`}>{t}</span>
                <div className="text-[8px] text-slate-600">{isG ? "G" : "T"} {fmt(tk.cap / 10000)}{"\u4e07"}</div>
              </div>
              <div className="flex-1 flex">
                {Array.from({ length: days }, (_, d) => {
                  const day = d + 1;
                  const cell = tg.grid[t]?.[String(day)];
                  if (!cell) return <div key={d} className="flex-1 min-w-[28px] h-9 bg-slate-900" />;
                  const hasProc = cell.proc && cell.proc > 0;
                  const hasRecv = cell.recv && cell.recv > 0;
                  const hasNoFeed = cell.no_feed_h && cell.no_feed_h > 0;
                  return <div key={d} onClick={() => setSelectedCell({ tank: t, day })}
                    className="flex-1 min-w-[28px] h-9 border border-slate-900 cursor-pointer hover:ring-1 hover:ring-white/30 flex flex-col items-center justify-center text-[8px] leading-tight"
                    style={{ background: cellBg(cell) }}
                    title={`${t} d${day}\n${cell.crude || "\u2014"}\nproc:${fmt(cell.proc)}\ninv:${fmt(cell.inv)}`}>
                    {hasProc && <><span className="text-white font-bold">{Math.round(cell.time!)}h</span><span className="text-white/70">{Math.round(cell.proc! / 1000)}k</span></>}
                    {!hasProc && hasNoFeed && <span className="text-slate-400">{cell.no_feed_h}h</span>}
                    {!hasProc && !hasNoFeed && hasRecv && <span className="text-green-400">{cell.unload_recv ? "\u5378" : "\u8f93"}{Math.round(cell.recv! / 1000)}k</span>}
                    {!hasProc && !hasNoFeed && !hasRecv && cell.inv !== null && <span className="text-slate-600">{Math.round(cell.inv / 1000)}</span>}
                  </div>;
                })}
              </div>
            </div>;
          })}
        </div>
        <div className="flex flex-wrap gap-4 mt-3 text-xs text-slate-400">
          <span className="flex items-center gap-1"><span className="w-3 h-3 rounded" style={{ background: "#3b82f660" }} />CFD</span>
          <span className="flex items-center gap-1"><span className="w-3 h-3 rounded" style={{ background: "#ef444460" }} />BZ</span>
          <span className="flex items-center gap-1"><span className="w-3 h-3 rounded" style={{ background: "#22c55e60" }} />QHD/NP</span>
          <span className="flex items-center gap-1"><span className="w-3 h-3 rounded" style={{ background: "#f59e0b60" }} />ATP</span>
          <span className="flex items-center gap-1"><span className="w-3 h-3 rounded bg-slate-600/40" />{"\u4e0d\u4f9b\u6599\u7070\u5757"}</span>
          <span className="flex items-center gap-1"><span className="w-3 h-3 rounded bg-green-900/30" />{"\u5230\u5e93"}</span>
        </div>
      </div>

      {sel && selectedCell && (
        <div className="panel p-4">
          <h2 className="text-sm font-semibold text-slate-300 mb-3">{selectedCell.tank} {"\u7b2c"} {selectedCell.day} {"\u5929 \u8be6\u60c5"}</h2>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
            {[{ label: "0\u70b9\u53ef\u7528\u5e93\u5b58", value: fmt(sel.inv), color: "text-white" },
              { label: "\u539f\u6cb9\u54c1\u79cd", value: sel.crude || "\u2014", color: "text-white" },
              { label: "\u52a0\u5de5\u8d1f\u8377", value: sel.load != null ? `${sel.load} t/h` : "\u2014", color: "text-white" },
              { label: "\u52a0\u5de5\u65f6\u95f4", value: sel.time != null ? `${sel.time} h` : "\u2014", color: "text-white" },
              { label: "\u52a0\u5de5\u91cf", value: fmt(sel.proc), color: "text-blue-400" },
              { label: "\u5230\u5e93\u91cf", value: fmt(sel.recv), color: "text-green-400" },
              { label: "\u5176\u4e2d\u5378\u6cb9\u5230\u5e93", value: fmt(sel.unload_recv), color: "text-green-400" },
              { label: "T\u2192G\u8f93\u51fa", value: fmt(sel.transfer_out), color: "text-orange-400" },
              { label: "\u4e0d\u4f9b\u6599\u5c0f\u65f6", value: sel.no_feed_h != null ? `${sel.no_feed_h} h` : "\u2014", color: "text-slate-400" },
            ].map((f) => (
              <div key={f.label} className="border border-slate-700 rounded p-2">
                <div className="text-slate-500">{f.label}</div>
                <div className={`font-mono font-bold mt-1 ${f.color}`}>{f.value}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {tg.clog.length > 0 && (
        <div className="panel p-4">
          <h2 className="text-sm font-semibold text-slate-300 mb-3">{"\u63a5\u5378/\u4f20\u8f93\u65e5\u5fd7"}</h2>
          <div className="space-y-1 max-h-60 overflow-y-auto">
            {tg.clog.map((l, i) => <div key={i} className="text-xs text-slate-400 bg-slate-800/30 rounded px-2 py-1">{l}</div>)}
          </div>
        </div>
      )}
    </div>
  );
}
