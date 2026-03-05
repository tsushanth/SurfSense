import SwiftUI

struct ReportView: View {

    struct Configuration {
        let categorySummary: [String: Int]
        var debugInfo: String = ""

        var totalSeconds: Int {
            categorySummary.values.reduce(0, +)
        }

        var formattedTotal: String {
            let hours = totalSeconds / 3600
            let minutes = (totalSeconds % 3600) / 60
            if hours > 0 && minutes > 0 {
                return "\(hours)h \(minutes)m"
            } else if hours > 0 {
                return "\(hours)h"
            }
            return "\(minutes)m"
        }

        var sortedCategories: [(name: String, seconds: Int)] {
            categorySummary
                .sorted { $0.value > $1.value }
                .map { (name: $0.key, seconds: $0.value) }
        }
    }

    let configuration: Configuration

    var body: some View {
        if configuration.totalSeconds == 0 {
            VStack(spacing: 12) {
                Image(systemName: "clock")
                    .font(.system(size: 32))
                    .foregroundColor(.secondary)

                Text("No usage data yet")
                    .font(.subheadline)
                    .foregroundColor(.secondary)

                if !configuration.debugInfo.isEmpty {
                    Text(configuration.debugInfo)
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 12)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
        } else {
            VStack(alignment: .leading, spacing: 16) {
                // Total time header
                VStack(alignment: .leading, spacing: 4) {
                    Text(configuration.formattedTotal)
                        .font(.system(size: 36, weight: .bold))

                    Text("Screen Time Today")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }

                Divider()

                // Category breakdown
                ForEach(configuration.sortedCategories, id: \.name) { item in
                    HStack {
                        Circle()
                            .fill(colorForCategory(item.name))
                            .frame(width: 10, height: 10)

                        Text(item.name)
                            .font(.subheadline)

                        Spacer()

                        Text(formatSeconds(item.seconds))
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding()
            .background(Color(.secondarySystemGroupedBackground))
            .cornerRadius(12)
        }
    }

    private func formatSeconds(_ seconds: Int) -> String {
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        if hours > 0 && minutes > 0 {
            return "\(hours)h \(minutes)m"
        } else if hours > 0 {
            return "\(hours)h"
        }
        return "\(minutes)m"
    }

    private func colorForCategory(_ category: String) -> Color {
        switch category {
        case "Social Media": return .blue
        case "Work/Productivity": return .green
        case "Entertainment": return .purple
        case "News": return .orange
        case "Education": return .cyan
        case "Finance": return .green
        case "Health": return .pink
        case "Shopping": return .yellow
        case "Travel": return .indigo
        case "Food": return .red
        default: return .gray
        }
    }
}
