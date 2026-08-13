import type { Metadata } from "next";
import "./globals.css";
import Nav from "@/components/Nav";

export const metadata: Metadata = {
  title: "原油加工计划预排产调测",
  description: "可视化展示预排产过程结果",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="zh-CN">
      <body>
        <Nav />
        <main className="p-4 max-w-[1600px] mx-auto">{children}</main>
      </body>
    </html>
  );
}
