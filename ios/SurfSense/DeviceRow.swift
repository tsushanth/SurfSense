//
//  DeviceRow.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/3/25.
//
import SwiftUI

struct DeviceRow: View {
    let device: LinkedDevice
    let onUnlink: () async -> Void
    
    var body: some View {
        HStack {
            Image(systemName: device.type.icon)
                .foregroundColor(device.name.isEmpty ? .green : .gray)
                .frame(width: 24)
            
            VStack(alignment: .leading) {
                Text(device.name)
                    .font(.headline)
                
                Text("Last sync: \(device.lastSeen)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            Button("Unlink") {
                Task {
                    await onUnlink()
                }
            }
            .foregroundColor(.red)
        }
        .padding(.vertical, 4)
    }
    
    private func formatDate(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
