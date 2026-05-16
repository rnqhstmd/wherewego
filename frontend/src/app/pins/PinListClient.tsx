"use client";

import { useMemo, useOptimistic, useState, useTransition } from "react";
import { useRouter } from "next/navigation";

import type { PinSummaryResponse } from "@/lib/api/types";

import { deletePinAction, updatePinAction } from "./actions";
import { EmptyState } from "./_components/EmptyState";
import { PinCard } from "./_components/PinCard";
import { PinDeleteConfirm } from "./_components/PinDeleteConfirm";
import { PinEditDialog, type PinEditPatch } from "./_components/PinEditDialog";
import { TagFilter, type TagFilterValue } from "./_components/TagFilter";

interface PinListClientProps {
  initialPins: PinSummaryResponse[];
  groupId: number;
  groupName: string;
}

type OptimisticAction =
  | { type: "patch"; pinId: number; patch: PinEditPatch }
  | { type: "replace"; pin: PinSummaryResponse }
  | { type: "remove"; pinId: number };

function applyPatch(
  pin: PinSummaryResponse,
  patch: PinEditPatch,
): PinSummaryResponse {
  const next: PinSummaryResponse = { ...pin };
  if (patch.tag !== undefined) {
    next.tag = patch.tag;
  }
  if (patch.memo !== undefined) {
    if (patch.memo === "") {
      next.memo = null;
      next.memoSource = null;
    } else {
      next.memo = patch.memo;
      next.memoSource = "MANUAL";
    }
  }
  return next;
}

function reducer(
  state: PinSummaryResponse[],
  action: OptimisticAction,
): PinSummaryResponse[] {
  switch (action.type) {
    case "patch":
      return state.map((pin) =>
        pin.id === action.pinId ? applyPatch(pin, action.patch) : pin,
      );
    case "replace":
      return state.map((pin) =>
        pin.id === action.pin.id ? action.pin : pin,
      );
    case "remove":
      return state.filter((pin) => pin.id !== action.pinId);
    default:
      return state;
  }
}

export function PinListClient({
  initialPins,
  groupId,
  groupName,
}: PinListClientProps) {
  const router = useRouter();
  const [filter, setFilter] = useState<TagFilterValue>("ALL");
  const [editingPin, setEditingPin] = useState<PinSummaryResponse | null>(null);
  const [deletingPin, setDeletingPin] = useState<PinSummaryResponse | null>(
    null,
  );
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const [optimisticPins, dispatch] = useOptimistic<
    PinSummaryResponse[],
    OptimisticAction
  >(initialPins, reducer);
  const [isPending, startTransition] = useTransition();

  const visiblePins = useMemo(() => {
    if (filter === "ALL") return optimisticPins;
    return optimisticPins.filter((pin) => pin.tag === filter);
  }, [optimisticPins, filter]);

  const placeCount = useMemo(
    () => optimisticPins.filter((pin) => pin.tag === "PLACE").length,
    [optimisticPins],
  );
  const memoryCount = useMemo(
    () => optimisticPins.filter((pin) => pin.tag === "MEMORY").length,
    [optimisticPins],
  );

  const handleEdit = (pin: PinSummaryResponse) => {
    setErrorMessage(null);
    setEditingPin(pin);
  };

  const handleAskDelete = (pin: PinSummaryResponse) => {
    setErrorMessage(null);
    setDeletingPin(pin);
  };

  const handleSave = (pinId: number, patch: PinEditPatch) => {
    setEditingPin(null);
    setErrorMessage(null);
    startTransition(async () => {
      dispatch({ type: "patch", pinId, patch });
      const result = await updatePinAction(groupId, pinId, patch);
      if (!result.ok) {
        setErrorMessage(result.message);
        router.refresh();
        return;
      }
      dispatch({ type: "replace", pin: result.data });
    });
  };

  const handleConfirmDelete = (pinId: number) => {
    setDeletingPin(null);
    setErrorMessage(null);
    startTransition(async () => {
      dispatch({ type: "remove", pinId });
      const result = await deletePinAction(groupId, pinId);
      if (!result.ok) {
        setErrorMessage(result.message);
        router.refresh();
      }
    });
  };

  return (
    <div className="mx-auto w-full max-w-3xl px-4 py-8">
      <header className="mb-6 flex flex-col gap-1">
        <p className="text-xs font-medium uppercase tracking-wider text-zinc-500 dark:text-zinc-400">
          {groupName}
        </p>
        <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
          핀 목록
        </h1>
      </header>

      <div className="mb-6 flex items-center justify-between gap-4">
        <TagFilter
          value={filter}
          onChange={setFilter}
          totalCount={optimisticPins.length}
          placeCount={placeCount}
          memoryCount={memoryCount}
        />
        {isPending ? (
          <span className="text-xs text-zinc-500 dark:text-zinc-400">
            저장 중...
          </span>
        ) : null}
      </div>

      {errorMessage ? (
        <div
          role="alert"
          className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900/60 dark:bg-red-950/40 dark:text-red-300"
        >
          {errorMessage}
        </div>
      ) : null}

      {visiblePins.length === 0 ? (
        <EmptyState filter={filter} hasPins={optimisticPins.length > 0} />
      ) : (
        <ul className="flex flex-col gap-3">
          {visiblePins.map((pin) => (
            <li key={pin.id}>
              <PinCard
                pin={pin}
                onEdit={handleEdit}
                onDelete={handleAskDelete}
                disabled={isPending}
              />
            </li>
          ))}
        </ul>
      )}

      {editingPin ? (
        <PinEditDialog
          pin={editingPin}
          onClose={() => setEditingPin(null)}
          onSave={(patch) => handleSave(editingPin.id, patch)}
        />
      ) : null}

      {deletingPin ? (
        <PinDeleteConfirm
          pin={deletingPin}
          onCancel={() => setDeletingPin(null)}
          onConfirm={() => handleConfirmDelete(deletingPin.id)}
        />
      ) : null}
    </div>
  );
}
