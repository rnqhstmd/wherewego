/**
 * 백엔드 ApiResponse 공통 래퍼 (`com.wherewego.interfaces.api.ApiResponse`)와 1:1 대응.
 */

export type PinTag = "PLACE" | "MEMORY";

export type MemoSource = "AUTO" | "MANUAL";

export interface ApiMeta {
  result: "SUCCESS" | "FAIL";
  errorCode?: string;
  message?: string;
}

export interface ApiResponse<T> {
  meta: ApiMeta;
  data?: T;
}

export interface PinSummaryResponse {
  id: number;
  groupId: number;
  createdBy: number;
  placeName: string;
  address: string | null;
  latitude: number;
  longitude: number;
  instagramUrl: string | null;
  memo: string | null;
  memoSource: MemoSource | null;
  tag: PinTag;
  createdAt: string;
}

export interface PinListResponse {
  items: PinSummaryResponse[];
}

export interface ActiveGroupResponse {
  groupId: number;
  name: string;
  memberCount: number;
  role: string;
  joinedAt: string;
}
