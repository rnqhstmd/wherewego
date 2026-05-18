import { describe, it, expect, beforeEach } from "vitest";
import { notifAsked, nicknameSet } from "../local-flags";

describe("notifAsked", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("초기 상태 get() → false", () => {
    expect(notifAsked.get()).toBe(false);
  });

  it("set(true) 후 get() → true", () => {
    notifAsked.set(true);
    expect(notifAsked.get()).toBe(true);
    expect(window.localStorage.getItem("maygo:notif-asked")).toBe("true");
  });

  it("set(false) 후 get() → false (localStorage에서 제거)", () => {
    notifAsked.set(true);
    expect(window.localStorage.getItem("maygo:notif-asked")).toBe("true");
    notifAsked.set(false);
    expect(notifAsked.get()).toBe(false);
    expect(window.localStorage.getItem("maygo:notif-asked")).toBeNull();
  });
});

describe("nicknameSet", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("초기 상태 get() → false", () => {
    expect(nicknameSet.get()).toBe(false);
  });

  it("set(true) 후 get() → true", () => {
    nicknameSet.set(true);
    expect(nicknameSet.get()).toBe(true);
    expect(window.localStorage.getItem("maygo:nickname-set")).toBe("true");
  });

  it("set(false) 후 get() → false (localStorage에서 제거)", () => {
    nicknameSet.set(true);
    expect(window.localStorage.getItem("maygo:nickname-set")).toBe("true");
    nicknameSet.set(false);
    expect(nicknameSet.get()).toBe(false);
    expect(window.localStorage.getItem("maygo:nickname-set")).toBeNull();
  });
});
