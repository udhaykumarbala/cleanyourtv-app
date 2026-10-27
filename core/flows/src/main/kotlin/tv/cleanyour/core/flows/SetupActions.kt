package tv.cleanyour.core.flows

// What the wizard screens can ask for: the intents (with RFC-W8-1's StopAction and RejectIdentity),
// system back, which must answer at once, and closing an inline card. The SetupMachine implements
// it; screenshot tests pass NoSetupActions.
interface SetupActions {
    fun intent(intent: SetupIntent)
    fun back(): Boolean
    fun dismissCard()
    // Test build: the answer on the candidate list card (true: try the test list).
    fun chooseCandidate(tryIt: Boolean) {}
    // 0.4.6: "Report a problem" on a card where a run or a connect stopped; `title` is the card's
    // own title as shown. Opens the prefilled GitHub form (SetupMachine.shareDiagnostics).
    fun report(title: String) {}
}

object NoSetupActions : SetupActions {
    override fun intent(intent: SetupIntent) {}
    override fun back(): Boolean = false
    override fun dismissCard() {}
}
