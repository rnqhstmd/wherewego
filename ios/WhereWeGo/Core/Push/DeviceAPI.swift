import Foundation

// 디바이스 푸시 토큰 등록/해제 API(설계 §8). 모든 호출은 APIClient 경유.
// 백엔드 계약(DeviceV1Controller/DeviceV1Dto)과 1:1 정합:
// - POST /devices: body { platform: "IOS", deviceToken }. platform 은 백엔드 DevicePlatform enum(IOS 만).
// - DELETE /devices/{deviceToken}: 204(빈 본문) 성공 — PinAPI.delete 와 동일하게 NO_CONTENT 흡수.

// MARK: - 요청 DTO

/// 디바이스 토큰 등록 요청(백엔드 DeviceV1Dto.RegisterDeviceRequest 대칭).
/// platform 은 enum 문자열("IOS")로 직렬화된다(현재 iOS 만 지원).
private struct RegisterDeviceRequest: Encodable {
    let platform: String
    let deviceToken: String
}

/// 디바이스 토큰 등록 응답(백엔드 DeviceV1Dto.RegisterDeviceResponse — Long → Int).
struct RegisterDeviceResponse: Decodable {
    let deviceId: Int
}

// MARK: - DeviceAPIProtocol

protocol DeviceAPIProtocol: Sendable {
    /// POST /devices (platform=IOS). 토큰 upsert — 서버가 재배정/갱신.
    func register(deviceToken: String) async throws
    /// DELETE /devices/{deviceToken} (204). 로그아웃/계정삭제 시 해제(FR-18).
    func unregister(deviceToken: String) async throws
}

// MARK: - DeviceAPI

final class DeviceAPI: DeviceAPIProtocol {

    /// 백엔드 DevicePlatform.IOS 와 정합하는 플랫폼 식별자.
    private static let platform = "IOS"

    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func register(deviceToken: String) async throws {
        let body = try JSONEncoder().encode(
            RegisterDeviceRequest(platform: Self.platform, deviceToken: deviceToken)
        )
        _ = try await client.request(
            "/devices",
            method: "POST",
            body: body,
            type: RegisterDeviceResponse.self
        )
    }

    func unregister(deviceToken: String) async throws {
        // DELETE 는 204(빈 본문) 정상 성공 — APIClient.decodeEnvelope 가 data 키 부재로 NO_CONTENT 를
        // throw 한다. 204 자체는 성공이므로 NO_CONTENT 만 흡수하고 나머지는 전파(PinAPI.delete 동치).
        do {
            _ = try await client.request(
                "/devices/\(deviceToken)",
                method: "DELETE",
                type: EmptyResponse.self
            )
        } catch let error as APIError where error.code == "NO_CONTENT" {
            return
        }
    }
}
