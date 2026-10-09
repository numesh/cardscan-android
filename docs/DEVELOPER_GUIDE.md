# Card scanner developer guide

This guide describes the current **R&D sample** in `CardScanSample`. It is intended for a developer integrating the library, changing OCR rules, testing on devices, or assessing it for production.

## 1. What the sample does

- Opens a camera screen from an Add Card form.
- Reads printed card numbers and expiry dates on the device with CameraX and bundled ML Kit Latin OCR.
- Accepts **Visa, Mastercard, and Amex** number formats only. It requires a Luhn-valid complete number and repeated recognition before returning it.
- Displays recognized number and expiry text over the live preview. Invalid or expired expiry text and validation problems receive a red warning.
- Can retain a confirmed number while the user tilts the card to reveal the expiry in later frames.
- Can join two repeated, overlapping partial number reads under strict conditions; it never returns an incomplete number.
- Closes the scanner and fills editable number/month/year fields in the separate demo app. The user supplies CVV manually.

The demo does **not** submit a payment, store a card, call a backend, or tokenize card data. Its Add Card button is intentionally inactive. Passing a scan is not payment authorization or proof that a card is usable.

## 2. Project map

| Location | Responsibility |
| --- | --- |
| `cardscan/` | Reusable Android library/AAR and Maven publication. |
| `cardscan/src/main/kotlin/com/andronuke/cardscan/CardScanContract.kt` | Public launch and result parsing API. |
| `cardscan/src/main/kotlin/com/andronuke/cardscan/CardScanResult.kt` | In-memory number and optional expiry result. |
| `cardscan/src/main/kotlin/com/andronuke/cardscan/CardScannerActivity.kt` | Android adapter for camera, permission, OCR line collection, and result handoff. |
| `cardscan/src/main/kotlin/com/andronuke/cardscan/ScannerViewModel.kt` | Scanner events, immutable UI state, warnings, confirmation timing, and one-time effects. |
| `cardscan/src/main/kotlin/com/andronuke/cardscan/ScannerScreen.kt` | Compose preview, overlays, controls, and user-event forwarding. |
| `cardscan/src/main/kotlin/com/andronuke/cardscan/CardTextExtractor.kt` | OCR text parsing, number/card-type checks, expiry parsing. |
| `cardscan/src/main/kotlin/com/andronuke/cardscan/PartialPanTracker.kt` | Short-lived partial number fragments and conservative overlap merge. |
| `cardscan/src/androidTest/` | Bundled OCR, parser, card type, expiry, and partial merge instrumentation checks. |
| `demo/src/main/kotlin/com/andronuke/cardscan/demo/DemoActivity.kt` | Android adapter for Activity Result navigation and secure window policy. |
| `demo/src/main/kotlin/com/andronuke/cardscan/demo/DemoViewModel.kt` | Add Card events, form state, and one-time navigation effects. |
| `demo/src/main/kotlin/com/andronuke/cardscan/demo/DemoScreen.kt` | Compose-based Add Card form and input-event forwarding. |
| `demo/src/androidTest/` | Synthetic result handoff instrumentation check. |

The root `settings.gradle.kts` includes both modules. All project source, instrumentation tests, and Gradle scripts are Kotlin. Both UI screens use Jetpack Compose; CameraX’s `PreviewView` is embedded with `AndroidView` because it supplies the live camera surface and touch focus. The build uses Kotlin 2.2.20, the Compose Compiler Gradle plugin, Compose BOM 2025.09.00, `minSdk 23`, `targetSdk 35`, `compileSdk 36`, Java/JVM target 17, Android Gradle Plugin 8.13.2, and Gradle 8.13. The demo depends on the local library project.

Direct camera and OCR dependencies are Google's AndroidX CameraX 1.6.2 artifacts and bundled `com.google.mlkit:text-recognition:16.0.1`. Gradle may resolve transitive support dependencies. Review the resolved dependency graph and your organization's approved-artifact policy before publishing or updating versions.

### MVVM event and state flow

Each Compose screen receives an immutable `UiState` and an `onEvent` callback. It does not parse OCR, decide whether a card is valid, or launch Android activities. The ViewModel reduces typed events into a `StateFlow` snapshot; Compose observes that snapshot with `collectAsState()`. One-time commands are sent through a buffered effect channel so a navigation or scan result is not re-run during recomposition.

| Module | Events into ViewModel | State rendered by Compose | One-time effects handled by Activity |
| --- | --- | --- | --- |
| Demo | Field edits, scan request, scan result, back press | Card number, month, year, CVV, nickname, guidance note | Launch scanner, close screen |
| Library scanner | Filtered OCR frame, torch/focus actions, camera/permission feedback | Status and red-warning flag, number/expiry previews, torch state, focus marker | Toggle hardware torch, close scanner, return result |

`CardScannerActivity` is the platform boundary: CameraX and ML Kit produce OCR lines; the activity filters them to the guide and sends `ScannerEvent.Frame` to `ScannerViewModel`. The ViewModel runs `CardTextExtractor` and `PartialPanTracker`, tracks confirmation across frames, and emits `ScannerEffect.Finished` once. The activity alone puts the accepted result in an `Intent`. `DemoActivity` parses that result and sends `AddCardEvent.ScanCompleted` to its ViewModel; the form only renders the new state.

Card details are kept in memory during this R&D flow. Neither ViewModel uses `SavedStateHandle`; do not add PAN or OCR text to saved state, logs, or analytics. ViewModels are lifecycle-aware, but a host must still apply its approved protected-field and tokenization design for production.

## 3. Scan flow

```text
Add Card camera button
  -> Activity Result launcher with CardScanContract.createIntent(...)
  -> non-exported CardScannerActivity requests CAMERA permission
  -> CameraX PreviewView + ImageAnalysis/MlKitAnalyzer
  -> OCR lines whose bounding-box center falls inside the card guide
  -> ScannerEvent.Frame in ScannerViewModel
  -> CardTextExtractor: complete number and expiry candidates
  -> PartialPanTracker: optional overlapping-fragment candidate
  -> ScannerUiState: previews, warnings, manual-entry guidance
  -> ScannerEffect.Finished: complete PAN and optional valid expiry
  -> CardScanContract.parse(...) in DemoActivity
  -> AddCardEvent.ScanCompleted in DemoViewModel
  -> AddCardUiState fills editable number, month, and year
```

The Compose scanner hosts CameraX `PreviewView` in `AndroidView`. Its `ScaleType.FILL_CENTER` center-crops rather than stretching the card. Compose draws the guide, OCR previews, warnings, focus marker, and controls. Analysis requests 1280×720; CameraX chooses the closest supported resolution according to the configured fallback. The visible guide has a 1.5:1 aspect ratio and the Activity uses the same bounds to filter OCR, with a small allowance. The analyzer uses `STRATEGY_KEEP_ONLY_LATEST` so processing does not queue old frames. `MlKitAnalyzer` maps text coordinates to the preview view.

The UI follows the supplied three-screen reference. The empty and review forms use the same layout: a 22 dp back graphic in a 48 dp touch target, a 24 dp outlined camera graphic in a 44 dp touch target, one visual `MM / YY` expiry field backed by separate month/year state, and a bottom Add card button. A valid scan adds a green confirmation banner and guidance for manually entering CVV or a missing expiry. The scanner has a 22 dp close graphic in a 48 dp target, yellow guide corners, a 28 dp flashlight graphic in a 72 dp circular button below the preview, a manual-entry button, and a privacy note. Icons are drawn in Compose at display density so they stay sharp; touch targets and graphic sizes are intentionally independent. The camera band ends above the flash button, leaving its lower controls on black. Keep `ScannerScreen` and `CardScannerActivity.insideGuide()` geometry in sync when changing the guide.

The scanner uses tap-to-focus through CameraX. Its focus marker is UI feedback for the tap; it does not assert that focus succeeded. The torch icon remains visible; tapping it explains when the camera is still starting or the device has no flash unit. Torch state is observed and reflected in the icon. The camera permission denial path leaves manual entry available.

## 4. Number recognition and partial scanning

### Complete number

`CardTextExtractor` reads individual OCR lines and same-row joined text. It strips spaces and hyphens, then considers 13–19 digit candidates. A complete candidate must pass Luhn and the sample card-type rules:

| Card type | Accepted number shape |
| --- | --- |
| Visa | Starts with `4`; 13, 16, or 19 digits. |
| Mastercard | Starts with `51`–`55` or `2221`–`2720`; 16 digits. |
| Amex | Starts with `34` or `37`; 15 digits. |

These checks identify plausible printed numbers. They do not verify issuance, account status, or payment acceptance. The production host must reconcile supported brands and BIN rules with its payment provider. The scanner normally requires the same complete number in three analyzed frames. An unsupported, complete Luhn-valid card type gets a red warning; a complete number that fails validation gets a different warning.

### Partial number recovery

Glare can reveal different portions as the user tilts the same card. `PartialPanTracker` keeps 8–14 digit OCR fragments **in memory** for a brief scan session. The preview says “Part captured” and prompts the user to tilt the card. The tracker:

1. Counts a fragment at most once per analyzed frame and requires each merging fragment in at least two frames.
2. Joins two fragments only when suffix and prefix overlap by at least six exact digits.
3. Accepts the joined result only if there is exactly one possible complete number and it passes the same Luhn/card-type checks.
4. Clears fragments after a five-second gap with no matching fragment, on a direct complete read, when too many distinct fragments accumulate, and when the scanner is destroyed.

A successfully merged result enters the normal number/expiry flow as a confirmed number. The scanner does **not** infer missing digits from Luhn and does not join fragments without an overlap. A card change inside the same active session cannot be perfectly identified from OCR alone; the overlap rule reduces that risk but does not eliminate it. The Add Card form must keep the result editable for user review. For production, consider an explicit “Start over” control and stronger card identity/session handling before relying on partial merges.

## 5. Expiry recognition and result timing

The extractor accepts month/year text separated by `/`, `.`, `-`, or spaces, and can join nearby OCR pieces such as `MM`, `/`, and `YY`. Compact four-digit expiry is considered when the line is labelled with terms such as `EXP` or `VALID`. It normalizes a valid date to `MM/YY` and checks month range, expiry against the current month, and an upper bound of 20 years. Distinct, similarly ranked dates can be treated as ambiguous. Expired, invalid, or conflicting dates remain visible in the preview with a red background and warning, but are **not** returned as valid expiry.

The scanner can recognize expiry in frames that no longer contain the number. It retains a confirmed number while checking subsequent frames, and requires the same expiry twice within the active observation window. If no valid expiry is confirmed about 12 seconds after the number was first seen, it returns the number alone. The Add Card form then asks the user to enter expiry manually. A warning is held briefly before such a number-only return so it can be read. The exact timeout is an R&D tuning value, not a payment-network rule.

The sample card image discussed during development shows `08/2019`: that date is expired and must not populate the Add Card expiry fields. Its printed number also fails Luhn. Use synthetic, future-dated test cards for positive scan tests.

## 6. Scanner feedback and expected outcomes

| Situation | Scanner feedback | Result |
| --- | --- | --- |
| No readable text | Move closer or improve lighting. | Keep scanning; manual entry remains available. |
| Some number digits are stable | Show captured fragment; prompt tilt. | No incomplete number returned. |
| Complete number detected but not confirmed | Show number; ask user to hold steady. | Keep scanning. |
| Complete number fails validation | Red number warning. | Do not return that number. |
| Luhn-valid unsupported type | Red warning naming Visa, Mastercard, Amex. | Do not return that number. |
| Invalid/expired/conflicting expiry | Red expiry preview and warning. | Do not fill expiry. May return confirmed number alone. |
| Valid number and expiry confirmed | Number and expiry preview briefly visible. | Dismiss scanner; fill editable demo fields. |
| Valid number, no reliable expiry | Prompt to keep expiry in frame. | After timeout, dismiss with number only; enter expiry manually. |
| Permission denied or camera unavailable | Warning; manual-entry button. | No scan result. |

The UI is based on the Figma design. Camera frames necessarily differ from the static Figma mockup. Device status bars, keyboards, camera hardware, exposure, and OCR output can vary. User-facing text currently lives in Kotlin source; a production implementation should move it to localized resources and review accessibility labels, scaling, and contrast.

## 7. Integrating the library

The host app needs the published `cardscan` artifact and its transitive Maven dependencies, or a project dependency during development. The library manifest contributes `CAMERA` permission, marks camera hardware optional, and declares `CardScannerActivity` as non-exported. The host should inspect its **merged manifest**, especially permissions, backup, network policy, and exported components.

Use the public API from a Kotlin `ComponentActivity`:

```kotlin
private val scanLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    val scan = CardScanContract.parse(result.resultCode, result.data)
        ?: return@registerForActivityResult
    // Populate editable, protected host fields. Split scan.expiry only if non-null.
    // Let the user review values before the host's normal payment/tokenization flow.
}

fun scanCard() {
    scanLauncher.launch(CardScanContract.createIntent(this))
}
```

`CardScanResult.cardNumber` contains unformatted digits. `CardScanResult.expiry` is either `MM/YY` or `null`. Cancellation, manual-entry exit, and permission failure do not produce a successful result. The legacy `CardScanContract.start(activity, requestCode)` method remains available for hosts using `onActivityResult`; the Kotlin demo uses Activity Result API.

### Build and publish

Open the project root in Android Studio with Android SDK 36 and JDK 17 or newer. From the root:

```sh
./gradlew :cardscan:assembleRelease :demo:assembleDebug
./gradlew :cardscan:lintRelease :demo:lintDebug
./gradlew :cardscan:assembleDebugAndroidTest :demo:assembleDebugAndroidTest
```

The library's `maven-publish` configuration defines coordinates `com.andronuke:cardscan:1.0.0`. Provide `NEXUS_URL`, `NEXUS_USERNAME`, and `NEXUS_PASSWORD` through the build environment to publish to a private Nexus repository, or use the GitHub release workflow to publish to GitHub Packages. Credentials are not stored in the repo. Publishing an AAR alone does not automatically give a consumer all dependencies; Maven publication is preferred so dependency metadata travels with it.

## 8. Security and privacy boundary

Card numbers are sensitive. The current sample processes frames and OCR in memory and contains no code path to save images, log recognized text, send a scan to a server, or submit a payment. The demo manifest removes `INTERNET` and `ACCESS_NETWORK_STATE` permissions from its merge, disables backup and cleartext traffic, and both scanner and demo set `FLAG_SECURE`. CVV is never scanned. The bundled ML Kit model works without a first-run model download.

These controls do **not** prove zero vulnerabilities or PCI compliance. The number and expiry exist temporarily as JVM strings, Compose state, and activity result extras; JVM strings cannot be reliably zeroed. `FLAG_SECURE` helps block ordinary screenshots but is not a general defense against a compromised device or camera observation. A production app may have different permissions and dependencies. Review the **final merged manifest**, dependency/SCA reports, ML Kit data/metrics behavior, crash reporting, analytics, clipboard/autofill behavior, accessibility exposure, logs, secure field handling, and the payment provider's tokenization flow. Complete a security review and applicable PCI assessment before handling live cards in production.

Never put PAN or OCR output in logs, crash reports, saved instance state, preferences, files, screenshots, or test artifacts. Use synthetic numbers in automated tests. The host should send card details only through its approved protected payment SDK or tokenization path, after user review.

## 9. Verification and device test matrix

The `cardscan` instrumentation test runs bundled OCR on a synthetic bitmap and checks parsing, preview candidates, valid/invalid expiry, split expiry, Visa/Mastercard/Amex acceptance, unsupported type rejection, overlap merging, and fragment timeout. It also sends frame events to `ScannerViewModel` and checks its completion effect and invalid-expiry warning state. The demo instrumentation test sends a synthetic `ScanCompleted` event to `DemoViewModel` and checks that number/month/year state is populated while a cancelled scan leaves existing edits intact. To run on a connected device or emulator, install the test APKs and run their configured instrumentation runners from Android Studio, or use `adb shell am instrument -w` with the package/runner shown in each module's `build.gradle.kts`.

Automated tests do **not** prove camera quality. Before production, test on physical low-, mid-, and high-range Android devices with synthetic Visa/Mastercard/Amex cards and at least these conditions:

| Area | Cases to verify |
| --- | --- |
| Print | Flat and embossed digits; dark/light cards; reflective foil; worn print; number on front or back. |
| Lighting | Bright sun, indoor light, low light, glare, torch available/unavailable, shadows. |
| Motion | Tilt to expose a hidden segment, hand shake, rapid card movement, rotate card, leave and re-enter guide. |
| Parsing | Grouped/ungrouped numbers; 13/15/16/19 digits; split OCR groups; different expiry separators; expired/invalid/conflicting dates. |
| False positives | Another card enters the frame, background numbers, two cards together, partial fragments from different cards, OCR digit substitutions. |
| UX | Permission denied, no camera, slow camera startup, tap-to-focus, clear warnings, manual exit, edit returned values, screen reader/large font. |
| Device integration | Camera preview alignment, analysis resolution, orientation, lifecycle interruptions, background/foreground, merged manifest and dependency graph. |

Record scan success rate, number-only rate, time to result, false acceptance rate, expiry accuracy, and manual fallback rate using **aggregate events without PAN, OCR text, images, or card identifiers**. Do not instrument real card numbers in telemetry.

## 10. Known limits and suggested next work

1. **Real-device tuning:** The current thresholds (three number frames, two expiry reads, six-digit partial overlap, five-second fragment gap, twelve-second expiry wait) are heuristic. Tune only with synthetic-card and consented device testing; track both missed scans and false acceptance.
2. **Partial-session safety:** Add a visible “Start over” control and stronger card-presence/tracking before broad rollout. Overlap plus Luhn is conservative, but two different cards or OCR mistakes can still produce misleading candidates.
3. **Better date association:** Expiry can appear on another row or side of a card. Improve spatial association and handle more printed variants, while avoiding issue dates or background text. Keep expired/ambiguous dates out of the result.
4. **Camera guidance:** Measure blur/exposure, show actionable “hold steady,” “more light,” or “reduce glare” prompts, and evaluate controlled zoom. Avoid automatic zoom that crops printed details without warning.
5. **Provider alignment:** Replace sample card-type rules and field behavior with the actual payment provider's supported brands, validation, secure input, and tokenization requirements.
6. **API and UX refinement:** Move UI strings to resources, improve Compose accessibility/semantics, and review lifecycle restoration without persisting PAN. The demo already uses Activity Result API; retain the legacy entry point only while host consumers need it.
7. **Security release gate:** Review dependencies and final app permissions, perform threat modeling, security testing, and applicable PCI review. Document approved data flows and retention before any production payment integration.

Keep the demo's manual-entry path even after improving OCR. It is the practical fallback for cards with unreadable print, damaged surfaces, unsupported layouts, or hardware limitations.
