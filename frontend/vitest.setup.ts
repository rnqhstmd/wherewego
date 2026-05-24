import "@testing-library/jest-dom/vitest";

/**
 * jsdom 은 window.matchMedia 를 제공하지 않는다.
 * useMediaQuery 훅이나 VisitToast 처럼 매체 쿼리를 mount 효과에서 평가하는
 * 컴포넌트의 단위 테스트가 동작할 수 있도록 기본 polyfill 을 등록한다.
 * 기본값: matches=false (데스크탑 가정). 개별 테스트에서 필요 시 vi.spyOn 으로 덮어쓸 수 있다.
 */
if (typeof window !== "undefined" && typeof window.matchMedia !== "function") {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}
