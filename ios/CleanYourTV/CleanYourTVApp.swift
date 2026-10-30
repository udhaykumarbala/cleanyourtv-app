import SwiftUI

@main
struct CleanYourTVApp: App {
    var body: some Scene {
        WindowGroup {
            WelcomeView()
                .preferredColorScheme(.dark)
        }
    }
}
