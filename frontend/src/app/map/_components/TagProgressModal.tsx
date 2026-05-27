"use client";

import { useEffect, useRef } from "react";

import type { PinTag } from "@/lib/api/types";
import { colors, fonts } from "@/lib/design/tokens";

interface TagProgressModalProps {
  isOpen: boolean;
  currentTag: PinTag;
  wantCount: number;
  onClose: () => void;
}

type Stage = "REEL" | "INTEREST" | "WISH" | "MEMORY";

interface StageMeta {
  key: Stage;
  glyph: string;
  label: string;
  description: string;
  /** 인라인 배경 hex 코드 — globals.css `--color-pin-*` 와 1:1 매칭. */
  color: string;
}

/**
 * 4단계 다이어그램 메타.
 *
 * 발견(REEL) → 관심(INTEREST) → 위시(WISH) → 추억(MEMORY)
 *
 * 설계 §9.4 + PRD 시나리오 6:
 *  "발견 핀에서 그룹원이 모두 '가고 싶어요'를 누르면 위시로 바뀌어요!"
 */
const STAGES: ReadonlyArray<StageMeta> = [
  {
    key: "REEL",
    glyph: "●",
    label: "발견",
    description: "릴스나 직접 추가로 새롭게 등장한 곳",
    color: colors.pinReel,
  },
  {
    key: "INTEREST",
    glyph: "●",
    label: "관심",
    description: "누군가 가고 싶어요를 누른 발견 핀",
    color: colors.pinInterest,
  },
  {
    key: "WISH",
    glyph: "★",
    label: "위시",
    description: "그룹원 과반이 가고 싶어해 위시로 승급",
    color: colors.pinWish,
  },
  {
    key: "MEMORY",
    glyph: "♥",
    label: "추억",
    description: "방문 후 추억으로 기록된 곳",
    color: colors.pinMemory,
  },
];

/**
 * 현재 핀이 다이어그램의 어느 단계에 있는지 결정한다.
 *  - tag=MEMORY              → MEMORY
 *  - tag=WISH                → WISH
 *  - tag=REEL & wantCount>=1 → INTEREST
 *  - tag=REEL & wantCount==0 → REEL
 */
function determineCurrentStage(tag: PinTag, wantCount: number): Stage {
  if (tag === "MEMORY") return "MEMORY";
  if (tag === "WISH") return "WISH";
  if (wantCount >= 1) return "INTEREST";
  return "REEL";
}

/**
 * Phase 12 (FR-PIN-12-28): 핀 태그 진행 다이어그램 모달.
 *
 * PinPopup의 `?` 아이콘 클릭으로 노출된다. 4단계(발견 → 관심 → 위시 → 추억) 카드를
 * 가로로 배치하고 현재 핀의 위치를 강조한다.
 *
 * 모달 베이스는 `PinDeleteConfirm` 의 native `<dialog>` 패턴을 답습한다
 * (Esc/backdrop click 동작은 dialog 기본 처리에 위임 + onClose 콜백).
 */
export default function TagProgressModal({
  isOpen,
  currentTag,
  wantCount,
  onClose,
}: TagProgressModalProps) {
  const dialogRef = useRef<HTMLDialogElement | null>(null);

  useEffect(() => {
    const node = dialogRef.current;
    if (!node) return;
    if (isOpen && !node.open) {
      node.showModal();
    }
    // PinDeleteConfirm 동일 — Strict Mode dev cycle race 방지 위해
    // cleanup 에서 close 를 호출하지 않는다. dialog 닫힘은 React unmount + isOpen prop 로 일어난다.
  }, [isOpen]);

  if (!isOpen) return null;

  const currentStage = determineCurrentStage(currentTag, wantCount);

  return (
    <dialog
      ref={dialogRef}
      onClose={onClose}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      // backdrop 클릭 닫기: dialog 자체에 클릭 → target === dialog 이면 backdrop.
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
      className="m-auto w-full max-w-md rounded-2xl border border-zinc-200 bg-white p-0 text-zinc-900 shadow-xl backdrop:bg-black/40"
    >
      <div
        style={{
          padding: "20px 22px 18px",
          fontFamily: fonts.sans,
        }}
      >
        <header
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "flex-start",
            gap: 12,
            marginBottom: 14,
          }}
        >
          <div>
            <h2
              style={{
                fontFamily: fonts.serif,
                fontSize: 16,
                fontWeight: 700,
                color: colors.ink,
                margin: 0,
                lineHeight: 1.4,
              }}
            >
              핀은 이렇게 자라요
            </h2>
            <p
              style={{
                fontSize: 12,
                color: colors.inkSoft,
                margin: "4px 0 0",
                lineHeight: 1.5,
              }}
            >
              그룹원이 모두 가고 싶어하면 위시로, 방문 후엔 추억으로 옮겨가요.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            style={{
              background: "transparent",
              border: "none",
              cursor: "pointer",
              color: colors.inkSoft,
              fontSize: 20,
              padding: 0,
              lineHeight: 1,
            }}
          >
            ×
          </button>
        </header>

        <ol
          style={{
            listStyle: "none",
            margin: 0,
            padding: 0,
            display: "flex",
            alignItems: "stretch",
            gap: 6,
          }}
        >
          {STAGES.map((stage, idx) => {
            const isCurrent = stage.key === currentStage;
            return (
              <li
                key={stage.key}
                style={{
                  flex: 1,
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "center",
                  textAlign: "center",
                  position: "relative",
                }}
              >
                <div
                  aria-current={isCurrent ? "step" : undefined}
                  style={{
                    width: 40,
                    height: 40,
                    borderRadius: "50%",
                    background: isCurrent ? stage.color : `${stage.color}33`,
                    color: isCurrent ? "#FFFFFF" : stage.color,
                    border: isCurrent
                      ? `2px solid ${stage.color}`
                      : `1px solid ${colors.hairline}`,
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    fontSize: 18,
                    fontWeight: 700,
                    boxShadow: isCurrent
                      ? `0 4px 12px ${colors.shadowMd}`
                      : "none",
                    transition: "transform 120ms ease",
                    transform: isCurrent ? "scale(1.05)" : "scale(1)",
                  }}
                >
                  {stage.glyph}
                </div>
                <div
                  style={{
                    marginTop: 8,
                    fontSize: 12,
                    fontWeight: isCurrent ? 700 : 500,
                    color: isCurrent ? colors.ink : colors.inkSoft,
                  }}
                >
                  {stage.label}
                </div>
                <div
                  style={{
                    marginTop: 4,
                    fontSize: 10.5,
                    lineHeight: 1.45,
                    color: colors.inkSoft,
                  }}
                >
                  {stage.description}
                </div>
                {idx < STAGES.length - 1 && (
                  <span
                    aria-hidden="true"
                    style={{
                      position: "absolute",
                      top: 18,
                      right: -8,
                      color: colors.inkFaint,
                      fontSize: 14,
                      fontWeight: 600,
                      pointerEvents: "none",
                    }}
                  >
                    →
                  </span>
                )}
              </li>
            );
          })}
        </ol>

        <footer
          style={{
            marginTop: 16,
            paddingTop: 12,
            borderTop: `1px solid ${colors.hairline}`,
            display: "flex",
            justifyContent: "flex-end",
          }}
        >
          <button
            type="button"
            onClick={onClose}
            style={{
              padding: "8px 18px",
              borderRadius: 999,
              border: "none",
              background: colors.cta,
              color: "#FFFFFF",
              fontFamily: fonts.sans,
              fontSize: 13,
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            확인
          </button>
        </footer>
      </div>
    </dialog>
  );
}
