'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { Cpu, GitBranch, TrendingUp, ClipboardCheck, Settings } from 'lucide-react'
import { cn } from '@/lib/utils'

// 与慧炼主前端同款深色侧栏：bg-slate-900 w-64，blue-600 选中，渐变 logo 块
// 分两段：业务视图（面向生产规划人员的决策台）+ 开发校验（求解器三阶段调试页）
//   开发校验三项对应求解器三阶段：①排产(LP) → ②批次划分+切换组合识别 → ③效益预测
const navSections = [
  {
    section: '业务视图',
    items: [{ href: '/decision', icon: ClipboardCheck, label: '效益决策台' }],
  },
  {
    section: '开发校验',
    items: [
      { href: '/', icon: Cpu, label: '排产求解' },
      { href: '/batches', icon: GitBranch, label: '批次划分与切换组合' },
      { href: '/predict', icon: TrendingUp, label: '效益预测' },
      { href: '/config', icon: Settings, label: '基础配置' },
    ],
  },
]

export default function Sidebar() {
  const pathname = usePathname()

  return (
    <aside className="w-64 bg-slate-900 text-white flex flex-col h-screen fixed left-0 top-0 z-30">
      {/* Logo */}
      <div className="px-5 py-4 border-b border-white/5">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-purple-600 rounded-lg flex items-center justify-center">
            <Cpu className="w-4 h-4 text-white" />
          </div>
          <div>
            <div className="text-base font-bold tracking-wide">求解器</div>
            <div className="text-[10px] text-slate-400">减一线切换组合优化 solve_v1</div>
          </div>
        </div>
      </div>

      {/* Nav */}
      <nav className="flex-1 py-4 overflow-y-auto">
        {navSections.map((sec, si) => (
          <div key={sec.section} className={si > 0 ? 'mt-5 border-t border-white/5 pt-4' : ''}>
            <div className="px-3 mb-2">
              <span className="text-xs text-slate-500 uppercase tracking-wider px-3">{sec.section}</span>
            </div>
            {sec.items.map((item) => {
              // "/" 需精确匹配，避免 /predict 也命中首页
              const active = item.href === '/' ? pathname === '/' : pathname.startsWith(item.href)
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={cn(
                    'flex items-center gap-3 mx-2 px-3 py-2.5 rounded-lg text-sm transition-all duration-150',
                    active
                      ? 'bg-blue-600 text-white shadow-sm'
                      : 'text-slate-400 hover:text-white hover:bg-white/5',
                  )}
                >
                  <item.icon className="w-4 h-4 shrink-0" />
                  <span>{item.label}</span>
                </Link>
              )
            })}
          </div>
        ))}
      </nav>

      {/* 底部品牌/版本区：与顶部 logo 呼应，避免侧栏悬空 */}
      <div className="border-t border-white/5 px-5 py-3">
        <div className="flex items-center gap-2">
          <span className="w-1.5 h-1.5 rounded-full bg-green-500" />
          <span className="text-[11px] text-slate-400">solve_v1 · v1.5</span>
        </div>
        <div className="text-[10px] text-slate-600 mt-1">SCIP · OR-Tools LP 求解引擎</div>
      </div>
    </aside>
  )
}
