package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Pairing a phone whose camera is refused (#17), and a pairing link that is read only once,
 * on the minified release build.
 *
 * The fallback used to send a phone to the Mac's control.json token, which the Mac takes
 * only on its own loopback — so over the tailnet it could never work. Now the refusal lands
 * on a form for the code the Mac shows, and that code is spent at {@code POST /buddy/pair}
 * exactly as a scanned one is. The token form is still there, named for what it is.
 *
 * Java, like {@link FakeMac}, for the reason given there.
 */
@RunWith(AndroidJUnit4.class)
public class ManualPairingTest {

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
        // A camera nobody has allowed. Revoking stops the app if it is running, so this
        // comes before anything opens it.
        instrumentation.getUiAutomation().revokeRuntimePermission(PACKAGE, "android.permission.CAMERA");
        // Nor refused before: once refused twice, Android stops asking and answers "denied"
        // at once, so whether a prompt came at all would depend on the tests before this
        // one. Cleared, the camera is always asked for, in its first form.
        device.executeShellCommand("pm clear-permission-flags " + PACKAGE
            + " android.permission.CAMERA user-set user-fixed");
        mac = new FakeMac();
        device.wakeUp();
        device.pressHome();
    }

    @After
    public void tearDown() throws Exception {
        // A code a failed test left with the Mac is the app's, not the test's, and the app may
        // outlive the test: answered, it does not hold up the next test's pairing.
        CountDownLatch held = mac != null ? mac.pairHeld : null;
        if (held != null && held.getCount() > 0) {
            held.countDown();
            Thread.sleep(2_000);
        }
        if (mac != null) mac.close();
        // A prompt a failed test never answered would stand over every test after it.
        if (device.findObject(PROMPT) != null) {
            clickDeny();
            device.wait(Until.gone(PROMPT), WAIT);
        }
        device.pressHome();
    }

    @Test
    public void aRefusedCameraLandsOnTheCodeFormAndTheCodeIsSpentAtBuddyPair() {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        assertNotNull(launch);
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));

        // Settings is the gear on a phone and a rail entry on a tablet; either way it has
        // the button that opens pairing, paired or not.
        UiObject2 settings = device.wait(Until.findObject(By.desc("Settings")), WAIT);
        if (settings == null) settings = device.wait(Until.findObject(By.text("Settings")), WAIT);
        assertNotNull("no way to Settings", settings);
        settings.click();
        UiObject2 open = device.wait(Until.findObject(By.text(Pattern.compile("Pair with (a|another) Mac"))), WAIT);
        assertNotNull("Settings has no pairing button", open);
        open.click();

        // The sheet opens on Scan, which asks for the camera.
        refuseTheCamera();

        assertNotNull("a refused camera says so, and where to go instead",
            device.wait(Until.findObject(By.text("Camera denied. Type the code your Mac shows instead.")), WAIT));
        assertNotNull("and it goes there", device.wait(Until.findObject(By.text("Pairing code")), WAIT));

        // The control token has its own tab, and says who it is for.
        UiObject2 developer = device.findObject(By.text("Developer"));
        assertNotNull(developer);
        developer.click();
        assertNotNull("the token is labelled for the emulator and development",
            device.wait(Until.findObject(By.text("Local emulator and development only")), WAIT));
        UiObject2 back = device.findObject(By.text("Enter code"));
        assertNotNull(back);
        back.click();
        assertNotNull(device.wait(Until.findObject(By.text("Pairing code")), WAIT));

        // Code, address, port — in the order the form shows them.
        List<UiObject2> fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT);
        assertNotNull(fields);
        assertTrue("expected the code, the address and the port, got " + fields.size(), fields.size() >= 3);
        fields.get(0).setText("123 456");
        fields.get(1).setText("127.0.0.1");
        fields.get(2).setText(String.valueOf(mac.port()));
        hideKeyboard();

        UiObject2 pair = device.wait(Until.findObject(By.text(Pattern.compile("Pair|Replace this Mac…"))), WAIT);
        assertNotNull("no Pair button", pair);
        boolean replacing = pair.getText().startsWith("Replace");
        pair.click();
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }

        assertTrue("the typed code never reached /buddy/pair", waitFor(() -> mac.saw("POST", "/buddy/pair")));
        String body = mac.bodyOf("POST", "/buddy/pair");
        assertTrue(body, body.contains("\"code\":\"123456\""));
        assertTrue("the sheet stays up after pairing",
            device.wait(Until.gone(By.text("Pairing code")), WAIT));
        assertNotNull("Settings names the Mac the code paired with",
            device.wait(Until.findObject(By.text("Test Mac")), WAIT));
    }

    @Test
    public void aPairingCodeInDeveloperIsExplainedBeforeAnyRequest() {
        openCodeForm();
        List<UiObject2> fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT);
        assertNotNull(fields);
        fields.get(1).setText("127.0.0.1");
        hideKeyboard();
        assertNotNull("local addresses explain which listener takes a code",
            device.wait(Until.findObject(By.textStartsWith("Use the Mac's Tailscale address shown beside the code.")), WAIT));

        device.findObject(By.text("Developer")).click();
        assertNotNull(device.wait(Until.findObject(By.text("Control token")), WAIT));
        fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT);
        assertNotNull(fields);
        assertTrue(fields.size() >= 3);
        fields.get(0).setText("127.0.0.1");
        fields.get(1).setText(String.valueOf(mac.port()));
        fields.get(2).setText("123-456");
        hideKeyboard();
        UiObject2 connect = device.wait(Until.findObject(By.text(Pattern.compile("Connect|Replace this Mac…"))), WAIT);
        assertNotNull(connect);
        boolean replacing = connect.getText().startsWith("Replace");
        connect.click();
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }
        assertNotNull("the code needs Enter code and the address displayed on the Mac",
            device.wait(Until.findObject(By.textStartsWith("That is a pairing code. Choose Enter code")), WAIT));
        assertFalse("the invalid token must not start a probe", mac.saw("GET", "/health"));
        assertFalse("the pairing code must not be sent as a bearer token", mac.saw("GET", "/status"));
        assertFalse("Developer must not spend a pairing code", mac.saw("POST", "/buddy/pair"));
    }

    /**
     * A link pasted into the code form is not something the person typed: whoever made it
     * chose its host. So it gets the confirmation a tapped link gets — the host, the code,
     * and "only pair with a code you can see on your own Mac's screen" — and nothing is
     * dialled until that is agreed to. The form's own Pair button never spends it.
     */
    @Test
    public void aPastedLinkIsConfirmedBeforeAnythingIsDialled() {
        openCodeForm();

        List<UiObject2> fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT);
        assertNotNull(fields);
        assertTrue("expected the code, the address and the port, got " + fields.size(), fields.size() >= 3);
        // One change carrying the whole link, which is what a paste is.
        fields.get(1).setText("siliconbuddy://pair?host=127.0.0.1&port=" + mac.port() + "&code=246810");

        assertNotNull("a pasted link is confirmed like a tapped one",
            device.wait(Until.findObject(By.text("Pair with this Mac?")), WAIT));
        assertNotNull("with the warning a tapped link carries",
            device.findObject(By.textContains("Only pair with a code you can see on your own Mac's screen")));
        assertNotNull(device.findObject(By.text("127.0.0.1:" + mac.port())));
        assertNotNull(device.findObject(By.text("Code 246 810")));
        assertTrue("nothing is dialled before the person agrees", !mac.saw("POST", "/buddy/pair"));
        // The form went away, so its own Pair button is not the one found below.
        assertTrue("the code form stays up behind the confirmation",
            device.wait(Until.gone(By.text("Pairing code")), WAIT));

        UiObject2 confirm = device.wait(Until.findObject(By.text(Pattern.compile("Pair|Replace this Mac…"))), WAIT);
        assertNotNull(confirm);
        boolean replacing = confirm.getText().startsWith("Replace");
        confirm.click();
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }
        assertTrue("agreed to, the link's code reaches /buddy/pair",
            waitFor(() -> mac.saw("POST", "/buddy/pair")));
        String body = mac.bodyOf("POST", "/buddy/pair");
        assertTrue(body, body.contains("\"code\":\"246810\""));
    }

    /**
     * Closing the sheet while the Mac is answering. The code was spent in the sheet's own
     * coroutine scope, so closing it cancelled the request — after the Mac had the code, had
     * made this phone a device, and was sending back its token. Now the request is the app's,
     * and the answer that comes after the sheet has gone is kept.
     */
    @Test
    public void closingTheSheetMidPairingStillKeepsTheMacsAnswer() throws Exception {
        mac.macName = "Slow Mac";
        mac.pairHeld = new CountDownLatch(1);
        spendATypedCode("135 790");
        assertTrue("the typed code never reached /buddy/pair", waitFor(() -> mac.saw("POST", "/buddy/pair")));

        closeTheSheet();
        mac.pairHeld.countDown();

        assertNotNull("the Mac's answer, which came after the sheet closed, was dropped",
            device.wait(Until.findObject(By.text("Slow Mac")), WAIT));
        assertTrue("and the phone talks to it with the token it was given",
            waitFor(() -> mac.saw("GET", "/status")));
    }

    /**
     * The same, when the Mac says no. The sheet that would have said so is gone, so the app
     * says it, and names the Mac that refused.
     */
    @Test
    public void aRefusalThatComesAfterTheSheetClosedIsStillSaid() throws Exception {
        mac.pairRefusal = "That pairing code has expired.";
        mac.pairHeld = new CountDownLatch(1);
        spendATypedCode("246 813");
        assertTrue("the typed code never reached /buddy/pair", waitFor(() -> mac.saw("POST", "/buddy/pair")));

        closeTheSheet();
        mac.pairHeld.countDown();

        assertNotNull("a refusal nobody was left to show was never said",
            device.wait(Until.findObject(By.text("Pairing didn't finish")), WAIT));
        assertNotNull(device.findObject(By.textContains("That pairing code has expired.")));
        assertNotNull(device.findObject(By.textContains("127.0.0.1:" + mac.port())));
        device.findObject(By.text("OK")).click();
        assertTrue(device.wait(Until.gone(By.text("Pairing didn't finish")), WAIT));
    }

    /**
     * A link that arrives while another code is still with its Mac. Its dialog used to show a
     * spinner — as if it were the one being spent — and keep "Not now" disabled for as long
     * as the other took, up to about 28 seconds. It says what it is waiting for, may be
     * declined, and once the first code has paired, asks to replace that Mac like any other.
     */
    @Test
    public void aLinkThatArrivesWhileAnotherCodeIsSpentWaitsAndMayBeDeclined() throws Exception {
        mac.macName = "First Mac";
        mac.pairHeld = new CountDownLatch(1);
        spendATypedCode("135 791");
        assertTrue("the typed code never reached /buddy/pair", waitFor(() -> mac.saw("POST", "/buddy/pair")));
        closeTheSheet();

        context.startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("siliconbuddy://pair?host=127.0.0.1&port=" + mac.port() + "&code=246802"))
            .setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertNotNull("the second link was not asked about",
            device.wait(Until.findObject(By.text("Code 246 802")), WAIT));
        assertNotNull("it does not say what it is waiting for",
            device.findObject(By.text("Waiting for the other pairing (127.0.0.1:" + mac.port() + ") to finish…")));
        UiObject2 notNow = device.findObject(By.text("Not now"));
        assertNotNull(notNow);
        assertTrue("Not now is disabled while another code is spent", takesTaps(notNow));
        UiObject2 pair = device.findObject(By.text(Pattern.compile("Pair|Replace this Mac…")));
        assertNotNull(pair);
        assertTrue("a second code could start while the first is out", !takesTaps(pair));

        mac.pairHeld.countDown();
        assertNotNull("once the first has paired, the second asks to replace it",
            device.wait(Until.findObject(By.textStartsWith("This replaces First Mac")), WAIT));
        assertTrue("and may, now that nothing else is being spent", waitFor(() -> {
            UiObject2 replace = device.findObject(By.text("Replace this Mac…"));
            return replace != null && takesTaps(replace);
        }));
        device.findObject(By.text("Not now")).click();
        assertTrue(device.wait(Until.gone(By.text("Code 246 802")), WAIT));
        assertTrue("the second code was spent", mac.count("POST", "/buddy/pair") == 1);
    }

    /**
     * Leaving the app while the Mac thinks, and opening it again before it answers. The new
     * activity's state had read the token store before the answer was in it, so the app said
     * it was not paired — or paired to the Mac before — until its next restart, though the
     * token was stored. Now the answer lands on whichever state is on screen.
     */
    @Test
    public void anAnswerThatLandsAfterTheAppWasLeftAndOpenedAgainIsShown() throws Exception {
        mac.macName = "Late Mac";
        mac.pairHeld = new CountDownLatch(1);
        spendATypedCode("135 792");
        assertTrue("the typed code never reached /buddy/pair", waitFor(() -> mac.saw("POST", "/buddy/pair")));

        // A new task: the activity that asked is finished, and its view model with it.
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        assertNotNull(launch);
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        UiObject2 settings = device.wait(Until.findObject(By.desc("Settings")), WAIT);
        if (settings == null) settings = device.wait(Until.findObject(By.text("Settings")), WAIT);
        assertNotNull("no way to Settings", settings);
        settings.click();
        assertNotNull(device.wait(Until.findObject(By.text(Pattern.compile("Pair with (a|another) Mac"))), WAIT));
        assertNull("answered already", device.findObject(By.text("Late Mac")));

        mac.pairHeld.countDown();
        assertNotNull("the reopened app never showed the Mac the code paired with",
            device.wait(Until.findObject(By.text("Late Mac")), WAIT));
        assertTrue("and does not talk to it", waitFor(() -> mac.saw("GET", "/status")));
    }

    /**
     * Whether the control labelled {@code label} takes a tap. Compose gives a button's text a
     * node of its own, which always reads as enabled; the button is its parent.
     */
    private static boolean takesTaps(UiObject2 label) {
        UiObject2 control = label.getParent();
        return control != null && control.isEnabled();
    }

    /** The code form, filled with {@code code} for this test's Mac, and its Pair tapped. */
    private void spendATypedCode(String code) {
        openCodeForm();
        List<UiObject2> fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT);
        assertNotNull(fields);
        assertTrue("expected the code, the address and the port, got " + fields.size(), fields.size() >= 3);
        fields.get(0).setText(code);
        fields.get(1).setText("127.0.0.1");
        fields.get(2).setText(String.valueOf(mac.port()));
        hideKeyboard();
        UiObject2 pair = device.wait(Until.findObject(By.text(Pattern.compile("Pair|Replace this Mac…"))), WAIT);
        assertNotNull("no Pair button", pair);
        boolean replacing = pair.getText().startsWith("Replace");
        pair.click();
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }
    }

    /** The sheet's own Close, while the Mac is still thinking. */
    private void closeTheSheet() {
        UiObject2 close = device.wait(Until.findObject(By.text("Close")), WAIT);
        assertNotNull("the sheet has no Close", close);
        close.click();
        assertTrue("the sheet stayed up", device.wait(Until.gone(By.text("Pairing code")), WAIT));
    }

    /** Settings, pairing, and the camera refused: which lands on the code form. */
    private void openCodeForm() {
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
        refuseTheCamera();
        assertNotNull("the code form", device.wait(Until.findObject(By.text("Pairing code")), WAIT));
    }

    /** The system's permission prompt: Google's package on a Play image, AOSP's elsewhere. */
    private static final BySelector PROMPT = By.pkg(Pattern.compile(".*permissioncontroller"));

    /**
     * Its two deny buttons: "Don't allow" the first time Android asks, and the
     * don't-ask-again one, which has another id, when it asks again after a no. Matched by
     * pattern, whichever of the two packages the id carries.
     */
    private static final Pattern DENY = Pattern.compile(
        ".*permissioncontroller:id/permission_deny(_and_dont_ask_again)?_button");

    /**
     * Answers the camera prompt with no. setUp has made sure Android asks, so the prompt is
     * waited for, not hoped for, and a run where it never comes fails here and says so.
     *
     * The answer is an accessibility click on the button itself, not a tap at its
     * coordinates. The prompt's buttons are in the accessibility tree before its window has
     * drawn and takes input: a tap in that gap went to the app underneath, where Android
     * dropped it as obscured, and the prompt stood unanswered until tearDown. A click on the
     * node goes to the button whether or not its window takes touches yet. It is repeated
     * until the prompt is gone, in case the first one lands before the dialog is listening.
     */
    private void refuseTheCamera() {
        assertNotNull("the camera prompt never appeared", device.wait(Until.findObject(By.res(DENY)), WAIT));
        long deadline = System.currentTimeMillis() + WAIT;
        boolean gone = false;
        while (!gone && System.currentTimeMillis() < deadline) {
            clickDeny();
            gone = device.wait(Until.gone(PROMPT), 2_000);
        }
        assertTrue("the camera prompt was not answered", gone);
    }

    /** Clicks whichever deny button the prompt is showing, through its accessibility node. */
    private void clickDeny() {
        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo info = automation.getServiceInfo();
        if ((info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
        }
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || root.getPackageName() == null
                || !root.getPackageName().toString().endsWith("permissioncontroller")) continue;
            AccessibilityNodeInfo button = find(root, DENY);
            if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        }
    }

    /** The first node under {@code node} whose resource id matches {@code id}. */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, Pattern id) {
        String name = node.getViewIdResourceName();
        if (name != null && id.matcher(name).matches()) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            AccessibilityNodeInfo found = find(child, id);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * A link is read once. The critic's case: pair through a link, then change the display
     * density (or the font size) — which recreates the activity with the intent it was
     * opened with — and "Pair with this Mac?" came back with the code already spent.
     */
    @Test
    public void aSpentLinkIsNotOfferedAgainWhenTheActivityIsRecreated() throws Exception {
        Intent link = new Intent(Intent.ACTION_VIEW,
            Uri.parse("siliconbuddy://pair?host=127.0.0.1&port=" + mac.port() + "&code=654321"))
            .setPackage(PACKAGE)
            // A fresh task, so the link is the intent the activity is created with.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
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
        assertTrue("the link never reached /buddy/pair", waitFor(() -> mac.saw("POST", "/buddy/pair")));
        assertTrue("the confirmation stays up after pairing",
            device.wait(Until.gone(By.text("Pair with this Mac?")), WAIT));

        String before = device.executeShellCommand("wm density");
        java.util.regex.Matcher physical = Pattern.compile("Physical density: (\\d+)").matcher(before);
        java.util.regex.Matcher override = Pattern.compile("Override density: (\\d+)").matcher(before);
        assertTrue(before, physical.find());
        int current = override.find() ? Integer.parseInt(override.group(1)) : Integer.parseInt(physical.group(1));
        try {
            device.executeShellCommand("wm density " + (current + 60));
            device.waitForIdle();
            assertNull("a recreated activity offered the spent code again",
                device.wait(Until.findObject(By.text("Pair with this Mac?")), 5_000));
            assertNull(device.findObject(By.text("Code 654 321")));
        } finally {
            device.executeShellCommand(before.contains("Override density:")
                ? "wm density " + current : "wm density reset");
        }
    }

    /** The keyboard can sit over the button; Back closes it, and only when it is up. */
    private void hideKeyboard() {
        try {
            String state = device.executeShellCommand("dumpsys input_method");
            if (state.contains("mInputShown=true")) device.pressBack();
        } catch (java.io.IOException ignored) {
            // Nothing to learn from; the tap below says whether it mattered.
        }
        device.waitForIdle();
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
}
