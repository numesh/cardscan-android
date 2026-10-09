# CardScan for Android

On-device card number and expiry scanning using CameraX, bundled Google ML Kit, Kotlin, and Jetpack Compose. Includes a reusable library and a separate demo app with interactive scanner settings. Supports Visa, Mastercard, and Amex; CVV remains manual.

**Current release: 1.1.0.** See the [configuration guide](docs/CONFIGURATION.md) for all options and migration details, and the [developer guide](docs/DEVELOPER_GUIDE.md) for architecture, integration, and validation.

## Install

```kotlin
implementation("com.andronuke:cardscan:1.1.0")
```

Add the GitHub Packages repository to `dependencyResolutionManagement.repositories` alongside `google()` and `mavenCentral()`:

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/numesh/cardscan-android")
    credentials {
        username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
        password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
    }
}
```

GitHub's Maven registry requires authentication, including for public packages. Use a classic personal access token with `read:packages`. Keep credentials in the user-level `~/.gradle/gradle.properties` or environment, never source control. The release also includes an AAR; use Maven to receive transitive dependencies automatically.

## Launch

```kotlin
val configuration = CardScannerConfiguration(
    controls = ScannerControls(torchVisible = true, retryVisible = true),
    timing = ScannerTiming(expiryWaitTimeoutMs = 15_000)
)
launcher.launch(CardScanContract.createIntent(context, configuration))
// In the Activity Result callback:
val outcome = CardScanContract.parseOutcome(resultCode, data)
```

Existing `createIntent(context)`, `start(activity, requestCode)`, and `parse()` integrations continue to work. Embedded Compose hosts can use `CardScanner` with custom UI slots, progress events, and additional validation.

## Build and test

Use JDK 21, Android SDK 36, and the checked-in Gradle wrapper. Minimum supported Android version is API 23.

```sh
./gradlew :cardscan:testDebugUnitTest :cardscan:assembleRelease :demo:assembleDebug
./gradlew :cardscan:assembleDebugAndroidTest :demo:assembleDebugAndroidTest
```

Instrumentation APKs contain a bundled OCR smoke test and demo result tests. Real camera, focus, torch, rotation, glare, and recognition accuracy still require device evaluation. Unit tests cover configuration and scan-policy combinations independently of camera callbacks.

## Data handling

Frames and card values remain in memory. The library Activity applies `FLAG_SECURE`; embedded hosts must apply that flag to their own window. The demo disables backup and cleartext traffic. Preview masking is configurable; returned card values remain complete. No option enables CVV scanning, frame storage, card logging, or payment submission. Review ML Kit metrics disclosures and the consuming app's tokenization, accessibility, logging, and retention before production use. This project does not claim zero vulnerabilities or PCI certification.

## Publishing

Tags use `vMAJOR.MINOR.PATCH`. Release CI tests, builds, publishes `com.andronuke:cardscan`, and attaches the AAR. To publish to Nexus, set `NEXUS_URL`, `NEXUS_USERNAME`, `NEXUS_PASSWORD` and run `:cardscan:publishReleasePublicationToNexusRepository`.

MIT licensed. The iOS companion is [cardscan-ios](https://github.com/numesh/cardscan-ios).
