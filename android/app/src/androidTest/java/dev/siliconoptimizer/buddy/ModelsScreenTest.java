package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The Models tab on the minified release build, when the Mac says no (#12) and when a load
 * outlives its request (#13).
 *
 * Driven from the outside, through the accessibility tree, like {@link AgentsScreenTest}:
 * nothing here names an app class, because R8 renames them. What it proves is what a person
 * holding the phone would see — the Mac's own sentence on screen, a dismissal that takes it
 * away, a retry that works leaving nothing stale behind, a list that failed to arrive not
 * passed off as an empty library, and a slow load followed to the failure the Mac pushed,
 * with its log behind a tap.
 */
@RunWith(AndroidJUnit4.class)
public class ModelsScreenTest {

    private static final long WAIT = 20_000;
    private static final String PACKAGE = "dev.siliconoptimizer.buddy";
    private static final String KILLED = "llama-server was killed (signal 9) after 8 seconds, "
        + "which usually means the system reclaimed its memory.";
    private static final String LOG = "load_tensors: loading model tensors";

    private UiDevice device;
    private Context context;
    private FakeMac mac;

    @Before
    public void setUp() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        context = instrumentation.getTargetContext();
        mac = new FakeMac();
        mac.status = FakeMac.IDLE;
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

    private void openModels() {
        UiObject2 tab = device.wait(Until.findObject(By.text("Models")), WAIT);
        assertNotNull("there is a Models tab", tab);
        tab.click();
    }

    /**
     * Text on screen, wherever the tree put it. The banner reads as one announcement, so its
     * heading and sentence may arrive as one node; a fragment finds either.
     */
    private static BySelector showing(String fragment) {
        return By.text(Pattern.compile(".*" + Pattern.quote(fragment) + ".*", Pattern.DOTALL));
    }

    private boolean onScreen(String fragment) {
        return device.wait(Until.hasObject(showing(fragment)), WAIT);
    }

    private boolean goneFromScreen(String fragment) {
        return device.wait(Until.gone(showing(fragment)), WAIT);
    }

    private void tap(String text) {
        UiObject2 control = device.wait(Until.findObject(By.text(text)), WAIT);
        assertNotNull("no " + text + " to press", control);
        control.click();
    }

    private int loads() {
        int count = 0;
        for (String line : mac.received) if (line.startsWith("POST /load ")) count++;
        return count;
    }

    @Test
    public void aFailedListIsSaidAsThatAndARetryBringsItBack() {
        mac.installedFails = true;
        pair();
        openModels();

        assertTrue("the refresh's failure is on screen, in the Mac's words",
            onScreen(FakeMac.DRIVE_GONE));
        assertTrue(onScreen("Couldn't read the models on the Mac"));
        assertTrue("a list that did not arrive is said as that",
            onScreen("The Mac's models didn't load"));
        assertFalse("and never as an empty library",
            device.hasObject(showing("Your model library")));
        assertFalse(device.hasObject(showing("Explore the Catalog")));

        mac.installedFails = false;
        tap("Retry");
        assertTrue("the list arrives", onScreen("Test Model"));
        assertTrue("and nothing stale is left over it", goneFromScreen(FakeMac.DRIVE_GONE));
        assertFalse(device.hasObject(showing("Couldn't read the models on the Mac")));
    }

    @Test
    public void aRefusedLoadIsShownDismissedAndRetried() {
        pair();
        openModels();
        assertTrue(onScreen("Test Model"));

        mac.loadFails = true;
        tap("Load");
        assertTrue("the Mac's reason is on screen", onScreen(FakeMac.DRIVE_GONE));
        assertTrue(onScreen("Couldn't load Test Model"));

        UiObject2 dismiss = device.wait(Until.findObject(By.desc("Dismiss")), WAIT);
        assertNotNull("the message can be put away, and says how to a screen reader", dismiss);
        dismiss.click();
        assertTrue("dismissing clears it", goneFromScreen(FakeMac.DRIVE_GONE));

        tap("Load");
        assertTrue(onScreen(FakeMac.DRIVE_GONE));
        mac.loadFails = false;
        tap("Retry");
        assertTrue("the retry loaded it", onScreen("Unload"));
        assertTrue("and the old failure is gone", goneFromScreen(FakeMac.DRIVE_GONE));
        assertEquals("one load per press: two refused, one retried", 3, loads());
    }

    @Test
    public void aSlowLoadIsFollowedToTheFailureTheMacPushed() {
        pair();
        openModels();
        assertTrue(onScreen("Test Model"));

        mac.loadIsSlow = true;
        tap("Load");
        assertTrue("the answer said 'still loading', and the row says so",
            onScreen("Loading weights… 42%"));
        SystemClock.sleep(1_000);
        assertTrue("still following, not finished", device.hasObject(showing("Loading weights… 42%")));

        mac.publishStatus("{\"state\":\"" + KILLED + "\",\"expertStreaming\":false,"
            + "\"failure\":{\"reason\":\"killed\",\"detail\":\"" + LOG + "\",\"runtime\":\"llama.cpp\","
            + "\"signal\":9,\"wasReplaced\":false,\"at\":\"2026-09-19T11:04:38Z\"}}");
        assertTrue("the pushed failure ends the load, sentence first", onScreen(KILLED));
        assertTrue(onScreen("Couldn't load Test Model"));
        assertFalse("the log waits behind a tap", device.hasObject(showing(LOG)));

        tap("Show log");
        assertTrue("and is there when asked for", onScreen(LOG));
        assertTrue(onScreen("Hide log"));
        assertEquals("the Mac was asked once; the rest was followed", 1, loads());
    }

    private static boolean waitFor(java.util.concurrent.Callable<Boolean> condition) {
        long deadline = SystemClock.uptimeMillis() + WAIT;
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(condition.call())) return true;
            } catch (Exception ignored) {
                // Not yet.
            }
            SystemClock.sleep(200);
        }
        return false;
    }
}
