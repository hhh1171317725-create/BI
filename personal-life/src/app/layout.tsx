import type { Metadata } from 'next';
import './globals.css';
export const metadata: Metadata = {
  title: 'Life · 个人健康工作台',
  description: '私人健康数据、睡眠与运动分析',
  robots: { index: false, follow: false },
};
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
