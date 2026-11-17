import XCTest

/// The app starts, and a person who is not signed in gets the sign-in screen.
final class LaunchTests: XCTestCase {

    func testTheAppOpensOnTheSignInScreen() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["Sign in or create an account"].waitForExistence(timeout: 30), "the sign-in screen")
        XCTAssertTrue(app.textFields["Username"].exists)
        XCTAssertTrue(app.secureTextFields["Password"].exists)
        // The server is matrix.org unless changed.
        XCTAssertTrue(app.textFields["matrix.org"].exists)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "sign-in"
        shot.lifetime = .keepAlways
        add(shot)
    }
}
