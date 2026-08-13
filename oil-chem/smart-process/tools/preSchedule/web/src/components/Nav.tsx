"use client";
import { useState, useRef, useEffect } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";

const navItems = [
  { href: "/", label: "\u603b\u89c8" },
  { href: "/parameters", label: "\u53c2\u6570\u8f93\u5165" },
  { href: "/phase0", label: "Phase0 \u9884\u5904\u7406" },
  { href: "/gantt", label: "\u6392\u7a0b\u7518\u7279\u56fe" },
  { href: "/grid", label: "\u7f50\u7269\u6d41\u7f51\u683c" },
  { href: "/compliance", label: "\u8fbe\u6807\u5bf9\u6bd4" },
];

function Nav() {
  const pathname = usePathname();
  const [running, setRunning] = useState(false);
  const [runMsg, setRunMsg] = useState("");
  const [elapsed, setElapsed] = useState(0);
  const [logs, setLogs] = useState<string[]>([]);
  const [showLogs, setShowLogs] = useState(false);
  const [showTemplateModal, setShowTemplateModal] = useState(false);
  const [templates, setTemplates] = useState<{name: string; size: number}[]>([]);
  const [selectedTemplate, setSelectedTemplate] = useState("");
  const [loadingTemplates, setLoadingTemplates] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const startRef = useRef(0);
  const logEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (showLogs && logEndRef.current) {
      logEndRef.current.scrollTop = logEndRef.current.scrollHeight;
    }
  }, [logs, showLogs]);

  function stopTimers() {
    if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
    if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
  }

  async function handleRunClick() {
    if (running) return;
    setLoadingTemplates(true);
    try {
      const res = await fetch("/api/templates");
      const d = await res.json();
      setTemplates(d.templates || []);
      setSelectedTemplate(d.selected || "");
      setShowTemplateModal(true);
    } catch {
      // 如果获取模板列表失败，直接用默认运行
      startRun("");
    }
    setLoadingTemplates(false);
  }

  async function startRun(template: string) {
    setShowTemplateModal(false);
    setRunning(true);
    setRunMsg("\u6b63\u5728\u542f\u52a8\u6392\u4ea7...");
    setElapsed(0);
    setLogs([]);
    setShowLogs(true);
    startRef.current = Date.now();

    timerRef.current = setInterval(() => {
      setElapsed(Math.floor((Date.now() - startRef.current) / 1000));
    }, 1000);

    try {
      const body = template ? JSON.stringify({ template }) : "{}";
      const res = await fetch("/api/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      });
      if (!res.ok) throw new Error(`API ${res.status}`);
      const d = await res.json();
      if (d.status === "already_running") {
        setRunMsg("\u6392\u4ea7\u5df2\u5728\u8fd0\u884c\u4e2d...");
      } else if (d.status === "running") {
        setRunMsg(template ? `\u4f7f\u7528\u6a21\u677f: ${template}` : "\u6392\u4ea7\u8fd0\u884c\u4e2d...");
      } else if (d.error) {
        throw new Error(d.error);
      } else {
        setRunMsg("\u5b8c\u6210\uff01");
        stopTimers();
        setRunning(false);
        setTimeout(() => window.location.reload(), 1000);
        return;
      }

      pollRef.current = setInterval(async () => {
        try {
          const sr = await fetch("/api/status", { cache: "no-store" });
          const sd = await sr.json();
          if (sd.logs) {
            setLogs(sd.logs);
          }
          if (sd.status === "done") {
            stopTimers();
            const sec = Math.floor((Date.now() - startRef.current) / 1000);
            const data = sd.data;
            setRunMsg(`\u5b8c\u6210\uff01${data.n_rounds_run} \u8f6e\uff0c\u8fbe\u6807 ${data.compliance.compliant}/${data.compliance.n_judged}\uff0c\u8017\u65f6 ${sec}s`);
            setRunning(false);
            setTimeout(() => { setRunMsg(""); setShowLogs(false); window.location.reload(); }, 3000);
          } else if (sd.status === "error") {
            stopTimers();
            setRunMsg(`\u5931\u8d25: ${sd.error?.substring(0, 100) || "\u672a\u77e5\u9519\u8bef"}`);
            setRunning(false);
          } else {
            setRunMsg(`\u6392\u4ea7\u8fd0\u884c\u4e2d... ${Math.floor((Date.now() - startRef.current) / 1000)}s`);
          }
        } catch {
          // Server might be briefly unavailable, keep polling
        }
      }, 3000);
    } catch (e: any) {
      stopTimers();
      setRunMsg(`\u5931\u8d25: ${e.message}`);
      setRunning(false);
    }
  }

  return (
    <>
      <nav className="flex items-center gap-1 px-4 py-3 border-b border-slate-700 bg-slate-900 flex-wrap">
        <span className="text-sm font-bold text-blue-400 mr-4">{"\u539f\u6cb9\u6392\u4ea7\u8c03\u6d4b"}</span>
        {navItems.map((item) => {
          const active = pathname === item.href;
          return (
            <Link
              key={item.href}
              href={item.href}
              className={`px-3 py-1.5 rounded text-sm transition ${
                active
                  ? "bg-blue-600 text-white"
                  : "text-slate-400 hover:text-white hover:bg-slate-800"
              }`}
            >
              {item.label}
            </Link>
          );
        })}
        <div className="ml-auto flex items-center gap-3">
          {runMsg && (
            <span className={`text-xs ${running ? "text-amber-400" : runMsg.startsWith("\u5b8c\u6210") ? "text-green-400" : "text-red-400"}`}>
              {running && <span className="inline-block w-3 h-3 border-2 border-amber-400 border-t-transparent rounded-full animate-spin mr-1 align-middle" />}
              {runMsg}
            </span>
          )}
          {running && (
            <button
              onClick={() => setShowLogs(!showLogs)}
              className="px-2 py-1 rounded text-xs bg-slate-700 text-slate-300 hover:bg-slate-600"
            >
              {showLogs ? "\u9690\u85cf\u65e5\u5fd7" : "\u663e\u793a\u65e5\u5fd7"}
            </button>
          )}
          <button
            onClick={handleRunClick}
            disabled={running || loadingTemplates}
            className={`px-4 py-1.5 rounded text-sm font-medium transition ${
              running || loadingTemplates
                ? "bg-slate-700 text-slate-500 cursor-not-allowed"
                : "bg-green-600 text-white hover:bg-green-500"
            }`}
          >
            {running ? `\u8fd0\u884c\u4e2d ${elapsed}s` : "\u25b6 \u8fd0\u884c\u6392\u4ea7"}
          </button>
        </div>
      </nav>

      {/* 模板选择弹窗 */}
      {showTemplateModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
          <div className="panel p-6 max-w-lg w-full mx-4">
            <h3 className="text-sm font-semibold text-slate-200 mb-4">{"\u9009\u62e9\u6392\u4ea7\u6a21\u677f"}</h3>
            <div className="space-y-1 max-h-72 overflow-y-auto">
              {templates.length === 0 ? (
                <div className="text-xs text-slate-500 py-4 text-center">{"\u672a\u627e\u5230\u6a21\u677f\u6587\u4ef6"}</div>
              ) : (
                templates.map((t) => (
                  <button
                    key={t.name}
                    onClick={() => setSelectedTemplate(t.name)}
                    className={`w-full text-left px-3 py-2 rounded text-xs transition ${
                      selectedTemplate === t.name
                        ? "bg-blue-600 text-white"
                        : "bg-slate-800 text-slate-400 hover:bg-slate-700"
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <span>{t.name}</span>
                      <span className="text-slate-500">{(t.size / 1024).toFixed(1)} KB</span>
                    </div>
                  </button>
                ))
              )}
            </div>
            <div className="flex justify-end gap-2 mt-4">
              <button
                onClick={() => setShowTemplateModal(false)}
                className="px-4 py-1.5 rounded text-xs bg-slate-700 text-slate-400 hover:bg-slate-600"
              >
                {"\u53d6\u6d88"}
              </button>
              <button
                onClick={() => startRun(selectedTemplate)}
                disabled={!selectedTemplate}
                className={`px-4 py-1.5 rounded text-xs font-medium ${
                  selectedTemplate
                    ? "bg-green-600 text-white hover:bg-green-500"
                    : "bg-slate-700 text-slate-500 cursor-not-allowed"
                }`}
              >
                {"\u5f00\u59cb\u6392\u4ea7"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 日志面板 */}
      {showLogs && (
        <div className="bg-slate-950 border-b border-slate-800 px-4 py-2">
          <div
            ref={logEndRef}
            className="max-h-64 overflow-y-auto font-mono text-xs text-slate-400 leading-relaxed"
          >
            {logs.length === 0 ? (
              <span className="text-slate-600">{"\u7b49\u5f85\u8f93\u51fa..."}</span>
            ) : (
              logs.map((line, i) => (
                <div key={i} className={
                  line.includes("Benders") ? "text-blue-400" :
                  line.includes("\u6863") || line.includes("\u9636\u68af") ? "text-amber-400" :
                  line.includes("OK") || line.includes("\u5b8c\u6210") || line.includes("\u6536\u655b") ? "text-green-400" :
                  line.includes("Failed") || line.includes("Error") || line.includes("\u5931\u8d25") ? "text-red-400" :
                  "text-slate-400"
                }>
                  {line}
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </>
  );
}

export default Nav;
