//
//  UnifiedUsageCard.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI

struct UnifiedUsageCard: View {
    let usageData: DeviceUsageData
    
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("All Devices")
                .font(.headline)
                .fontWeight(.semibold)
            
            VStack(spacing: 4) {
                Text(usageData.totalTime)
                    .font(.largeTitle)
                    .fontWeight(.bold)
                    .foregroundColor(.blue)
                
                Text("Total screen time today")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity)
            
            CategoryBreakdownView(categories: usageData.categories)
            QuickStatsView(mostUsedCategory: usageData.mostUsedCategory)
        }
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(12)
    }
}
