import DeviceActivity
import SwiftUI
import os

private let logger = Logger(subsystem: "com.kreativekoala.SurfSense", category: "UsageReport")

struct TotalUsageReport: DeviceActivityReportScene {

    let context: DeviceActivityReport.Context = .init(rawValue: "TotalActivity")

    let content: (ReportView.Configuration) -> ReportView

    func makeConfiguration(
        representing data: DeviceActivityResults<DeviceActivityData>
    ) async -> ReportView.Configuration {

        logger.info("makeConfiguration called")

        var categorySummary: [String: Int] = [:]
        var segmentCount = 0
        var activityDataCount = 0
        var rawCategoryNames: [String] = []

        // Iterate the async sequence of activity data
        for await activityData in data {
            activityDataCount += 1
            for await segment in activityData.activitySegments {
                segmentCount += 1
                for await categoryActivity in segment.categories {
                    let categoryName = categoryActivity.category.localizedDisplayName ?? "Other"
                    rawCategoryNames.append(categoryName)
                    let mappedName = SharedUsageData.mapCategory(categoryName)
                    let totalSeconds = Int(categoryActivity.totalActivityDuration)

                    if totalSeconds > 0 {
                        categorySummary[mappedName, default: 0] += totalSeconds
                    }
                }
            }
        }

        let containerOK = SharedUsageData.isContainerAccessible()
        let debugInfo = "activityData=\(activityDataCount) segments=\(segmentCount) cats=\(rawCategoryNames.count) mapped=\(categorySummary.count) container=\(containerOK)"
        logger.info("\(debugInfo)")

        // Write to App Group shared storage so the host app can read it
        SharedUsageData.writeCategorySummary(categorySummary)

        return ReportView.Configuration(
            categorySummary: categorySummary,
            debugInfo: debugInfo
        )
    }
}
