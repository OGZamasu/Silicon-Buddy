package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.io.IOException;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The pairing sheet's camera, on the minified release build: on while the scanner is on
 * screen, and let go the moment it leaves.
 *
 * CameraX binds to a lifecycle, and the scanner bound to the activity's — which the sheet is
 * not. Closed, or switched to typing the code, or done because the code was read, the sheet
 * went and the camera stayed: streaming frames into an analyser nobody read, with the privacy
 * indicator lit, until the whole app left the screen. Whether the camera is open is asked of
 * the camera service itself, which lists the apps holding one.
 *
 * Java, like {@link FakeMac}, for the reason given there.
 */
@RunWith(AndroidJUnit4.class)
public class QrScannerTest {

    private static final long WAIT = 20_000;
    private static final String PACKAGE = "dev.siliconoptimizer.buddy";
    private static final String CAMERA = "android.permission.CAMERA";

    private Instrumentation instrumentation;
    private UiDevice device;
    private Context context;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation().grantRuntimePermission(PACKAGE, CAMERA);
        device.wakeUp();
        device.pressHome();
    }

    /**
     * The camera stays allowed. Taking a runtime permission back kills the app's process,
     * which is this test's process too; so this class runs in scripts/ci-android.sh's pass on
     * a fresh install, where nothing after it needs the camera never asked for.
     */
    @After
    public void tearDown() {
        device.pressHome();
    }

    @Test
    public void theCameraIsLetGoWhenTheScannerLeaves() {
        openScanner();
        assertTrue("the scanner never opened the camera", waitFor(this::cameraOpen));

        // Typing the code instead: the sheet stays, the scanner goes, and so does the camera.
        UiObject2 typed = device.findObject(By.text("Enter code"));
        assertNotNull(typed);
        typed.click();
        assertNotNull(device.wait(Until.findObject(By.text("Pairing code")), WAIT));
        assertTrue("the camera stayed on with no scanner on screen", waitFor(() -> !cameraOpen()));

        // Back to the scanner, then the sheet closed: the app is still in front, and the
        // camera is not on for it.
        UiObject2 scan = device.findObject(By.text("Scan"));
        assertNotNull(scan);
        scan.click();
        assertTrue("the scanner did not open the camera again", waitFor(this::cameraOpen));
        UiObject2 close = device.wait(Until.findObject(By.text("Close")), WAIT);
        assertNotNull("the sheet has no Close", close);
        close.click();
        assertTrue("the sheet stayed up",
            device.wait(Until.gone(By.textStartsWith("Point at the code")), WAIT));
        assertTrue("the camera stayed on after the sheet closed", waitFor(() -> !cameraOpen()));
    }

    /** Settings, and the button that opens pairing, which starts on the scanner. */
    private void openScanner() {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        assertNotNull(launch);
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        UiObject2 settings = device.wait(Until.findObject(By.desc("Settings")), WAIT);
        if (settings == null) settings = device.wait(Until.findObject(By.text("Settings")), WAIT);
        assertNotNull("no way to Settings", settings);
        settings.click();
        UiObject2 open = device.wait(Until.findObject(By.text(Pattern.compile("Pair with (a|another) Mac"))), WAIT);
        assertNotNull("Settings has no pairing button", open);
        open.click();
        assertNotNull("the sheet did not open on the scanner",
            device.wait(Until.findObject(By.textStartsWith("Point at the code")), WAIT));
    }

    /** Whether the camera service lists this app among the clients holding a camera open. */
    private boolean cameraOpen() {
        String dump;
        try {
            dump = device.executeShellCommand("dumpsys media.camera");
        } catch (IOException failed) {
            throw new AssertionError("the camera service did not answer", failed);
        }
        int start = dump.indexOf("Active Camera Clients:");
        if (start < 0) throw new AssertionError("the camera service no longer says who holds a camera");
        int end = dump.indexOf("Allowed user IDs", start);
        return dump.substring(start, end < 0 ? dump.length() : end).contains(PACKAGE);
    }

    private interface Condition {
        boolean holds();
    }

    private static boolean waitFor(Condition condition) {
        long deadline = System.currentTimeMillis() + WAIT;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) return true;
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.holds();
    }
}
