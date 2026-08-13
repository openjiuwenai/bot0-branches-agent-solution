'use client'

import { useEffect, useRef } from 'react'

// 可复用 echarts 包装：动态 import（不进首屏 bundle），配色对齐慧炼主前端。
// 基于 慧炼 frontend/components/route-compare/YieldChart.tsx 范式。
// option 透传给 echarts.setOption；notMerge=true 确保切月时全量替换。
export default function EChart({
  option, height = 280, className = '',
}: {
  option: Record<string, unknown>
  height?: number
  className?: string
}) {
  const chartRef = useRef<HTMLDivElement>(null)
  const instanceRef = useRef<import('echarts').ECharts | null>(null)

  useEffect(() => {
    if (!chartRef.current) return
    let disposed = false
    import('echarts').then(echarts => {
      if (disposed || !chartRef.current) return
      if (!instanceRef.current) {
        instanceRef.current = echarts.init(chartRef.current)
      }
      instanceRef.current.setOption(option, true)
    })
    return () => { disposed = true }
  }, [option])

  // 卸载时释放实例
  useEffect(() => {
    return () => {
      instanceRef.current?.dispose()
      instanceRef.current = null
    }
  }, [])

  // 窗口缩放自适应
  useEffect(() => {
    const handler = () => instanceRef.current?.resize()
    window.addEventListener('resize', handler)
    return () => window.removeEventListener('resize', handler)
  }, [])

  return <div ref={chartRef} style={{ height }} className={className} />
}

// 慧炼主前端 echarts 配色常量，供各页拼 option 时复用
export const CHART_COLORS = {
  blue: '#3b82f6',
  blueDeep: '#1E5BFF',
  green: '#22c55e',
  purple: '#8b5cf6',
  amber: '#f59e0b',
  axisLine: '#e2e8f0',
  splitLine: '#f1f5f9',
  label: '#64748b',
  labelDim: '#94a3b8',
  tooltipBorder: '#E6EAF1',
  tooltipShadow: '0 12px 32px rgba(15,23,42,.14)',
}
