import SwiftUI

struct AddDeviceView: View {
    @StateObject private var service = AddDeviceService()
    @Environment(\.dismiss) private var dismiss
    @State private var deviceCode = ""
    @State private var showSuccessAlert = false
    
    private let currentClientId = SecureStorage.shared.deviceId
    
    var body: some View {
        VStack(spacing: 24) {
            if service.linkingSuccess {
                // Success State
                SuccessView(
                    linkedDeviceName: service.linkedDeviceName ?? "Unknown Device",
                    onDone: {
                        dismiss()
                    },
                    onAddAnother: {
                        service.reset()
                        deviceCode = ""
                    }
                )
            } else {
                // Input State
                VStack(spacing: 24) {
                    // Header Section
                    VStack(spacing: 16) {
                        Image(systemName: "link")
                            .font(.system(size: 48))
                            .foregroundColor(.blue)
                            .frame(width: 80, height: 80)
                            .background(Color.blue.opacity(0.1))
                            .clipShape(Circle())
                        
                        Text("Add Device")
                            .font(.title2)
                            .fontWeight(.semibold)
                        
                        Text("Enter the 6-digit code from the device you want to link")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding()
                    .background(Color(.systemBackground))
                    .cornerRadius(12)
                    
                    // Code Input Section
                    VStack(spacing: 16) {
                        Text("Device Code")
                            .font(.headline)
                            .fontWeight(.semibold)
                        
                        TextField("000000", text: $deviceCode)
                            .textFieldStyle(RoundedBorderTextFieldStyle())
                            .multilineTextAlignment(.center)
                            .font(.title)
                            .fontDesign(.monospaced)
                            .textCase(.uppercase)
                            .keyboardType(.asciiCapable)
                            .autocorrectionDisabled()
                            .onChange(of: deviceCode) { newValue in
                                // Only allow alphanumeric characters and limit to 6
                                let filtered = newValue.filter { $0.isLetter || $0.isNumber }
                                deviceCode = String(filtered.prefix(6)).uppercased()
                            }
                        
                        Button(action: linkDevice) {
                            HStack {
                                if service.isLinking {
                                    ProgressView()
                                        .scaleEffect(0.8)
                                        .foregroundColor(.white)
                                }
                                Text(service.isLinking ? "Linking Device..." : "Link Device")
                            }
                            .font(.headline)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(canLinkDevice ? Color.blue : Color.gray)
                            .cornerRadius(12)
                        }
                        .disabled(!canLinkDevice || service.isLinking)
                    }
                    .padding()
                    .background(Color(.systemBackground))
                    .cornerRadius(12)
                    
                    // Error Message
                    if let error = service.errorMessage {
                        ErrorView(message: error) {
                            service.reset()
                        }
                    }
                    
                    // Info Section
                    InfoCard()
                    
                    Spacer()
                }
            }
        }
        .padding()
        .navigationTitle("Add Device")
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(.systemGroupedBackground))
        .alert("Device Linked!", isPresented: .constant(service.linkingSuccess && !showSuccessAlert)) {
            Button("OK") {
                dismiss()
            }
        } message: {
            Text("Successfully linked with \(service.linkedDeviceName ?? "device")!")
        }
    }
    
    private var canLinkDevice: Bool {
        deviceCode.count == 6 && !service.isLinking
    }
    
    private func linkDevice() {
        // Validate code before making API call
        guard deviceCode.count == 6 else {
            return
        }
        
        Task {
            await service.completeDeviceLinking(clientId: currentClientId, code: deviceCode)
        }
    }
}
