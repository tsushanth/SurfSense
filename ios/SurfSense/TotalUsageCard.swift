//
//  TotalUsageCard.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/3/25.
//
import SwiftUI

struct TotalUsageCard: View {
    let totalTime: TimeInterval
    
    var body: some View {
        VStack {
            Text("Total Screen Time")
                .font(.headline)
                .foregroundColor(.secondary)
            
            Text(formatTime(totalTime))
                .font(.largeTitle)
                .fontWeight(.bold)
                .foregroundColor(.primary)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }
    
    private func formatTime(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = Int(time) % 3600 / 60
        return "\(hours)h \(minutes)m"
    }
}

struct CategoryBreakdownCard: View {
    let categories: [CategoryUsage]
    
    var body: some View {
        VStack(alignment: .leading) {
            Text("Category Breakdown")
                .font(.headline)
                .padding(.bottom, 10)
            
            ForEach(categories) { category in
                HStack {
                    Circle()
                        .fill(colorForCategory(category.color))
                        .frame(width: 12, height: 12)
                    
                    Text(category.category)
                        .font(.body)
                    
                    Spacer()
                    
                    Text(category.formattedTime)
                        .font(.body)
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }
    
    private func colorForCategory(_ colorName: String) -> Color {
        switch colorName {
        case "blue": return .blue
        case "green": return .green
        case "purple": return .purple
        case "orange": return .orange
        default: return .gray
        }
    }
}

struct QuickStatsCard: View {
    let categories: [CategoryUsage]
    
    var body: some View {
        VStack(alignment: .leading) {
            Text("Quick Stats")
                .font(.headline)
                .padding(.bottom, 10)
            
            if let mostUsed = categories.max(by: { $0.totalTime < $1.totalTime }) {
                HStack {
                    Image(systemName: "star.fill")
                        .foregroundColor(.yellow)
                    Text("Most used: \(mostUsed.category)")
                    Spacer()
                    Text(mostUsed.formattedTime)
                        .foregroundColor(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }
}
