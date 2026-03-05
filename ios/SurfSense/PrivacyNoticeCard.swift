//
//  PrivacyNoticeCard.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI
import Foundation

struct PrivacyNoticeCard: View {
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(.orange)
                .font(.headline)
            
            VStack(alignment: .leading, spacing: 4) {
                Text("Privacy Notice")
                    .font(.headline)
                    .fontWeight(.semibold)
                
                Text("Sharing this code will allow others to view your device usage data. Only share with trusted individuals.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .background(Color.orange.opacity(0.1))
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.orange.opacity(0.3), lineWidth: 1)
        )
    }
}
