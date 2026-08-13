import { NextRequest, NextResponse } from 'next/server'

// solve_v1 前端专用 BFF 透传：/api/* → solve_v1 Flask :5081 /api/*
// 本前端独立于主慧炼前端，直连 solve_v1 服务，不经主后端，便于求解器整体独立部署。

// Next.js App Router 默认会在 ~60s 后杀掉长请求，必须显式放宽。
// CP-SAT 排产求解最长约 7 分钟，设 600s 安全余量。
export const maxDuration = 600

const SOLVE_V1 = process.env.SOLVE_V1_URL || 'http://localhost:5081'
// 代理超时：略大于 maxDuration，让 Next 自身超时先触发
const PROXY_TIMEOUT_MS = 610_000

async function proxy(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  const path = (await params).path
  const apiPath = path.join('/')
  const queryString = request.nextUrl.search
  const url = `${SOLVE_V1}/api/${apiPath}${queryString}`

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), PROXY_TIMEOUT_MS)

  try {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    const method = request.method
    const fetchOptions: RequestInit = { method, headers, signal: controller.signal }
    if (['POST', 'PUT'].includes(method)) {
      fetchOptions.body = JSON.stringify(await request.json())
    }

    const res = await fetch(url, fetchOptions)
    // 后端可能返回非 JSON（如 Flask 404 HTML），需容错
    const text = await res.text()
    let data: unknown
    try {
      data = JSON.parse(text)
    } catch {
      return NextResponse.json(
        { detail: `后端返回非JSON响应(HTTP ${res.status}): ${text.slice(0, 200)}` },
        { status: res.status },
      )
    }
    return NextResponse.json(data, { status: res.status })
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    return NextResponse.json(
      { detail: `BFF代理失败: ${msg}` },
      { status: 502 },
    )
  } finally {
    clearTimeout(timer)
  }
}

export async function GET(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  return proxy(request, { params })
}

export async function POST(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  return proxy(request, { params })
}

export async function PUT(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  return proxy(request, { params })
}

export async function DELETE(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  return proxy(request, { params })
}
