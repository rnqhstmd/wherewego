import type { Metadata, Viewport } from "next";
import { Geist_Mono, Gowun_Batang, JetBrains_Mono, Noto_Serif_KR } from "next/font/google";
import localFont from "next/font/local";
import "./globals.css";

// Phase 6 폰트 — tokens.jsx::F 매칭. Pretendard는 self-host.
const sans = localFont({
  src: "../../public/fonts/PretendardVariable.woff2",
  variable: "--font-sans",
  display: "swap",
  weight: "45 920",
});

const serif = Noto_Serif_KR({
  subsets: ["latin"],
  weight: ["400", "700", "900"],
  variable: "--font-serif",
  display: "swap",
});

const emo = Gowun_Batang({
  subsets: ["latin"],
  weight: ["400", "700"],
  variable: "--font-emo",
  display: "swap",
});

const mono = JetBrains_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-mono",
  display: "swap",
});

// 기존 Geist Mono는 다른 페이지 호환을 위해 유지(별도 변수).
const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "우리가 갈 지도",
  description: "우리의 장소를 지도 위에 아카이빙해요",
  other: {
    "format-detection": "telephone=no, address=no, email=no",
  },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  viewportFit: "cover",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="ko"
      className={`${sans.variable} ${serif.variable} ${emo.variable} ${mono.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
