import SwiftUI
import Charts

struct TrendsView: View {
    @StateObject private var linkedDevicesService = LinkedDevicesService()
    @State private var selectedPeriod: TimePeriod = .week
    @State private var usageHistory: [LocalStorage.DailyUsage] = []

    enum TimePeriod: String, CaseIterable {
        case week = "7 Days"
        case twoWeeks = "14 Days"
        case month = "30 Days"

        var days: Int {
            switch self {
            case .week: return 7
            case .twoWeeks: return 14
            case .month: return 30
            }
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Period Selector
                Picker("Time Period", selection: $selectedPeriod) {
                    ForEach(TimePeriod.allCases, id: \.self) { period in
                        Text(period.rawValue).tag(period)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .onChange(of: selectedPeriod) { _ in
                    loadHistory()
                }

                if usageHistory.isEmpty {
                    emptyStateView
                } else {
                    // Summary Card
                    summaryCard

                    // Daily Usage Chart
                    dailyUsageChart

                    // Category Breakdown
                    categoryBreakdownChart

                    // Daily Details List
                    dailyDetailsList
                }
            }
            .padding()
        }
        .navigationTitle("Usage Trends")
        .onAppear {
            loadHistory()
        }
    }

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Image(systemName: "chart.line.uptrend.xyaxis")
                .font(.system(size: 60))
                .foregroundColor(.gray)

            Text("No Usage Data Yet")
                .font(.headline)

            Text("Start tracking your usage to see trends over time.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 60)
    }

    private var summaryCard: some View {
        VStack(spacing: 12) {
            HStack {
                Text("Summary")
                    .font(.headline)
                Spacer()
                Text(selectedPeriod.rawValue)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }

            HStack(spacing: 20) {
                StatBox(
                    title: "Total",
                    value: formatMinutes(totalMinutes),
                    icon: "clock.fill",
                    color: .blue
                )

                StatBox(
                    title: "Daily Avg",
                    value: formatMinutes(averageMinutes),
                    icon: "chart.bar.fill",
                    color: .green
                )

                StatBox(
                    title: "Peak Day",
                    value: formatMinutes(peakMinutes),
                    icon: "arrow.up.circle.fill",
                    color: .orange
                )
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }

    @ViewBuilder
    private var dailyUsageChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Daily Usage")
                .font(.headline)

            if #available(iOS 16.0, *) {
                Chart(usageHistory.reversed(), id: \.date) { day in
                    BarMark(
                        x: .value("Date", formatDateShort(day.date)),
                        y: .value("Minutes", day.totalMinutes)
                    )
                    .foregroundStyle(Color.blue.gradient)
                }
                .frame(height: 200)
                .chartYAxisLabel("Minutes")
            } else {
                // Fallback for iOS 15
                SimpleBarChart(data: usageHistory.reversed().map { ($0.date, $0.totalMinutes) })
                    .frame(height: 200)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }

    @ViewBuilder
    private var categoryBreakdownChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Category Breakdown")
                .font(.headline)

            let categoryTotals = aggregateCategoryTotals()

            ForEach(categoryTotals.sorted(by: { $0.value > $1.value }), id: \.key) { category, minutes in
                HStack {
                    Circle()
                        .fill(colorForCategory(category))
                        .frame(width: 12, height: 12)

                    Text(category)
                        .font(.subheadline)

                    Spacer()

                    Text(formatMinutes(minutes))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }

                GeometryReader { geometry in
                    let maxMinutes = categoryTotals.values.max() ?? 1
                    let width = CGFloat(minutes) / CGFloat(maxMinutes) * geometry.size.width

                    RoundedRectangle(cornerRadius: 4)
                        .fill(colorForCategory(category))
                        .frame(width: max(width, 4), height: 8)
                }
                .frame(height: 8)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }

    private var dailyDetailsList: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Daily Details")
                .font(.headline)

            ForEach(usageHistory.prefix(7), id: \.date) { day in
                HStack {
                    VStack(alignment: .leading) {
                        Text(formatDateFull(day.date))
                            .font(.subheadline)
                            .fontWeight(.medium)

                        Text(topCategory(for: day))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }

                    Spacer()

                    Text(formatMinutes(day.totalMinutes))
                        .font(.headline)
                        .foregroundColor(.blue)
                }
                .padding(.vertical, 8)

                if day.date != usageHistory.first?.date {
                    Divider()
                }
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }

    // MARK: - Computed Properties

    private var totalMinutes: Int {
        usageHistory.reduce(0) { $0 + $1.totalMinutes }
    }

    private var averageMinutes: Int {
        guard !usageHistory.isEmpty else { return 0 }
        return totalMinutes / usageHistory.count
    }

    private var peakMinutes: Int {
        usageHistory.map { $0.totalMinutes }.max() ?? 0
    }

    // MARK: - Helper Methods

    private func loadHistory() {
        usageHistory = linkedDevicesService.getUsageHistory(days: selectedPeriod.days)
    }

    private func aggregateCategoryTotals() -> [String: Int] {
        var totals: [String: Int] = [:]
        for day in usageHistory {
            for (category, minutes) in day.byCategory {
                totals[category, default: 0] += minutes
            }
        }
        return totals
    }

    private func topCategory(for day: LocalStorage.DailyUsage) -> String {
        if let top = day.byCategory.max(by: { $0.value < $1.value }) {
            return "\(top.key): \(formatMinutes(top.value))"
        }
        return "No data"
    }

    private func formatMinutes(_ minutes: Int) -> String {
        if minutes >= 60 {
            let hours = minutes / 60
            let mins = minutes % 60
            return mins > 0 ? "\(hours)h \(mins)m" : "\(hours)h"
        }
        return "\(minutes)m"
    }

    private func formatDateShort(_ dateString: String) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        guard let date = formatter.date(from: dateString) else { return dateString }

        formatter.dateFormat = "MM/dd"
        return formatter.string(from: date)
    }

    private func formatDateFull(_ dateString: String) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        guard let date = formatter.date(from: dateString) else { return dateString }

        formatter.dateFormat = "EEEE, MMM d"
        return formatter.string(from: date)
    }

    private func colorForCategory(_ category: String) -> Color {
        switch category.lowercased() {
        case "social": return .blue
        case "productivity": return .green
        case "entertainment": return .purple
        case "news": return .orange
        default: return .gray
        }
    }
}

// MARK: - Supporting Views

struct StatBox: View {
    let title: String
    let value: String
    let icon: String
    let color: Color

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(color)

            Text(value)
                .font(.headline)

            Text(title)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Color(.systemBackground))
        .cornerRadius(8)
    }
}

// Fallback chart for iOS 15
struct SimpleBarChart: View {
    let data: [(String, Int)]

    var body: some View {
        GeometryReader { geometry in
            let maxValue = data.map { $0.1 }.max() ?? 1
            let barWidth = (geometry.size.width - CGFloat(data.count - 1) * 4) / CGFloat(data.count)

            HStack(alignment: .bottom, spacing: 4) {
                ForEach(data, id: \.0) { item in
                    VStack {
                        Spacer()
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color.blue)
                            .frame(
                                width: barWidth,
                                height: CGFloat(item.1) / CGFloat(maxValue) * (geometry.size.height - 20)
                            )

                        Text(formatDateLabel(item.0))
                            .font(.system(size: 8))
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                }
            }
        }
    }

    private func formatDateLabel(_ dateString: String) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        guard let date = formatter.date(from: dateString) else { return "" }
        formatter.dateFormat = "d"
        return formatter.string(from: date)
    }
}

#Preview {
    NavigationView {
        TrendsView()
    }
}
