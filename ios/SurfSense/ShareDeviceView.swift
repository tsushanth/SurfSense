import SwiftUI
import UIKit

struct ShareDeviceView: View {
    @StateObject private var service = DeviceTrackingService()
    @Environment(\.dismiss) private var dismiss
    
    
    private let currentClientId = SecureStorage.shared.deviceId

    
    var body: some View {
        VStack(spacing: 24) {
            // Header Section
            VStack(spacing: 16) {
                Image(systemName: "iphone")
                    .font(.system(size: 48))
                    .foregroundColor(.blue)
                    .frame(width: 80, height: 80)
                    .background(Color.blue.opacity(0.1))
                    .clipShape(Circle())
                
                Text("Allow others to track this device")
                    .font(.title2)
                    .fontWeight(.semibold)
                    .multilineTextAlignment(.center)
                
                Text("Generate a code that others can use to link and monitor this device's usage")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding()
            .background(Color(.systemBackground))
            .cornerRadius(12)
            
            // Code Generation/Display Section
            if let code = service.generatedCode {
                CodeDisplayView(code: code, service: service, clientId: currentClientId)
            } else {
                Button(action: generateCode) {
                    HStack {
                        if service.isGeneratingCode {
                            ProgressView()
                                .scaleEffect(0.8)
                                .foregroundColor(.white)
                        }
                        Text(service.isGeneratingCode ? "Generating Code..." : "Generate Sharing Code")
                    }
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(service.isGeneratingCode ? Color.gray : Color.blue)
                    .cornerRadius(12)
                }
                .disabled(service.isGeneratingCode)
            }
            
            // Error Message
            if let error = service.errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.red)
                    .multilineTextAlignment(.center)
                    .padding()
                    .background(Color.red.opacity(0.1))
                    .cornerRadius(8)
            }
            
            // Privacy Notice
            PrivacyNoticeCard()
            
            Spacer()
        }
        .padding()
        .navigationTitle("Share This Device")
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(.systemGroupedBackground))
    }
    
    private func generateCode() {
        Task {
            await service.initiateLinking(clientId: currentClientId)
        }
    }
}
