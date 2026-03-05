import SwiftUI
import UIKit
import Foundation

class UsageTrackingService: ObservableObject {
    @Published var currentDayUsage: DeviceUsageData
    @Published var isSubmitting = false
    @Published var lastSubmissionTime: Date?
    @Published var errorMessage: String?

    private let baseURL = Config.apiBaseURL
    private let userId: String
    private var submissionTimer: Timer?

    private let categoryColors = Config.categoryColors

    init() {
        self.userId = SecureStorage.shared.deviceId

        self.currentDayUsage = DeviceUsageData(
            totalTime: "0m",
            categories: [],
            mostUsedCategory: CategoryUsage(category: "None", totalTime: 0, color: "gray")
        )

        startPeriodicSubmission()

        Task {
            await loadTodaysUsage()
        }
    }

    deinit {
        submissionTimer?.invalidate()
    }

    private func startPeriodicSubmission() {
        submissionTimer = Timer.scheduledTimer(withTimeInterval: 1800, repeats: true) { _ in
            Task {
                await self.submitCurrentUsage()
            }
        }
    }

    // MARK: - Read Usage from App Group (written by DeviceActivityReport extension)

    private func getUsageFromAppGroup() -> [String: Int]? {
        return SharedUsageData.readCategorySummary()
    }

    // MARK: - Submit Usage Data
    func submitCurrentUsage() async {
        await MainActor.run {
            isSubmitting = true
            errorMessage = nil
        }

        guard let categorySummary = getUsageFromAppGroup(),
              !categorySummary.isEmpty else {
            await MainActor.run { isSubmitting = false }
            return
        }

        let success = await submitCategorySummary(categorySummary)

        if success {
            await MainActor.run {
                self.lastSubmissionTime = Date()
            }
            await loadTodaysUsage()
        }

        await MainActor.run {
            isSubmitting = false
        }
    }

    private func submitCategorySummary(_ categorySummary: [String: Int]) async -> Bool {
        do {
            guard let url = URL(string: "\(baseURL)/submit-category-summary") else {
                throw URLError(.badURL)
            }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            SecureStorage.shared.addAuthHeader(to: &request)

            let timestamp = ISO8601DateFormatter().string(from: Date())
            let requestBody = SubmitCategorySummaryRequest(
                timestamp: timestamp,
                categorySummary: categorySummary,
                userId: userId
            )

            let jsonData = try JSONEncoder().encode(requestBody)
            request.httpBody = jsonData

            let (data, _) = try await URLSession.shared.data(for: request)
            let apiResponse = try JSONDecoder().decode(SubmitCategorySummaryResponse.self, from: data)
            return apiResponse.status == "success"

        } catch {
            return false
        }
    }

    // MARK: - Load Today's Usage
    func loadTodaysUsage() async {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd"
        let today = dateFormatter.string(from: Date())

        do {
            guard let url = URL(string: "\(baseURL)/get-summary-history?day=\(today)&userId=\(userId)") else {
                throw URLError(.badURL)
            }

            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            SecureStorage.shared.addAuthHeader(to: &request)

            let (data, _) = try await URLSession.shared.data(for: request)
            let summaryHistory = try JSONDecoder().decode([GetSummaryHistoryResponse].self, from: data)

            await MainActor.run {
                if let latestSummary = summaryHistory.first,
                   let summary = latestSummary.summary {
                    self.updateUsageData(from: summary)
                }
                // No mock data fallback - show empty state if no data
            }

        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
        }
    }

    private func updateUsageData(from summary: [String: Int]) {
        let categories = summary.map { (category, seconds) in
            CategoryUsage(
                category: category,
                totalTime: TimeInterval(seconds),
                color: categoryColors[category] ?? "gray"
            )
        }.sorted { $0.totalTime > $1.totalTime }

        let totalSeconds = summary.values.reduce(0, +)
        let totalHours = totalSeconds / 3600
        let totalMinutes = (totalSeconds % 3600) / 60
        let totalTimeString = totalHours > 0 ? "\(totalHours)h \(totalMinutes)m" : "\(totalMinutes)m"

        let mostUsed = categories.first ?? CategoryUsage(category: "None", totalTime: 0, color: "gray")

        currentDayUsage = DeviceUsageData(
            totalTime: totalTimeString,
            categories: categories,
            mostUsedCategory: mostUsed
        )
    }

    // MARK: - Manual Refresh
    func refreshUsage() async {
        await submitCurrentUsage()
    }
}
