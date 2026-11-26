import XCTest

/// Drives the app through every animated interaction while CI records the simulator screen.
/// Each step prints "STEP <name> <unix time>" so the video can be cut into frame strips around it.
final class AnimationTour: XCTestCase {

    private var app: XCUIApplication!

    private func step(_ name: String) {
        print(String(format: "STEP %@ %.3f", name, Date().timeIntervalSince1970))
    }

    private func pause(_ s: Double) { Thread.sleep(forTimeInterval: s) }

    private func tap(_ label: String) {
        let b = app.buttons[label].firstMatch
        if b.waitForExistence(timeout: 5) { b.tap() } else { XCTFail("no button \(label)") }
    }

    func testTour() {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-bg_style", "0"]
        app.launch()
        pause(4)

        // Home -> Read: push transition, then the first verse's box/words/buttons intro.
        step("tab-read"); tap("Read"); pause(2)

        // Swipe verses: a slow drag (page follows the finger) and two quick flicks.
        let w = app.windows.firstMatch
        step("swipe-slow")
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
            .press(forDuration: 0.05, thenDragTo: w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)),
                   withVelocity: .slow, thenHoldForDuration: 0.1)
        pause(1.5)
        // A short, slow drag (~17% of the screen): Android turns the page past 12%.
        step("swipe-short")
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.62))
            .press(forDuration: 0.05, thenDragTo: w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)),
                   withVelocity: .slow, thenHoldForDuration: 0.2)
        pause(1.5)
        step("swipe-fast"); w.swipeUp(velocity: .fast); pause(1.2)
        step("swipe-back"); w.swipeDown(velocity: .fast); pause(1.2)

        // Read -> Shuffle: the feed pushes sideways, the counter stays put.
        step("tab-shuffle"); tap("Shuffle"); pause(2)

        // Shuffle -> Home.
        step("tab-home"); tap("Home"); pause(2)

        // Swipe up past the bottom of Home into today's verse (opens on release).
        w.swipeUp(velocity: .fast); pause(1)
        step("pull-daily")
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85))
            .press(forDuration: 0.05, thenDragTo: w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)),
                   withVelocity: .default, thenHoldForDuration: 0.3)
        pause(2)

        // Back down to Home.
        step("leave-daily"); tap("Home"); pause(2)

        // Settings card grows from the gear; theme switches blur through.
        step("settings-open"); tap("Settings"); pause(1.5)
        step("theme-light"); tap("Blur light"); pause(1.5)
        step("theme-dark"); tap("Blur dark"); pause(1.5)
        step("theme-colorful"); tap("Colorful"); pause(1.5)
        step("settings-close")
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.92)).tap()
        pause(1.5)

        // Go to: push, tile cascade, then search results popping in.
        step("tab-goto"); tap("Go to"); pause(2)
        let field = app.textFields.firstMatch
        if field.waitForExistence(timeout: 3) {
            field.tap()
            step("search"); field.typeText("love your neighbor"); pause(2.5)
        }
        step("end")
    }
}
