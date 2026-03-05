import SwiftUI

struct DeviceDetailView: View {
    let device: LinkedDeviceViewModel
    
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                DailyUsageSummaryCard(usageData: device.usageData)
            }
            .padding()
        }
        .navigationTitle(device.name)
        .navigationBarTitleDisplayMode(.large)
        .background(Color(.systemGroupedBackground))
    }
}
