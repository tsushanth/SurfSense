import DeviceActivity
import SwiftUI

@main
struct UsageReportExtension: DeviceActivityReportExtension {
    var body: some DeviceActivityReportScene {
        TotalUsageReport { configuration in
            ReportView(configuration: configuration)
        }
    }
}
