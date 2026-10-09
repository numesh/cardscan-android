# Developer guide

## Architecture

The library follows state/event-driven presentation. `CardScannerConfiguration` is the per-session policy snapshot; it contains no card data. `CardScanner` is the Compose platform adapter for camera permission, CameraX, ML Kit, lifecycle, and the active-time clock. `ScannerViewModel` publishes immutable `ScannerUiState`. `ScanEngine` handles deterministic candidate confirmation, timeout, completion, and transient result ownership. `CardTextExtractor` parses number/date observations and `PartialPanTracker` merges bounded repeated fragments. `ScannerScreen` renders state and emits `ScannerAction` values. `CardScannerActivity` hosts the same embedded scanner and adapts terminal outcomes to Activity results.

The demo has its own `DemoViewModel`, typed events, form state, and navigation effects. `ScannerSettings` changes only the library configuration; the Add card form remains host-owned. Card data is not kept in saved state or persisted.

## Public integration

Use the Activity Result API with `CardScanContract.createIntent(context, configuration)` and `parseOutcome()`. Existing `parse()` returns `CardScanResult?`. The returned result includes `cardNumber`, nullable `expiry`, computed `brand`, `requiresManualExpiry`, and `validationStatus`. Review results in editable fields before any payment flow. CVV is always entered manually.

For embedded Compose usage and every configuration property, see [CONFIGURATION.md](CONFIGURATION.md). Custom slots and additional validation are available only through `CardScanner`; they cannot be serialized through an Activity Intent. The embedded host supplies lifecycle/activity-result ownership and secure-window policy. All terminal outcomes should cause host dismissal; use a new composition for a new scan.

## Policy guarantees

Only Luhn-valid Visa/Mastercard/Amex PANs are accepted, filtered by the configured brand set. Multiple matching reads and the optional stable interval are required. A changed card candidate clears associated expiry evidence. Partial merges require repeated exact overlap, a single valid completion, bounded memory, and per-fragment expiration. No digits are guessed. Confidence thresholds filter OCR evidence but do not prove correctness.

Dates are ranked by explicit expiry labels and position. Invalid/ambiguous observations reset conflicting expiry confirmation. Optional expiry may fall back to a number-only result. Required expiry never does. Expired-card review is explicit and requires confirmation. Neither scanning nor Luhn establishes payment authorization or account validity.

## Lifecycle

The timer uses monotonic active time and continues without OCR callbacks. It pauses while inactive. CameraX owns lifecycle binding and keeps only the latest frame. Analysis is throttled before ML Kit processing. Terminal delivery is guarded exactly once; analyzers unbind and close, and transient engine state clears. Configuration changes apply on the next scanner presentation. The library Activity and embedded UI use the same policy engine.

## Testing

Run `:cardscan:testDebugUnitTest` for confirmation, expiry requirements, timeout, fragments, masking, validation, and lifecycle-independent timing. Build the instrumentation APKs, install on a device, and run:

```sh
adb shell am instrument -w com.andronuke.cardscan.library.test/com.andronuke.cardscan.ScannerPipelineInstrumentation
adb shell am instrument -w com.andronuke.cardscan.test/com.andronuke.cardscan.demo.DemoResultInstrumentation
```

The library and demo instrumentation APKs use distinct application IDs and may be installed together. The scanner runner exercises bundled ML Kit using synthetic in-memory text; no real card image is included. Check permission denial/regrant, cancellation, backgrounding, rotation, torch availability, small screens, large fonts, and custom slots on supported devices before production rollout.

## Extending the library

Add new configuration with a default and validation; route it through both the embedded and Activity paths. Use message codes rather than hardcoded UI copy. Keep parser/engine tests deterministic with supplied times and dates. New brands require prefix/length rules plus fixtures, not just UI labels. New OCR models need separate performance/accuracy/privacy evaluation. Headless recognition, cloud OCR, CVV capture, payment tokenization, and arbitrary PAN-acceptance bypasses are outside this release.

## Release process

Update the Maven version, demo version, changelog, and installation examples. Run local checks, push `main`, and wait for CI. Tag that commit only after CI passes. Existing tags are immutable. GitHub Packages requires authenticated consumers; Nexus is also supported. Do not publish credentials, local SDK paths, real PANs, device screenshots with real cards, or build caches.
