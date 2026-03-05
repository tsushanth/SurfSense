import SwiftUI
struct InfoCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "info.circle.fill")
                    .foregroundColor(.blue)
                
                Text("How to get a code")
                    .font(.headline)
                    .fontWeight(.semibold)
            }
            
            VStack(alignment: .leading, spacing: 8) {
                InfoStep(number: "1", text: "Open the app on the device you want to link")
                InfoStep(number: "2", text: "Go to Linked Devices tab")
                InfoStep(number: "3", text: "Tap 'Share This Device'")
                InfoStep(number: "4", text: "Generate and share the 6-digit code")
            }
        }
        .padding()
        .background(Color.blue.opacity(0.1))
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.blue.opacity(0.3), lineWidth: 1)
        )
    }
}
