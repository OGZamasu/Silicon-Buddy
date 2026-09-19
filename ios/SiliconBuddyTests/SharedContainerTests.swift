import XCTest
@testable import SiliconBuddy

/// The plumbing that lets a widget, a share sheet and a Shortcuts action find the same
/// Mac as the app.
///
/// Worth a test rather than an assumption: if the entitlement is wrong the app keeps
/// working perfectly and only the extensions are blank, which is the kind of breakage
/// nobody notices until they place a widget.
final class SharedContainerTests: XCTestCase {

    /// The bug this replaced: matching the access group on the bundle id alone.
    ///
    /// Every target lists `$(AppIdentifierPrefix)dev.siliconoptimizer.buddy` first in
    /// its entitlement, so an item added with no explicit group is filed under *that*,
    /// not under the extension's own `…buddy.widgets`. The old code asked for a suffix
    /// match on the bundle id, got none, and answered nil — in the app it coincided and
    /// worked, and in both extensions the shared Keychain silently did not exist.
    func testThePrefixIsReadFromTheSharedGroupNotTheBundleID() {
        XCTAssertEqual(
            SharedKeychain.prefix(
                from: "ABCDE12345.dev.siliconoptimizer.buddy",
                bundleID: "dev.siliconoptimizer.buddy.widgets"
            ),
            "ABCDE12345."
        )
        XCTAssertEqual(
            SharedKeychain.prefix(
                from: "ABCDE12345.dev.siliconoptimizer.buddy",
                bundleID: "dev.siliconoptimizer.buddy.share"
            ),
            "ABCDE12345."
        )
    }

    /// A build with no shared entitlement at all files under the bundle id, and the
    /// prefix is still worth having — it just names a group nothing else can reach.
    func testTheBundleIDIsTheFallbackWhenThereIsNoSharedGroup() {
        XCTAssertEqual(
            SharedKeychain.prefix(
                from: "ABCDE12345.dev.siliconoptimizer.buddy.widgets",
                bundleID: "dev.siliconoptimizer.buddy.widgets"
            ),
            "ABCDE12345."
        )
    }

    func testAnEmptyTeamPrefixIsAPrefixAndNotAFailure() {
        XCTAssertEqual(
            SharedKeychain.prefix(from: "dev.siliconoptimizer.buddy", bundleID: nil),
            ""
        )
    }

    func testAGroupThatIsNeitherIsRefused() {
        XCTAssertNil(
            SharedKeychain.prefix(from: "ABCDE12345.com.example.other", bundleID: nil)
        )
    }

    func testTheAppGroupIsReallyShared() throws {
        try XCTSkipIf(
            UserDefaults(suiteName: BuddyShared.appGroup) == nil,
            "This build has no app group; the extensions will show 'open the app to pair'."
        )
        XCTAssertTrue(BuddyShared.hasSharedContainer)
        BuddyShared.defaults.set("probe", forKey: "buddy.tests.probe")
        XCTAssertEqual(
            UserDefaults(suiteName: BuddyShared.appGroup)?.string(forKey: "buddy.tests.probe"),
            "probe"
        )
        BuddyShared.defaults.removeObject(forKey: "buddy.tests.probe")
    }

    /// The token is a credential, so it stays in the Keychain rather than the container,
    /// and this is the group that lets the extensions read it.
    func testTheTokenRoundTripsThroughTheSharedKeychainGroup() throws {
        let store = TokenStore(service: "dev.siliconoptimizer.buddy.tests.token")
        defer { store.delete(account: "shared-probe") }
        try store.save("a-token", account: "shared-probe")
        XCTAssertEqual(store.read(account: "shared-probe"), "a-token")
    }

    /// A Keychain that refuses the shared group must not break pairing: the app falls
    /// back to its own group and the extensions find nothing, which is the honest
    /// failure rather than an app that cannot store a token at all.
    func testAStoreWithNoSharedGroupStillWorks() throws {
        let store = TokenStore(
            service: "dev.siliconoptimizer.buddy.tests.token", accessGroup: nil
        )
        defer { store.delete(account: "private-probe") }
        try store.save("b-token", account: "private-probe")
        XCTAssertEqual(store.read(account: "private-probe"), "b-token")
    }

    func testForgettingRemovesTheTokenFromEveryGroupItCouldBeIn() throws {
        let store = TokenStore(service: "dev.siliconoptimizer.buddy.tests.token")
        try store.save("c-token", account: "forget-probe")
        XCTAssertTrue(store.delete(account: "forget-probe"))
        XCTAssertNil(store.read(account: "forget-probe"))
        XCTAssertNil(
            TokenStore(service: "dev.siliconoptimizer.buddy.tests.token", accessGroup: nil)
                .read(account: "forget-probe")
        )
    }

    /// A widget must never dial a host the app would refuse, whatever is in the shared
    /// container — which is a file three processes can write.
    func testSharedConfigurationAppliesTheSameTailnetGateTheAppDoes() throws {
        let suite = "dev.siliconoptimizer.buddy.tests.config"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokens = TokenStore(service: "dev.siliconoptimizer.buddy.tests.token2")
        defer { tokens.delete() }
        try tokens.save("token")

        defaults.set("evil.example.com", forKey: SharedConfiguration.Keys.host)
        defaults.set(8788, forKey: SharedConfiguration.Keys.port)
        XCTAssertNil(SharedConfiguration.load(defaults: defaults, tokens: tokens))

        defaults.set("100.64.0.9", forKey: SharedConfiguration.Keys.host)
        let config = SharedConfiguration.load(defaults: defaults, tokens: tokens)
        XCTAssertEqual(config?.host, "100.64.0.9")
        XCTAssertEqual(config?.port, 8788)
    }

    func testAnUnpairedContainerYieldsNoTransport() throws {
        let suite = "dev.siliconoptimizer.buddy.tests.empty"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokens = TokenStore(service: "dev.siliconoptimizer.buddy.tests.none")
        XCTAssertNil(SharedConfiguration.transport(defaults: defaults, tokens: tokens))
    }
}
