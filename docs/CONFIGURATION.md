# Scanner customization — 1.1.0

The scanner accepts a configuration snapshot per session. Existing launch APIs remain supported. Start a new scanner instance to apply changed settings; use the demo's Settings screen to experiment before launching. Card entry, CVV, tokenization, and payment submission belong to the host app.

## Configuration groups

- **controls**: show/hide close, manual entry, torch, retry, zoom and focus indicators; choose top/bottom camera controls, initial zoom/torch, and haptics. Device capability always wins over requested camera settings. Focus and torch change hardware; hiding an icon alone is cosmetic.
- **appearance**: ARGB colors, typography, spacing, dimensions, guide geometry, preview masking, privacy copy visibility, and icon overrides. Android uses dp/sp and drawable resource IDs; iOS uses points and SF Symbol names. Custom assets/fonts/layouts can use view slots. Text respects system font scaling; the default view adds no animated transitions.
- **recognition**: confirmation counts, minimum OCR confidence, minimum normalized text height and camera resolution preference. Zero confidence means no confidence filtering. Scores are engine-specific, not probabilities that a card is correct. A higher threshold may reject small expiry text. Android uses bundled Latin ML Kit; iOS additionally supports Vision languages and fast/accurate recognition. iOS language correction remains disabled for numeric card text. Requested resolution falls back to device capability.
- **validation**: a nonempty subset of Visa/Mastercard/Amex, expiry disabled/optional/required, supported date format groups, literal expiry labels, future-year limit, expired-card policy, and missing-expiry behavior. Separated dates include slash/dash/dot; spaced dates are constrained to short/labelled text; compact MMYY requires a label. Labels are literal text, never arbitrary regular expressions.
- **timing**: OCR interval, stable duration, confirmation window, candidate reset, expiry wait, overall timeout, warning/preview/focus/success display durations, and confirmed-number retention. Android durations are milliseconds; iOS durations are seconds.
- **partial**: fragment merging, preview, repeated evidence, overlap, retention, and memory bounds.
- **messages**: keyed replacements for every built-in label, instruction, warning, and accessibility string. Omitted keys use English defaults. Validation produces codes independently of displayed copy. Host custom slots own their own localization and accessibility.
- **completionMode**: automatic handoff or an explicit confirmation button.

## Scan policy and outcomes

Luhn and network length/prefix rules always apply. Missing digits are never invented. A fragment must appear in multiple separate frames and overlap exactly by at least six digits. A merged candidate then follows the configured confirmation policy. Each fragment expires independently; a capacity overflow clears fragment evidence. New conflicting card numbers clear expiry evidence. A confirmed number is retained only for the configured active-time limit.

Number-only mode ignores expiry. Optional expiry normally returns a confirmed number after the expiry wait; required expiry never returns a number-only success. With required expiry, `returnNumber` effectively keeps scanning; choose `timeout` for a terminal expiry timeout. `keepScanning` can still end at the overall session timeout. Expiry wait begins when the number is confirmed. Timers are driven independently of OCR callbacks, pause in the background, and reset on Retry. Success display time is included in the overall session budget. Confirmation-window gaps reset unconfirmed evidence. Warning changes receive a readable minimum display interval.

Expired dates are rejected by default. `returnForReview` returns the observed expired value with `expiredRequiresReview` and forces the scanner confirmation button even in automatic mode. It never becomes a normal valid result. Invalid months, out-of-range years, ambiguous dates, invalid PANs and unsupported brands cannot become successful scans. Additional host validation can reject a complete candidate; it cannot bypass these rules. Keep this callback quick, synchronous, and side-effect-free.

Terminal outcomes distinguish **completed**, **manual entry requested**, **cancelled**, **timed out**, and **failed**. Completion includes number, optional expiry, detected brand, validation status, and whether manual expiry entry is needed. Host code owns dismissal. Each scanner instance delivers at most one terminal callback. The legacy APIs continue returning card results or cancellation.

Progress events include only message codes and warning state. They contain no PAN, expiry, frame, image, or raw OCR text. UI slots receive transient preview strings and must handle them as sensitive information. Display masking affects the preview only; the final result still contains the complete confirmed number. No configuration enables logging, saving frames, CVV recognition, or network OCR.

## UI slots and layout responsibilities

There are four optional replacements: header, camera controls, status, and detected values. The library retains camera/recognition ownership, shared guide filtering, manual-entry/confirmation controls, and lifecycle cleanup. Custom slots receive immutable UI state and typed actions where applicable. Hosts can dispatch close, manual entry, retry, confirm, torch, and zoom actions. Hiding the close/manual buttons does not prevent the host from dismissing an embedded scanner; Android system Back still cancels.

Guide width, aspect ratio, and vertical placement determine one shared rectangle for drawing and filtering. Guide width is limited by available height so it remains on-screen. Analysis padding expands only the recognition region around that rectangle. Top controls and extreme text sizes may need custom slots on compact or landscape displays; test the supported layouts. Slot dimensions cannot alter the OCR region implicitly.

## Presets and tuning

`balanced` uses 3 number reads, 2 expiry reads, a 250 ms OCR interval, and a 12-second expiry wait. `fast` uses 2 number reads and 150 ms; iOS also uses Vision fast recognition. `strict` uses 5 number reads, 3 expiry reads, a 600 ms stable duration, and required expiry. Presets are tuning profiles, not measured accuracy promises. Compare false acceptance, missed scans, completion latency, glare, embossed numbers, camera motion and small expiry text on real devices before choosing custom settings.

## Validation and compatibility

Configuration validation rejects invalid ranges and empty brand/format sets before capture. Confirmation/fragment reads are 2–20; overlap 6–13 digits; fragment count 2–32; fragment lifetime 0.5–30 seconds. OCR confidence is 0–1; minimum text height 0–0.2; zoom 1–10 before hardware clamping. Analysis interval is 0–5 seconds; stability 0–10; confirmation/reset 0.1–30; expiry wait 0–120; optional overall timeout 1–600; confirmed number retention 1–600. Display intervals are bounded to 5 or 10 seconds. Colors are ARGB integers. Geometry/font bounds are enforced by `validate()` and defined next to the public properties.

The configuration belongs in application configuration, not in card data persistence. Android transports only serializable values in its Activity contract; closures and UI slots are available through the embedded Compose API. iOS captures a value snapshot. Applications using changed binary Swift interfaces must rebuild when updating the package.

## Differences from v1.0.0

The legacy APIs still work. Expiry waiting is now measured from confirmed number time rather than the first tentative read. Unconfirmed number evidence expires across long gaps even if no empty OCR frames arrive. Confirmed numbers expire after 60 active seconds by default. Partial merges are confirmed as candidates and fragments expire individually. These changes reduce stale evidence and make timing configurable. The guide is now sized responsively, with corner brackets by default and an optional outline. Timer and parser behavior are covered by deterministic tests; those tests do not establish real-camera accuracy.

## Android integration

```kotlin
val configuration = CardScannerConfiguration(
    controls = ScannerControls(torchVisible = true, zoomVisible = true, retryVisible = true),
    recognition = RecognitionOptions(numberConfirmationReads = 4),
    timing = ScannerTiming(expiryWaitTimeoutMs = 15_000, sessionTimeoutMs = 60_000),
    validation = ValidationOptions(supportedBrands = setOf(CardBrand.VISA, CardBrand.MASTERCARD)),
    appearance = ScannerAppearance(guideColor = 0xFF00D2FF, numberDisplay = NumberDisplay.MASKED),
    messages = mapOf(ScannerMessage.TITLE to "Scan your card"),
    completionMode = CompletionMode.USER_CONFIRMATION
)
launcher.launch(CardScanContract.createIntent(context, configuration))
```

In the Activity Result callback, use `CardScanContract.parseOutcome(resultCode, data)` to distinguish all outcomes. `parse()` remains available for existing integrations.

Embedded hosts must enable Compose, have a `LifecycleOwner`/Activity Result registry, and apply `FLAG_SECURE` to their window. The library Activity applies it automatically. Camera permission is declared by the library and requested by the embedded scanner. Permission denial keeps controls visible; the host can direct users to system Settings.

```kotlin
CardScanner(
    configuration = configuration,
    onOutcome = { outcome -> /* update form and dismiss on all terminal outcomes */ },
    onProgress = { progress -> /* optional value-free status handling */ },
    additionalValidator = { result -> result.brand == CardBrand.VISA },
    slots = ScannerSlots(
        header = { _, dispatch ->
            TextButton(onClick = { dispatch(ScannerAction.CLOSE) }) { Text("Cancel scan") }
        },
        status = { state -> Text(configuration.text(state.message)) }
    )
)
```

Custom callbacks/content cannot be passed through an Intent. Resource icon overrides must resolve in the consuming application's resources. Create a new composition (for example, a new presentation or `key(sessionId)`) for another scan after completion. The demo's Settings menu demonstrates the serializable configuration API.

## Complete property reference

Defaults below are the values shipped in 1.1.0. See the configuration source for types and validation constraints.

### ScannerControls

| Property | Default |
| --- | --- |
| `torchVisible` | `true` |
| `initialTorchEnabled` | `false` |
| `hideTorchWhenUnavailable` | `false` |
| `tapToFocusEnabled` | `true` |
| `focusMarkerVisible` | `true` |
| `manualEntryVisible` | `true` |
| `closeVisible` | `true` |
| `retryVisible` | `false` |
| `zoomVisible` | `false` |
| `initialZoom` | `1f` |
| `hapticsEnabled` | `false` |
| `placement` | `ControlPlacement.BOTTOM` |

### RecognitionOptions

| Property | Default |
| --- | --- |
| `numberConfirmationReads` | `3` |
| `expiryConfirmationReads` | `2` |
| `minimumConfidence` | `0f` |
| `minimumTextHeight` | `0f` |
| `resolutionWidth` | `1280` |
| `resolutionHeight` | `720` |

### ValidationOptions

| Property | Default |
| --- | --- |
| `supportedBrands` | `CardBrand.entries.toSet()` |
| `expiryRequirement` | `ExpiryRequirement.OPTIONAL` |
| `missingExpiryAction` | `MissingExpiryAction.RETURN_NUMBER` |
| `expiredCardPolicy` | `ExpiredCardPolicy.REJECT` |
| `maximumFutureYears` | `20` |
| `expiryFormats` | `ExpiryFormat.entries.toSet()` |
| `expiryLabels` | `listOf("EXP", "VALID", "THRU", "GOOD")` |

### ScannerTiming

| Property | Default |
| --- | --- |
| `analysisIntervalMs` | `250` |
| `minimumStableDurationMs` | `0` |
| `confirmationWindowMs` | `3000` |
| `candidateResetDelayMs` | `2000` |
| `expiryWaitTimeoutMs` | `12000` |
| `sessionTimeoutMs` | `null` |
| `warningDisplayMs` | `1600` |
| `previewRetentionMs` | `1000` |
| `expiryPreviewRetentionMs` | `1500` |
| `focusMarkerMs` | `900` |
| `successDisplayMs` | `0` |
| `confirmedNumberRetentionMs` | `60000` |

### PartialNumberOptions

| Property | Default |
| --- | --- |
| `enabled` | `true` |
| `previewVisible` | `true` |
| `readsPerFragment` | `2` |
| `minimumOverlap` | `6` |
| `retentionMs` | `5000` |
| `maxFragments` | `12` |

### ScannerAppearance

| Property | Default |
| --- | --- |
| `backgroundColor` | `0xFF000000` |
| `textColor` | `0xFFFFFFFF` |
| `guideColor` | `0xFFFFD522` |
| `warningColor` | `0xFFAE2525` |
| `successColor` | `0xFF16883B` |
| `focusColor` | `0xFFFFC83D` |
| `buttonColor` | `0xFF222222` |
| `previewColor` | `0xDD000000` |
| `titleSizeSp` | `20f` |
| `bodySizeSp` | `15f` |
| `buttonSizeSp` | `15f` |
| `previewSizeSp` | `17f` |
| `iconSizeDp` | `28f` |
| `buttonSizeDp` | `72f` |
| `spacingDp` | `12f` |
| `cornerRadiusDp` | `16f` |
| `guideWidthFraction` | `0.88f` |
| `guideAspectRatio` | `1.5f` |
| `guideVerticalPosition` | `0.25f` |
| `guideStrokeDp` | `3f` |
| `analysisPaddingDp` | `24f` |
| `shadeOpacity` | `0.67f` |
| `guideStyle` | `GuideStyle.CORNERS` |
| `guideVisible` | `true` |
| `numberDisplay` | `NumberDisplay.FULL` |
| `expiryPreviewVisible` | `true` |
| `privacyVisible` | `true` |
| `announceStatus` | `true` |
| `closeIcon` | `0` |
| `torchOnIcon` | `0` |
| `torchOffIcon` | `0` |
| `manualIcon` | `0` |
| `privacyIcon` | `0` |

## Message keys

`READY`, `CONFIRMING_NUMBER`, `LOOKING_FOR_EXPIRY`, `CONFIRMING_EXPIRY`, `PARTIAL_NUMBER`, `NO_NUMBER`, `NO_TEXT`, `INVALID_NUMBER`, `UNSUPPORTED_BRAND`, `INVALID_EXPIRY`, `EXPIRED`, `AMBIGUOUS_EXPIRY`, `PERMISSION_DENIED`, `CAMERA_UNAVAILABLE`, `CAMERA_STARTING`, `TORCH_UNAVAILABLE`, `ANALYSIS_FAILED`, `FOCUS_FAILED`, `REVIEW`, `SUCCESS`, `HOST_REJECTED`, `TITLE`, `INSTRUCTIONS`, `CLOSE`, `MANUAL`, `RETRY`, `CONFIRM`, `TORCH_ON`, `TORCH_OFF`, `PRIVACY`, `EXPIRY_PREFIX`, `PARTIAL_PREFIX`, `ZOOM_IN`, `ZOOM_OUT`, `NUMBER_DETECTED`, `EXPIRY_DETECTED`.
