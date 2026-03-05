//
//  CodeDisplayView.swift
//  SurfSense
//
//  Created by Sushanth Tiruvaipati on 6/4/25.
//
import SwiftUI

struct CodeDisplayView: View {
    let code: String
    let service: DeviceTrackingService
    let clientId: String
    
    @State private var showCopiedAlert = false
    
    var body: some View {
        VStack(spacing: 16) {
            VStack(spacing: 8) {
                Text("Your sharing code:")
                    .font(.caption)
                    .foregroundColor(.secondary)
                
                Text(code)
                    .font(.largeTitle)
                    .fontDesign(.monospaced)
                    .fontWeight(.bold)
                    .foregroundColor(.blue)
                    .tracking(4)
                
                Text("Code expires in \(Config.linkingCodeExpiryMinutes) minutes")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            .padding()
            .background(Color(.systemGray6))
            .cornerRadius(12)
            
            HStack(spacing: 12) {
                Button("Copy Code") {
                    copyCodeToClipboard()
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                
                Button("New Code") {
                    generateNewCode()
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .disabled(service.isGeneratingCode)
            }
        }
        .alert("Code Copied!", isPresented: $showCopiedAlert) {
            Button("OK") { }
        } message: {
            Text("The sharing code has been copied to your clipboard.")
        }
    }
    
    private func copyCodeToClipboard() {
        UIPasteboard.general.string = code
        showCopiedAlert = true
        
        // Haptic feedback
        let impactFeedback = UIImpactFeedbackGenerator(style: .light)
        impactFeedback.impactOccurred()
    }
    
    private func generateNewCode() {
        service.resetCode()
        Task {
            await service.initiateLinking(clientId: clientId)
        }
    }
}
