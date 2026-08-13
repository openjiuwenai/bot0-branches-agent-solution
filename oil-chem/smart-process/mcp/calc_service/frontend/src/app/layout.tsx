import type { Metadata } from 'next'
import './globals.css'
import { AppShell } from '@/components/AppShell'

export const metadata: Metadata = {
  title: '求解器 — solve_v1',
  description: '减一线切换组合求解器前端（直连 solve_v1 服务）',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body className="bg-[#F7F9FD] min-h-screen text-slate-900">
        <AppShell>{children}</AppShell>
      </body>
    </html>
  )
}
