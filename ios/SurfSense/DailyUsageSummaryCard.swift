//
//  DailyUsageSummaryCard.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI

struct DailyUsageSummaryCard: View {
    let usageData: DeviceUsageData
    
    var body: some View {
        VStack(spacing: 20) {
            // Total Time Display
            VStack(spacing: 4) {
                Text(usageData.totalTime)
                    .font(.largeTitle)
                    .fontWeight(.bold)
                    .foregroundColor(.blue)
                
                Text("Total screen time today")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            // Category Breakdown
            CategoryBreakdownView(categories: usageData.categories)
            
            // Quick Stats
            QuickStatsView(mostUsedCategory: usageData.mostUsedCategory)
        }
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(12)
    }
}
