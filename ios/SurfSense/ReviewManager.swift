import Foundation
import StoreKit
import SwiftUI

@MainActor
final class ReviewManager: ObservableObject {
    static let shared = ReviewManager()

    @Published var showReviewPrompt = false

    private let userDefaults = UserDefaults.standard
    private let minimumLaunchCount = 3
    private let minimumSuccessfulActions = 1

    private enum Keys {
        static let launchCount = "app_launch_count"
        static let lastReviewRequestDate = "last_review_request_date"
        static let successfulActionsCount = "successful_actions_count"
    }

    private init() {}

    func recordAppLaunch() {
        let currentCount = userDefaults.integer(forKey: Keys.launchCount)
        userDefaults.set(currentCount + 1, forKey: Keys.launchCount)
    }

    func recordSuccessfulAction() {
        let currentCount = userDefaults.integer(forKey: Keys.successfulActionsCount)
        userDefaults.set(currentCount + 1, forKey: Keys.successfulActionsCount)
        if shouldRequestReview() {
            showReviewPrompt = true
        }
    }

    func handlePositiveResponse() {
        showReviewPrompt = false
        guard let scene = UIApplication.shared.connectedScenes
            .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene else { return }
        SKStoreReviewController.requestReview(in: scene)
        userDefaults.set(Date(), forKey: Keys.lastReviewRequestDate)
    }

    func handleNegativeResponse() {
        showReviewPrompt = false
        userDefaults.set(Date(), forKey: Keys.lastReviewRequestDate)
        let subject = "Feedback for \(ReviewManager.appName)".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        if let url = URL(string: "mailto:support@kreativekoala.llc?subject=\(subject)") {
            UIApplication.shared.open(url)
        }
    }

    func handleDismiss() {
        showReviewPrompt = false
    }

    private func shouldRequestReview() -> Bool {
        let successfulActions = userDefaults.integer(forKey: Keys.successfulActionsCount)
        guard successfulActions >= minimumSuccessfulActions else { return false }
        let launchCount = userDefaults.integer(forKey: Keys.launchCount)
        guard launchCount >= minimumLaunchCount else { return false }
        if let lastRequestDate = userDefaults.object(forKey: Keys.lastReviewRequestDate) as? Date {
            let daysSinceLastRequest = Calendar.current.dateComponents([.day], from: lastRequestDate, to: Date()).day ?? 0
            guard daysSinceLastRequest >= 90 else { return false }
        }
        return true
    }

    static var appName: String {
        Bundle.main.infoDictionary?["CFBundleDisplayName"] as? String
            ?? Bundle.main.infoDictionary?["CFBundleName"] as? String
            ?? "this app"
    }
}

struct ReviewPromptModifier: ViewModifier {
    @ObservedObject var reviewManager = ReviewManager.shared

    func body(content: Content) -> some View {
        content
            .alert("Enjoying \(ReviewManager.appName)?", isPresented: $reviewManager.showReviewPrompt) {
                Button("Yes, I love it!") { reviewManager.handlePositiveResponse() }
                Button("Not really") { reviewManager.handleNegativeResponse() }
                Button("Ask me later", role: .cancel) { reviewManager.handleDismiss() }
            } message: {
                Text("Your feedback helps us improve!")
            }
    }
}

extension View {
    func reviewPrompt() -> some View {
        modifier(ReviewPromptModifier())
    }
}
