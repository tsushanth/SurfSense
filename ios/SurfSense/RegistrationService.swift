import Foundation
import UIKit
import os

private let logger = Logger(subsystem: "com.kreativekoala.SurfSense", category: "Registration")

/// Handles device registration with the backend and API key storage.
/// Must be called on app launch before any authenticated API calls.
class RegistrationService {
    static let shared = RegistrationService()

    private init() {}

    /// Register this device with the backend. Stores the API key on first registration.
    /// Safe to call multiple times — only registers once, and is a no-op if already registered.
    func registerIfNeeded() async {
        // Already have an API key — nothing to do
        if SecureStorage.shared.apiKey != nil {
            logger.debug("Already registered, skipping")
            return
        }

        let deviceId = SecureStorage.shared.deviceId
        let deviceName = UIDevice.current.name

        guard let url = URL(string: "\(Config.apiBaseURL)/register-client") else {
            logger.error("Invalid registration URL")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body: [String: String] = [
            "clientId": deviceId,
            "clientType": "IOS",
            "clientName": deviceName
        ]

        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)

            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                logger.error("Registration failed: no HTTP response")
                return
            }

            let responseBody = String(data: data, encoding: .utf8) ?? "<unreadable>"

            guard (200...299).contains(httpResponse.statusCode) else {
                logger.error("Registration failed: HTTP \(httpResponse.statusCode) — \(responseBody)")
                return
            }

            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
               let apiKey = json["apiKey"] as? String {
                SecureStorage.shared.apiKey = apiKey
                logger.info("Device registered and API key stored")
            } else {
                logger.warning("Registration returned 200 but no apiKey in response: \(responseBody)")
            }
        } catch {
            logger.error("Registration request failed: \(error.localizedDescription)")
        }
    }
}
