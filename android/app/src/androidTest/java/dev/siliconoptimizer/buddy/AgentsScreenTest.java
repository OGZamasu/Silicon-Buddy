package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The Agents tab on the minified release build, end to end.
 *
 * Driven from the outside, through the accessibility tree, against the APK R8 produced —
 * nothing here names an app class, because R8 renames them and a test that reached in
 * would be testing the unminified app by accident. What it proves is what matters on a
 * real phone: that the new wire types survive R8 and decode (the cards show what the Mac
 * said), that an approval can be answered from the session, and that with the app in the
 * background the watcher posts one notification per approval whose buttons demand the
 * device be unlocked.
 *
 * Java, like {@link FakeMac}, so the test APK needs nothing from the Kotlin standard
 * library that the shrunk app might not have kept.
 */
@RunWith(AndroidJUnit4.class)
public class AgentsScreenTest {

    private static final long WAIT = 20_000;
    private static final String PACKAGE = "dev.siliconoptimizer.buddy";

    private Instrumentation instrumentation;
    private UiDevice device;
    private Context context;
    private FakeMac mac;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        context = instrumentation.getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.getUiAutomation().grantRuntimePermission(
                PACKAGE, "android.permission.POST_NOTIFICATIONS");
        }
        mac = new FakeMac();
        device.wakeUp();
        device.pressHome();
    }

    @After
    public void tearDown() throws Exception {
        mac.close();
        device.pressHome();
    }

    /** Pairs through the link the Mac's QR carries, replacing whatever an earlier test left. */
    private void pair() {
        Intent link = new Intent(Intent.ACTION_VIEW,
            Uri.parse("siliconbuddy://pair?host=127.0.0.1&port=" + mac.port() + "&code=123456"))
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(link);
        UiObject2 confirm = device.wait(Until.findObject(By.text(Pattern.compile("Pair|Replace this Mac…"))), WAIT);
        assertNotNull("the pairing confirmation never appeared", confirm);
        boolean replacing = confirm.getText().startsWith("Replace");
        confirm.click();
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }
        assertTrue("the app never asked the stand-in for a token",
            waitFor(() -> mac.saw("POST", "/buddy/pair")));
    }

    private void openAgents() {
        UiObject2 tab = device.wait(Until.findObject(By.text("Agents")), WAIT);
        assertNotNull("a full-control device has an Agents tab", tab);
        tab.click();
    }

    private void openCodex() {
        UiObject2 card = device.wait(Until.findObject(By.text("Codex")), WAIT);
        assertNotNull("the Codex card never appeared", card);
        List<UiObject2> open = device.findObjects(By.text("Open"));
        assertTrue(open.size() >= 1);
        open.get(0).click();
    }

    @Test
    public void theAgentsTabShowsTheMacsSessionsAndAnswersAnApproval() {
        pair();
        openAgents();

        // Both engines, as the Mac listed them, with what is waiting and what never asks.
        assertNotNull(device.wait(Until.findObject(By.text("Codex")), WAIT));
        assertNotNull(device.wait(Until.findObject(By.text("Pi")), WAIT));
        assertNotNull("the pending approval is counted on the card",
            device.wait(Until.findObject(By.text("1 approval waiting")), WAIT));
        assertNotNull("Pi's unattended mode is said on its card",
            device.wait(Until.findObject(By.text("Runs without asking")), WAIT));
        assertNotNull("the tab's badge is said, not only drawn",
            device.wait(Until.findObject(By.desc("Agents, 1 approval waiting")), WAIT));

        openCodex();
        assertNotNull("the transcript is the Mac's",
            device.wait(Until.findObject(By.text("Running the suite now.")), WAIT));
        assertNotNull("reasoning is folded away until asked for",
            device.wait(Until.findObject(By.text("Reasoning")), WAIT));
        assertNotNull(device.wait(Until.findObject(By.text("Codex wants to run a command")), WAIT));

        UiObject2 accept = device.wait(Until.findObject(By.text("Accept")), WAIT);
        assertNotNull(accept);
        accept.click();

        assertTrue("the answer reached the Mac",
            waitFor(() -> "accept".equals(mac.decision)));
        assertTrue(mac.bodyOf("POST", "/agent/sessions/codex/approvals/" + FakeMac.APPROVAL)
            .contains("\"decision\":\"accept\""));
        assertTrue("the card came down, saying so",
            device.wait(Until.hasObject(By.textStartsWith("Accepted on this phone")), WAIT));
        assertTrue("the command's row arrived on the stream",
            device.wait(Until.hasObject(By.text("$ swift test --filter Lisbon")), WAIT));
    }

    @Test
    public void inTheBackgroundAnApprovalRingsWithButtonsThatNeedTheDeviceUnlocked() {
        pair();
        openAgents();
        openCodex();
        assertNotNull(device.wait(Until.findObject(By.text("Codex wants to run a command")), WAIT));

        // Leaving with a turn running in a session this phone opened starts the watcher.
        device.pressHome();

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification[] found = new Notification[1];
        assertTrue("no approval notification was posted", waitFor(() -> {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                Notification notification = posted.getNotification();
                CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
                if (title != null && title.toString().equals("Codex wants to run a command")) {
                    found[0] = notification;
                    return true;
                }
            }
            return false;
        }));
        Notification approval = found[0];

        // One per approval, private on the lock screen, with a public version that says
        // only that something is waiting.
        int approvals = 0;
        boolean watching = false;
        for (StatusBarNotification posted : manager.getActiveNotifications()) {
            CharSequence title = posted.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            if (title != null && title.toString().equals("Codex wants to run a command")) approvals++;
            if (title != null && title.toString().startsWith("Watching Codex")) watching = true;
        }
        assertEquals("one notification per approval", 1, approvals);
        assertTrue("the watcher's own notification is up", watching);
        assertEquals(Notification.VISIBILITY_PRIVATE, approval.visibility);
        assertNotNull(approval.publicVersion);
        String publicText = String.valueOf(approval.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(publicText.contains("waiting"));
        assertTrue("the lock screen is not told the command", !publicText.contains("swift"));

        assertEquals(2, approval.actions.length);
        assertEquals("Decline", approval.actions[0].title.toString());
        assertEquals("Accept", approval.actions[1].title.toString());
        if (Build.VERSION.SDK_INT >= 31) {
            for (Notification.Action action : approval.actions) {
                assertTrue(action.title + " must demand the device be unlocked",
                    action.isAuthenticationRequired());
            }
        }

        // And the button works from the shade: the phone is unlocked, so the system sends
        // the broadcast at once, and the answer reaches the Mac.
        device.openNotification();
        UiObject2 accept = device.wait(Until.findObject(By.text(Pattern.compile("(?i)accept"))), WAIT);
        if (accept == null) {
            UiObject2 title = device.wait(Until.findObject(By.text("Codex wants to run a command")), WAIT);
            assertNotNull(title);
            title.swipe(androidx.test.uiautomator.Direction.DOWN, 1.0f);
            accept = device.wait(Until.findObject(By.text(Pattern.compile("(?i)accept"))), WAIT);
        }
        assertNotNull("the Accept button is in the shade", accept);
        accept.click();
        assertTrue("the answer from the shade reached the Mac",
            waitFor(() -> "accept".equals(mac.decision)));

        // The turn ended, so the watcher lets go and takes the approval down with it.
        assertTrue("the approval notification came down", waitFor(() -> {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                Notification notification = posted.getNotification();
                if (notification.actions != null && notification.actions.length == 2) return false;
            }
            return true;
        }));
        device.pressBack();
    }

    private interface Condition {
        boolean holds();
    }

    private static boolean waitFor(Condition condition) {
        long deadline = System.currentTimeMillis() + WAIT;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) return true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.holds();
    }
}
