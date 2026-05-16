export function NoGroupGuide() {
  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col items-start gap-4 px-4 py-16">
      <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
        먼저 그룹에 가입해 주세요
      </h1>
      <p className="text-sm leading-7 text-zinc-600 dark:text-zinc-400">
        핀은 그룹 단위로 관리됩니다. 그룹을 만들거나 받은 초대를 수락한 후
        다시 시도해 주세요.
      </p>
      <div className="rounded-xl border border-dashed border-zinc-300 bg-zinc-50 px-5 py-4 text-sm text-zinc-600 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-300">
        그룹 가입 페이지는 곧 제공될 예정입니다. 임시로 챗봇을 통해 그룹을
        생성하거나 초대를 처리해 주세요.
      </div>
    </div>
  );
}
