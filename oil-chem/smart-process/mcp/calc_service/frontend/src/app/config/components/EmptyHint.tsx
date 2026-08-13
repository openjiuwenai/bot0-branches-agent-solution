export function EmptyHint({ text }: { text: string }) {
  return (
    <div className="p-10 rounded-xl border border-dashed border-slate-200 bg-slate-50/40 text-center text-sm text-slate-400">
      {text}
    </div>
  )
}
