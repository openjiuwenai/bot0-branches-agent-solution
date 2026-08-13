/** @type {import('next').NextConfig} */
const nextConfig = {
  // solve_v1 前端独立运行(:5082)，直连 solve_v1 Flask(:5081)，不经主后端。
  // 前端调 /api/* 由 app/api/[...path]/route.ts 透传到 SOLVE_V1_URL。
}
module.exports = nextConfig
