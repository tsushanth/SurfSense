//
//  QuickStatsView.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI

struct QuickStatsView: View {
    let mostUsedCategory: CategoryUsage
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Quick Stats")
                .font(.headline)
                .fontWeight(.semibold)
            
            HStack {
                Text("Most used category")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                
                Spacer()
                
                VStack(alignment: .trailing, spacing: 2) {
                    Text(mostUsedCategory.category)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                    
                    Text(mostUsedCategory.formattedTime)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
    }
}
