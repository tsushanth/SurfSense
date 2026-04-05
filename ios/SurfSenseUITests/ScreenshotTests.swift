import XCTest

@MainActor
class ScreenshotTests: XCTestCase {
    let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        setupSnapshot(app)
        app.launch()
    }

    func testScreenshots() {
        sleep(3)
        snapshot("01_ThisDevice")

        app.tabBars.buttons["Linked Devices"].tap()
        sleep(1)
        snapshot("02_LinkedDevices")

        app.tabBars.buttons["Trends"].tap()
        sleep(1)
        snapshot("03_Trends")

        app.tabBars.buttons["Settings"].tap()
        sleep(1)
        snapshot("04_Settings")
    }
}
