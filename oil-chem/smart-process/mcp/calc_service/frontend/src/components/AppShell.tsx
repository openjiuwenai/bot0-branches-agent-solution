'use client'

import Sidebar from './Sidebar'
import Topbar from './Topbar'

// 与慧炼主前端同款外壳：固定深色侧栏 + 主区左留白 64(ml-64)、bg-[#F7F9FD]
// 顶栏对齐慧炼 Topbar（面包屑 + 引擎状态）；solve_v1 无 AI 抽屉/登录态
export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex h-screen bg-[#F7F9FD]">
      <Sidebar />
      <div className="flex-1 flex flex-col ml-64 min-h-screen overflow-hidden">
        <Topbar />
        <main className="flex-1 overflow-auto p-5">
          <div className="mx-auto w-full max-w-[1800px] h-full">
            {children}
          </div>
        </main>
      </div>
    </div>
  )
}
