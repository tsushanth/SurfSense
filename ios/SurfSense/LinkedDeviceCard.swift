//
//  LinkedDeviceCard.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI

struct LinkedDeviceCard: View {
    let device: LinkedDeviceViewModel
    let isUnlinking: Bool
    let onRemove: () -> Void
    
    var body: some View {
        HStack {
            // Device Info Section (Tappable)
            NavigationLink(destination: DeviceDetailView(device: device)) {
                HStack {
                    Image(systemName: device.type.icon)
                        .font(.title2)
                        .foregroundColor(.secondary)
                        .frame(width: 24)
                    
                    VStack(alignment: .leading, spacing: 2) {
                        Text(device.name)
                            .font(.headline)
                            .foregroundColor(.primary)
                        
                        Text("Last seen \(device.lastSeen)")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    Spacer()
                    
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(device.usageData.totalTime)
                            .font(.headline)
                            .fontWeight(.semibold)
                        
                        Text("View details")
                            .font(.caption)
                            .foregroundColor(.blue)
                    }
                }
            }
            .buttonStyle(PlainButtonStyle())
            
            // Remove Button Section (Non-tappable)
            Button(action: onRemove) {
                Image(systemName: "trash")
                    .font(.title3)
                    .foregroundColor(.red)
                    .frame(width: 24, height: 24)
            }
            .buttonStyle(PlainButtonStyle())
            .disabled(isUnlinking)
            .opacity(isUnlinking ? 0.5 : 1.0)
        }
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(12)
    }
}
