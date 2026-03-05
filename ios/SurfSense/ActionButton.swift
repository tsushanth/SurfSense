//
//  ActionButton.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI

struct ActionButton: View {
    let title: String
    let icon: String
    let style: ButtonStyle
    
    enum ButtonStyle {
        case primary, secondary
    }
    
    var body: some View {
        HStack {
            Image(systemName: icon)
            Text(title)
        }
        .font(.headline)
        .foregroundColor(style == .primary ? .white : .primary)
        .frame(maxWidth: .infinity)
        .padding()
        .background(style == .primary ? Color.blue : Color(.systemGray6))
        .cornerRadius(12)
    }
}
