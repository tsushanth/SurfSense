import FamilyControls
import SwiftUI
import os

private let logger = Logger(subsystem: "com.kreativekoala.SurfSense", category: "ScreenTime")

/// Manages FamilyControls authorization for Screen Time data access
class ScreenTimeService: ObservableObject {
    static let shared = ScreenTimeService()

    @Published var authorizationStatus: AuthStatus = .notDetermined
    @Published var lastAuthError: String?

    enum AuthStatus {
        case notDetermined
        case approved
        case denied
    }

    private let authCenter = AuthorizationCenter.shared

    private init() {
        checkAuthorizationStatus()
    }

    /// Request FamilyControls authorization (.individual requires Face ID/passcode)
    func requestAuthorization() async {
        do {
            try await authCenter.requestAuthorization(for: .individual)
            await MainActor.run {
                self.authorizationStatus = .approved
                self.lastAuthError = nil
            }
            logger.info("FamilyControls authorization granted")
        } catch {
            let errorDetail = "\(error)"
            await MainActor.run {
                self.authorizationStatus = .denied
                self.lastAuthError = errorDetail
            }
            logger.error("FamilyControls authorization failed: \(errorDetail)")
        }
    }

    /// Check current authorization status
    func checkAuthorizationStatus() {
        switch authCenter.authorizationStatus {
        case .approved:
            authorizationStatus = .approved
        case .denied:
            authorizationStatus = .denied
        case .notDetermined:
            authorizationStatus = .notDetermined
        @unknown default:
            authorizationStatus = .notDetermined
        }
    }
}
