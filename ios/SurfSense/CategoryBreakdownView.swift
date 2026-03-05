//
//  CategoryBreakdownView.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI

struct CategoryBreakdownView: View {
    let categories: [CategoryUsage]
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Category Breakdown")
                .font(.headline)
                .fontWeight(.semibold)
            
            VStack(spacing: 8) {
                ForEach(categories) { categoryUsage in
                    HStack {
                        Circle()
                            .fill(colorForCategory(categoryUsage.color))
                            .frame(width: 12, height: 12)
                        
                        Text(categoryUsage.category)
                            .font(.subheadline)
                            .fontWeight(.medium)
                        
                        Spacer()
                        
                        Text(categoryUsage.formattedTime)
                            .font(.subheadline)
                            .fontWeight(.semibold)
                    }
                }
            }
        }
    }
    
    private func colorForCategory(_ colorName: String) -> Color {
        switch colorName.lowercased() {
        case "blue": return .blue
        case "green": return .green
        case "purple": return .purple
        case "orange": return .orange
        default: return .gray
        }
    }
}
