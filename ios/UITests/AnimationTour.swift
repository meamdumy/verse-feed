import XCTest

/// Drives the app through every animated interaction while CI records the simulator screen.
/// Each action is followed by "STEP <name> <unix time>" (logged right after the tap or drag
/// happens, since XCUITest can wait a second for the app to go idle before acting), so the video
/// can be cut into frame strips around it.
final class AnimationTour: XCTestCase {

    private var app: XCUIApplication!

    private func step(_ name: String) {
        print(String(format: "STEP %@ %.3f", name, Date().timeIntervalSince1970))
    }

    private func pause(_ s: Double) { Thread.sleep(forTimeInterval: s) }

    private func tap(_ label: String, _ name: String) {
        let b = app.buttons[label].firstMatch
        if b.waitForExistence(timeout: 5) { b.tap(); step(name) } else { XCTFail("no button \(label)") }
    }

    private func drag(_ name: String, from: CGFloat, to: CGFloat, velocity: XCUIGestureVelocity, hold: TimeInterval) {
        let w = app.windows.firstMatch
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
            .press(forDuration: 0.05, thenDragTo: w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)),
                   withVelocity: velocity, thenHoldForDuration: hold)
        step(name) // logged as the finger lifts
    }

    func testTour() {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-bg_style", "0", "-uitest", "YES"]
        app.launch()
        pause(4)

        // Magenta flash: CI finds it in the video to line every step up exactly.
        tap("SyncFlash", "sync"); pause(2)

        // Home -> Read: push transition, then the first verse's box/words/buttons intro.
        tap("Read", "tab-read"); pause(2)

        // Swipe verses: a slow drag (page follows the finger), a short slow drag (~17%,
        // Android turns past 12%), then two quick flicks.
        drag("swipe-slow", from: 0.75, to: 0.25, velocity: .slow, hold: 0.1); pause(1.5)
        drag("swipe-short", from: 0.62, to: 0.45, velocity: .slow, hold: 0.2); pause(1.5)
        app.windows.firstMatch.swipeUp(velocity: .fast); step("swipe-fast"); pause(1.2)
        app.windows.firstMatch.swipeDown(velocity: .fast); step("swipe-back"); pause(1.2)

        // Read -> Shuffle: the feed pushes sideways, the counter stays put.
        tap("Shuffle", "tab-shuffle"); pause(2)

        // Shuffle -> Home.
        tap("Home", "tab-home"); pause(2)

        // Swipe up past the bottom of Home into today's verse (opens on release).
        app.windows.firstMatch.swipeUp(velocity: .fast); pause(1)
        drag("pull-daily", from: 0.85, to: 0.2, velocity: .default, hold: 0.3); pause(2)

        // Back down to Home.
        tap("Home", "leave-daily"); pause(2)

        // Settings card grows from the gear; theme switches blur through.
        tap("Settings", "settings-open"); pause(1.5)
        tap("Blur light", "theme-light"); pause(1.5)
        tap("Blur dark", "theme-dark"); pause(1.5)
        tap("Colorful", "theme-colorful"); pause(1.5)
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.92)).tap()
        step("settings-close"); pause(1.5)

        // Go to: push, tile cascade, then search results popping in.
        tap("Go to", "tab-goto"); pause(2)
        let field = app.textFields.firstMatch
        if field.waitForExistence(timeout: 3) {
            field.tap()
            field.typeText("love your neighbor"); step("search"); pause(2.5)
        }
        step("end")
    }
}
