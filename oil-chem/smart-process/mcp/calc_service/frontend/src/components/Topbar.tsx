'use client'

import { usePathname } from 'next/navigation'

// 路径 → 面包屑标签（业务视图 + 开发校验，与侧栏一致）
const ROUTE_LABEL: Record<string, string> = {
  '/decision': '效益决策台',
  '/': '排产求解',
  '/batches': '批次划分与切换组合',
  '/predict': '效益预测',
  '/config': '基础配置',
}

// 顶栏：对齐慧炼主前端范式（h-12 白底 + 面包屑 + 右侧引擎状态）。
// solve_v1 无 AI 抽屉/登录态/全局搜索，故只保留面包屑与引擎在线指示。
export default function Topbar() {
  const pathname = usePathname()
  const current = ROUTE_LABEL[pathname] || ROUTE_LABEL[pathname.split('/').filter(Boolean)[0] ? `/${pathname.split('/').filter(Boolean)[0]}` : '/'] || '求解器'

  return (
    <header className="h-12 bg-white border-b border-slate-200 flex items-center px-6 gap-4 sticky top-0 z-20 shrink-0">
      {/* 面包屑 */}
      <div className="flex items-center gap-2 text-sm">
        <span className="text-slate-400">求解器</span>
        <span className="text-slate-300">/</span>
        <span className="text-slate-800 font-medium">{current}</span>
      </div>

      <div className="ml-auto flex items-center gap-4 text-[12px] text-slate-500">
        {/* 引擎状态 */}
        <div className="flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-green-500 animate-pulse" />
          <span>求解引擎 <span className="text-slate-700 font-medium">SCIP</span> · 在线</span>
        </div>
        <span className="w-px h-4 bg-slate-200" />
        <span>排产优化 / 减一线切换组合</span>
      </div>
    </header>
  )
}
