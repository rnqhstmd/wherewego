import type { NextConfig } from "next";

const BACKEND_BASE_URL =
  process.env.BACKEND_BASE_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // Phase 13 (FR-PIN-9): Server Action 기본 bodySizeLimit(1MB)은 2MB 압축 사진
  // 멀티파트 업로드를 거부하므로 4MB 로 상향한다 (멀티파트 오버헤드 여유 포함).
  experimental: {
    serverActions: {
      bodySizeLimit: "4mb",
    },
  },
  async rewrites() {
    return [
      {
        source: "/api/v1/:path*",
        destination: `${BACKEND_BASE_URL}/api/v1/:path*`,
      },
    ];
  },
};

export default nextConfig;
