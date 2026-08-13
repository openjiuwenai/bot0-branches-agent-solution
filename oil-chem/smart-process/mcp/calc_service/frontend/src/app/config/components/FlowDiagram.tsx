import { useState, useEffect, useMemo, useRef } from 'react'
import {
  ReactFlow, Background, Controls, MiniMap, Handle, Position,
  useNodesState, useEdgesState,
  type ReactFlowInstance,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from 'dagre'
import { Share2, Star, Maximize2, Minimize2 } from 'lucide-react'
import { CardHead } from '@/components/SolveResult'
import { cn } from '@/lib/utils'
import type { FlowRow, RFNode, RFEdge, FlowNodeData } from '../types'

const MAIN_LINE_THRESHOLD = 10

function DeviceNode({ data }: { data: Record<string, unknown> }) {
  const d = data as FlowNodeData
  const colors = [
    'bg-blue-50 border-blue-400 text-blue-700',      // 0: 源装置
    'bg-amber-50 border-amber-400 text-amber-700',   // 1: 中间储罐
    'bg-emerald-50 border-emerald-400 text-emerald-700', // 2: 加工装置
    'bg-slate-50 border-slate-400 text-slate-600',   // 3: 成品储罐
  ]
  return (
    <div className={cn('rounded-lg border-2 px-3 py-2 text-center text-[14px] font-bold shadow-md min-w-[100px]', colors[d.layer] || colors[3])}>
      <Handle type="target" position={Position.Left} className="!w-2 !h-2 !bg-slate-400" />
      <div className="truncate">{d.name}</div>
      <Handle type="source" position={Position.Right} className="!w-2 !h-2 !bg-slate-400" />
    </div>
  )
}

const nodeTypes = { device: DeviceNode }

/**
 * 两轮 dagre 布局：主线骨架优先，辅助线叠加
 * 1. 第一轮：只布局主线节点+边 → 固定坐标
 * 2. 第二轮：固定主线节点，布局辅助节点
 */
function dagreLayout(
  nodes: RFNode[],
  edges: { source: string; target: string; isMain: boolean }[],
  dir: 'LR' | 'TB' = 'LR',
) {
  if (!nodes.length) return nodes

  const NW = 140, NH = 48
  const mainNodeIds = new Set<string>()

  // 收集主线涉及的节点
  for (const e of edges) {
    if (e.isMain) {
      mainNodeIds.add(e.source)
      mainNodeIds.add(e.target)
    }
  }

  const fixedPos = new Map<string, { x: number; y: number }>()

  // 有主线时做两轮布局
  if (mainNodeIds.size >= 2) {
    // 第一轮：只布局主线子图
    const g1 = new dagre.graphlib.Graph()
    g1.setGraph({ rankdir: dir, ranksep: 70, edgesep: 30, nodesep: 40, marginx: 20, marginy: 20 })
    g1.setDefaultEdgeLabel(() => ({}))
    for (const n of nodes) {
      if (mainNodeIds.has(n.id)) g1.setNode(n.id, { width: NW, height: NH })
    }
    for (const e of edges) {
      if (e.isMain && mainNodeIds.has(e.source) && mainNodeIds.has(e.target)) {
        g1.setEdge(e.source, e.target, { weight: 50 })
      }
    }
    dagre.layout(g1)
    for (const id of mainNodeIds) {
      const pos = g1.node(id)
      if (pos) fixedPos.set(id, { x: pos.x, y: pos.y })
    }

    // 第二轮：全部节点，主线节点固定坐标
    const g2 = new dagre.graphlib.Graph()
    g2.setGraph({ rankdir: dir, ranksep: 60, edgesep: 25, nodesep: 35, marginx: 20, marginy: 20 })
    g2.setDefaultEdgeLabel(() => ({}))
    for (const n of nodes) {
      const fixed = fixedPos.get(n.id)
      if (fixed) {
        // 固定节点：直接设坐标
        g2.setNode(n.id, { width: NW, height: NH, rank: undefined })
      } else {
        g2.setNode(n.id, { width: NW, height: NH })
      }
    }
    for (const e of edges) {
      g2.setEdge(e.source, e.target, { weight: e.isMain ? 50 : 1 })
    }
    dagre.layout(g2)

    // 主线节点用第一轮坐标，辅助节点用第二轮坐标
    return nodes.map(n => {
      const fixed = fixedPos.get(n.id)
      if (fixed) return { ...n, position: { x: fixed.x - NW / 2, y: fixed.y - NH / 2 } }
      const pos = g2.node(n.id)
      return { ...n, position: { x: pos.x - NW / 2, y: pos.y - NH / 2 } }
    })
  }

  // 无主线：单轮布局（权重区分）
  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: dir, ranksep: 60, edgesep: 25, nodesep: 35, marginx: 20, marginy: 20 })
  g.setDefaultEdgeLabel(() => ({}))
  for (const n of nodes) g.setNode(n.id, { width: NW, height: NH })
  for (const e of edges) g.setEdge(e.source, e.target, { weight: e.isMain ? 50 : 1 })
  dagre.layout(g)
  return nodes.map(n => {
    const pos = g.node(n.id)
    return { ...n, position: { x: pos.x - NW / 2, y: pos.y - NH / 2 } }
  })
}

export function FlowDiagram({ rows }: { rows: FlowRow[] }) {
  const [viewMode, setViewMode] = useState<'all' | 'main'>('all')
  const [fullscreen, setFullscreen] = useState(false)
  const rfInstance = useRef<ReactFlowInstance<RFNode, RFEdge> | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  // 主线模式下过滤出星标行
  const displayRows = useMemo(() => {
    if (viewMode === 'all') return rows
    return rows.filter(r => r.priority >= MAIN_LINE_THRESHOLD)
  }, [rows, viewMode])

  const { initNodes, initEdges, mainLineCount } = useMemo(() => {
    const mainLineCount = rows.filter(r => r.priority >= MAIN_LINE_THRESHOLD).length
    if (!displayRows.length) return { initNodes: [] as RFNode[], initEdges: [] as RFEdge[], mainLineCount }

    const nodeMap = new Map<string, { id: string; name: string; type: string }>()
    const edgeList: { from: string; to: string; label: string; flowType: string; specialVar: string; isMain: boolean }[] = []

    for (const f of displayRows) {
      const sid = f.source_device_id || f.source_name || ''
      if (sid && !nodeMap.has(sid))
        nodeMap.set(sid, { id: sid, name: f.source_device_name || f.source_name || sid, type: 'source' })
      if (f.tank_id && !nodeMap.has(f.tank_id))
        nodeMap.set(f.tank_id, { id: f.tank_id, name: f.tank_name || f.tank_id, type: 'tank' })
      if (f.target_device_id && !nodeMap.has(f.target_device_id))
        nodeMap.set(f.target_device_id, { id: f.target_device_id, name: f.target_device_name || f.target_device_id, type: 'target' })

      const prodLabel = f.source_product_name || f.source_product_id || ''
      const isMain = f.priority >= MAIN_LINE_THRESHOLD
      // 归一化模型：每行 = 一条边
      if (f.flow_type === 'source_to_tank' && sid && f.tank_id) {
        edgeList.push({ from: sid, to: f.tank_id, label: prodLabel, flowType: f.flow_type, specialVar: f.special_var, isMain })
      } else if (f.flow_type === 'tank_to_target' && f.tank_id && f.target_device_id) {
        const prodLabel = f.target_product_name || f.target_product_id || ''
        const ratioLabel = f.split_ratio !== 1 ? ` ×${f.split_ratio}` : ''
        edgeList.push({ from: f.tank_id, to: f.target_device_id, label: prodLabel + ratioLabel, flowType: f.flow_type, specialVar: '', isMain })
      } else if (f.flow_type === 'direct' && sid && f.target_device_id) {
        edgeList.push({ from: sid, to: f.target_device_id, label: prodLabel, flowType: f.flow_type, specialVar: f.special_var, isMain })
      } else if (f.flow_type === 'final' && sid && f.tank_id) {
        edgeList.push({ from: sid, to: f.tank_id, label: prodLabel, flowType: f.flow_type, specialVar: f.special_var, isMain })
      }
    }

    // 分层
    const inDeg = new Map<string, number>(), outDeg = new Map<string, number>()
    for (const id of nodeMap.keys()) { inDeg.set(id, 0); outDeg.set(id, 0) }
    for (const e of edgeList) { outDeg.set(e.from, (outDeg.get(e.from) || 0) + 1); inDeg.set(e.to, (inDeg.get(e.to) || 0) + 1); }
    const layerMap = new Map<string, number>()
    for (const [id, node] of nodeMap) {
      const ind = inDeg.get(id) || 0, outd = outDeg.get(id) || 0
      if (outd > 0 && ind === 0) layerMap.set(id, 0)
      else if (ind > 0 && outd === 0) layerMap.set(id, 3)
      else if (node.type === 'tank') layerMap.set(id, 1)
      else layerMap.set(id, 2)
    }

    // 合并相同 from→to 的边（主线 OR 逻辑：任一条星标则合并边为主线）
    const mergedMap = new Map<string, { from: string; to: string; label: string; flowType: string; specialVar: string; isMain: boolean }>()
    for (const e of edgeList) {
      const key = `${e.from}→${e.to}`
      const ex = mergedMap.get(key)
      if (ex) {
        if (e.label && !ex.label.includes(e.label)) ex.label = ex.label ? `${ex.label}, ${e.label}` : e.label
        if (e.isMain) ex.isMain = true
      } else mergedMap.set(key, { ...e })
    }

    const layerLabels = ['源装置', '中间储罐', '加工装置', '成品储罐']
    const rawNodes: RFNode[] = Array.from(nodeMap.values()).map(n => ({
      id: n.id,
      type: 'device',
      position: { x: 0, y: 0 },
      data: { name: n.name, id: n.id, layer: layerMap.get(n.id) ?? 3, label: layerLabels[layerMap.get(n.id) ?? 3] } as Record<string, unknown>,
    }))

    const edgeColor = (e: { flowType: string; specialVar: string; isMain: boolean }) => {
      if (e.isMain) return '#f59e0b'
      if (e.specialVar === 'X') return '#3b82f6'
      if (e.specialVar === 'Y') return '#a855f7'
      if (e.flowType === 'tank_to_target') return '#06b6d4'
      if (e.flowType === 'direct') return '#22c55e'
      if (e.flowType === 'final') return '#94a3b8'
      return '#64748b'
    }

    const rfEdges: RFEdge[] = Array.from(mergedMap.values()).map((e, i) => ({
      id: `e${i}`,
      source: e.from,
      target: e.to,
      label: e.label || undefined,
      labelStyle: { fontSize: 10, fill: edgeColor(e) },
      labelBgStyle: { fill: '#fff' },
      labelBgPadding: [4, 2] as [number, number],
      labelBgBorderRadius: 4,
      style: { stroke: edgeColor(e), strokeWidth: e.isMain ? 2.5 : 1.5 },
      type: 'default',
      animated: e.specialVar !== '' || e.isMain,
    }))

    // 为 dagreLayout 准备带 isMain 的边
    const layoutEdges = Array.from(mergedMap.values()).map(e => ({
      source: e.from, target: e.to, isMain: e.isMain,
    }))
    const laidNodes = dagreLayout(rawNodes, layoutEdges, 'LR')
    return { initNodes: laidNodes, initEdges: rfEdges, mainLineCount }
  }, [displayRows, rows])

  const [nodes, setNodes, onNodesChange] = useNodesState(initNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initEdges)

  // 数据变化时重新布局
  useEffect(() => { setNodes(initNodes); setEdges(initEdges) }, [initNodes, initEdges, setNodes, setEdges])

  // 浏器原生全屏
  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      containerRef.current?.requestFullscreen?.()
    } else {
      document.exitFullscreen?.()
    }
  }

  // 监听全屏状态变化，同步 state 并重新 fitView
  useEffect(() => {
    const onFsChange = () => {
      setFullscreen(!!document.fullscreenElement)
      setTimeout(() => rfInstance.current?.fitView({ padding: 0.15 }), 60)
    }
    document.addEventListener('fullscreenchange', onFsChange)
    return () => document.removeEventListener('fullscreenchange', onFsChange)
  }, [])

  if (!rows.length) return null

  return (
    <div ref={containerRef} className={cn(
      'bg-white shadow-sm overflow-hidden',
      fullscreen
        ? 'w-screen h-screen'
        : 'rounded-xl border border-[#E6EAF1] mb-4'
    )}>
      <div className="flex items-center justify-between gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/60">
        <CardHead icon={Share2} title="装置连接图" accent="from-indigo-500 to-purple-600"
          hint="物料流拓扑 · 可拖拽节点 · 滚轮缩放 · 产品名标注在连线上" />
        <div className="flex items-center gap-2">
          {/* 全量/主线切换 */}
        <div className="flex items-center gap-1 bg-white rounded-lg border border-slate-200 p-0.5">
          <button
            onClick={() => setViewMode('all')}
            className={cn('px-3 py-1 text-[11px] rounded-md transition-colors',
              viewMode === 'all' ? 'bg-indigo-500 text-white' : 'text-slate-500 hover:bg-slate-50')}
          >
            全量
          </button>
          <button
            onClick={() => setViewMode('main')}
            className={cn('px-3 py-1 text-[11px] rounded-md transition-colors flex items-center gap-1',
              viewMode === 'main' ? 'bg-amber-500 text-white' : 'text-slate-500 hover:bg-slate-50')}
          >
            <Star className={cn('w-3 h-3', viewMode === 'main' && 'fill-white')} />
            主线
            {mainLineCount > 0 && <span className={cn('text-[10px]', viewMode === 'main' ? 'text-amber-100' : 'text-amber-500')}>({mainLineCount})</span>}
          </button>
        </div>
          {/* 全屏切换 */}
          <button
            onClick={toggleFullscreen}
            className="p-1.5 rounded-lg border border-slate-200 bg-white text-slate-500 hover:text-slate-700 hover:bg-slate-50 transition-colors"
            title={fullscreen ? '退出全屏' : '全屏查看'}
          >
            {fullscreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>
      <div className="p-2">
        {displayRows.length === 0 ? (
          <div className="h-[420px] flex items-center justify-center text-slate-400 text-sm">
            <div className="text-center">
              <Star className="w-8 h-8 mx-auto mb-2 text-slate-300" />
              <p>暂无主线标记</p>
              <p className="text-[11px] mt-1">在物料流向表中点击星标按钮标记主线流向</p>
            </div>
          </div>
        ) : (
          <>
            <div style={{ height: fullscreen ? 'calc(100vh - 120px)' : 420 }}>
              <ReactFlow
                nodes={nodes}
                edges={edges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onInit={(inst) => { rfInstance.current = inst }}
                nodeTypes={nodeTypes}
                nodesDraggable
                fitView
                fitViewOptions={{ padding: 0.15 }}
                minZoom={0.3}
                maxZoom={2}
                proOptions={{ hideAttribution: true }}
              >
                <Background color="#e2e8f0" gap={16} />
                <Controls showInteractive={false} />
                <MiniMap
                  nodeColor={(n) => {
                    const layer = (n.data as FlowNodeData)?.layer ?? 3
                    return ['#bfdbfe', '#fde68a', '#a7f3d0', '#e2e8f0'][layer] || '#e2e8f0'
                  }}
                  maskColor="rgba(0,0,0,0.05)"
                  pannable
                  zoomable
                />
              </ReactFlow>
            </div>
            {/* 图例 */}
            <div className="flex items-center gap-4 mt-2 pt-2 border-t border-slate-100 text-[11px] text-slate-500 px-2 flex-wrap">
              <span className="flex items-center gap-1"><span className="w-3 h-3 rounded bg-blue-100 border border-blue-400"></span>源装置</span>
              <span className="flex items-center gap-1"><span className="w-3 h-3 rounded bg-amber-100 border border-amber-400"></span>中间储罐</span>
              <span className="flex items-center gap-1"><span className="w-3 h-3 rounded bg-emerald-100 border border-emerald-400"></span>加工装置</span>
              <span className="flex items-center gap-1"><span className="w-3 h-3 rounded bg-slate-100 border border-slate-400"></span>成品储罐</span>
              <span className="flex items-center gap-1"><span className="w-3 h-0.5 bg-amber-500" style={{height:'2.5px'}}></span>主线</span>
              <span className="flex items-center gap-1"><span className="w-3 h-0.5 bg-blue-500"></span>X分流</span>
              <span className="flex items-center gap-1"><span className="w-3 h-0.5 bg-purple-500"></span>Y分流</span>
              <span className="flex items-center gap-1"><span className="w-3 h-0.5 bg-cyan-500"></span>罐→装置</span>
              <span className="flex items-center gap-1"><span className="w-3 h-0.5 bg-green-500"></span>直供</span>
              <span className="text-slate-400">· 虚线动画=分流/主线 · ×N=分配比例</span>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
