import { ApiData } from "./types";

export async function fetchData(): Promise<ApiData> {
  const res = await fetch("/api/data", { cache: "no-store" });
  if (!res.ok) throw new Error(`API ${res.status}`);
  return res.json();
}

export function crudeColor(crude: string): string {
  const colors: Record<string, string> = {
    CFD: "#3b82f6",
    BZ: "#ef4444",
    "QHD/NP": "#22c55e",
    ATP: "#f59e0b",
    SEPIA: "#a855f7",
    JZ: "#06b6d4",
    LD: "#ec4899",
    PY: "#84cc16",
    TP: "#f97316",
    ESPO: "#8b5cf6",
  };
  return colors[crude] || "#64748b";
}

export function fmt(n: number | null | undefined): string {
  if (n === null || n === undefined) return "—";
  if (Math.abs(n) >= 10000) return (n / 10000).toFixed(1) + "万";
  return Math.round(n).toLocaleString();
}

export function fmtTon(n: number | null | undefined): string {
  if (n === null || n === undefined) return "—";
  return Math.round(n).toLocaleString() + "t";
}
