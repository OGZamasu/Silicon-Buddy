package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.io.IOException;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

/**
 * "Cancel render" on the Create tab's queue, on the minified release build.
 *
 * Driven from the outside, like {@link AgentsScreenTest}. What it proves is what a person
 * holding the phone sees: the button on the one clip the Mac marks cancellable and on no
 * other, nothing sent until the warning about the GPU work has been read and agreed to, the
 * node's answer on the clip afterwards — and, on a phone paired for chat only, no such
 * button at all. That the new queue fields survive R8 is part of it: a field it dropped
 * would be a button that never appears.
 */
@RunWith(AndroidJUnit4.class)
public class QueueCancelTest {

    private static final long WAIT = 20_000;
    private static final String PACKAGE = "dev.siliconoptimizer.buddy";

    /** The clip the Mac stopped following, whose node still offers to stop it. */
    private static final String CANCELLABLE = "9C2F-0005";
    /** The clip rendering now, on a node that offers nothing but Stop following. */
    private static final String FOLLOWED = "9C2F-0001";

    private static final String STOPPING =
        "The node is stopping this render. The queue follows it until the node confirms.";

    private UiDevice device;
    private Context context;
    private FakeMac mac;

    @Before
    public void setUp() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        context = instrumentation.getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.getUiAutomation().grantRuntimePermission(
                PACKAGE, "android.permission.POST_NOTIFICATIONS");
        }
        mac = new FakeMac();
        // The cancellable clip first, so both cards are on screen without scrolling.
        mac.videoQueue = queue(null,
            clip(CANCELLABLE, "Alfama steps", "failed", true, null),
            clip(FOLLOWED, "Tram at dawn", "rendering", false, null));
        mac.cancelAnswer = queue(STOPPING,
            clip(CANCELLABLE, "Alfama steps", "rendering", false, "requested"),
            clip(FOLLOWED, "Tram at dawn", "rendering", false, null));
        device.wakeUp();
        device.pressHome();
    }

    /** What was on screen when a check failed, for `adb pull /data/local/tmp/queue-cancel-*.png`. */
    @Rule
    public final TestWatcher evidence = new TestWatcher() {
        @Override
        protected void failed(Throwable failure, Description test) {
            try {
                device.executeShellCommand("screencap -p /data/local/tmp/queue-cancel-" + test.getMethodName() + ".png");
            } catch (IOException ignored) {
                // Evidence, not the test.
            }
        }
    };

    @After
    public void tearDown() throws Exception {
        mac.close();
        device.pressHome();
    }

    private static String clip(String id, String title, String status, boolean canCancel, String cancelState) {
        return "{\"id\":\"" + id + "\",\"batchID\":\"9C2F\",\"title\":\"" + title + "\","
            + "\"prompt\":\"" + title + ", five seconds\",\"scene\":1,\"variation\":1,"
            + "\"modelID\":\"ltx2-distilled\",\"seconds\":5,\"resolution\":\"720p\","
            + "\"status\":\"" + status + "\",\"nodeJobID\":\"job-" + id + "\","
            + "\"outputDirectory\":\"/Users/you/Movies/Silicon/Lisbon\",\"uncertainSubmission\":false,"
            + "\"canCancel\":" + canCancel
            + (cancelState == null ? "" : ",\"cancelState\":\"" + cancelState + "\"")
            + "}";
    }

    private static String queue(String message, String... items) {
        return "{\"paused\":false,\"activeID\":\"" + FOLLOWED + "\","
            + (message == null ? "" : "\"message\":\"" + message + "\",")
            + "\"items\":[" + String.join(",", items) + "]}";
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
        assertTrue("the app never asked the fake Mac for a token",
            waitFor(() -> mac.saw("POST", "/buddy/pair")));
    }

    private void openQueue() {
        tap("Create");
        tap("Queue");
        assertTrue("the queue never showed the Mac's clips", onScreen("Alfama steps, five seconds"));
        assertTrue(onScreen("Tram at dawn, five seconds"));
    }

    private static BySelector showing(String fragment) {
        return By.text(Pattern.compile(".*" + Pattern.quote(fragment) + ".*", Pattern.DOTALL));
    }

    private boolean onScreen(String fragment) {
        return device.wait(Until.hasObject(showing(fragment)), WAIT);
    }

    private void tap(String text) {
        UiObject2 control = device.wait(Until.findObject(By.text(text)), WAIT);
        assertNotNull("no " + text + " to press", control);
        control.click();
    }

    private boolean waitFor(java.util.function.BooleanSupplier check) {
        long deadline = System.currentTimeMillis() + WAIT;
        while (System.currentTimeMillis() < deadline) {
            if (check.getAsBoolean()) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return check.getAsBoolean();
    }

    @Test
    public void cancelRenderIsOfferedOnTheMarkedClipAndAsksFirst() {
        pair();
        openQueue();

        assertTrue(device.wait(Until.hasObject(By.text("Cancel render")), WAIT));
        assertEquals("one clip is marked cancellable, and only that one has the button",
            1, device.findObjects(By.text("Cancel render")).size());
        assertNotNull("Stop following stays on the clip being followed",
            device.findObject(By.text("Stop following")));

        // The warning first, and nothing sent for it.
        tap("Cancel render");
        assertTrue(onScreen("The GPU work it has done is thrown away"));
        tap("Keep rendering");
        assertTrue(device.wait(Until.gone(showing("The GPU work it has done is thrown away")), WAIT));
        assertFalse("keeping it sends nothing", mac.saw("POST", "/video/queue/control"));

        tap("Cancel render");
        tap("Cancel it");
        assertTrue("the cancel never reached the Mac",
            waitFor(() -> mac.saw("POST", "/video/queue/control")));
        String sent = mac.bodyOf("POST", "/video/queue/control");
        assertTrue(sent, sent.contains("\"action\":\"cancel\""));
        assertTrue("it names that clip: " + sent, sent.contains("\"id\":\"" + CANCELLABLE + "\""));

        // The Mac's answer, on the clip: requested, and nothing left to press.
        assertTrue(onScreen("Cancel requested. The node is stopping this render"));
        assertTrue(onScreen(STOPPING));
        assertTrue(device.wait(Until.gone(By.text("Cancel render")), WAIT));
    }

    @Test
    public void aChatOnlyPhoneHasNoCancelRender() {
        mac.scope = "chat";
        pair();
        openQueue();
        // The row is there and so is the clip the Mac marked; the button is not.
        assertNotNull(device.findObject(By.text("Stop following")));
        assertNull(device.findObject(By.text("Cancel render")));
        assertFalse(mac.saw("POST", "/video/queue/control"));
    }
}
