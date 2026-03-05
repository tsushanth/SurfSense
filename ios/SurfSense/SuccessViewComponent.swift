import SwiftUI
struct SuccessView: View {
    let linkedDeviceName: String
    let onDone: () -> Void
    let onAddAnother: () -> Void
    
    var body: some View {
        VStack(spacing: 24) {
            // Success Icon
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 80))
                .foregroundColor(.green)
            
            // Success Message
            VStack(spacing: 8) {
                Text("Device Linked!")
                    .font(.title2)
                    .fontWeight(.bold)
                
                Text("Successfully linked with")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                
                Text(linkedDeviceName)
                    .font(.headline)
                    .fontWeight(.semibold)
                    .foregroundColor(.blue)
            }
            
            // Action Buttons
            VStack(spacing: 12) {
                Button("Done") {
                    onDone()
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .padding(.horizontal)
                
                Button("Add Another Device") {
                    onAddAnother()
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                .padding(.horizontal)
            }
            
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}
