# CardScan for Android

For architecture, integration, scan rules, security boundaries, testing, and next steps, see the [developer guide](docs/DEVELOPER_GUIDE.md).

Two-module **Kotlin and Jetpack Compose** Android card-scanning project:

- `cardscan`: reusable Android Library module, published as an AAR or Maven artifact.
- `demo`: separate Android application that depends on `cardscan`.

Both modules follow MVVM: Compose screens render immutable `StateFlow` UI state and send typed events to their ViewModels. Activities handle Android camera, permission, and navigation APIs, then consume one-time effects such as scanner launch and scan completion. See the developer guide for the event/state flow.

The project uses Google's AndroidX CameraX and bundled ML Kit Latin text recognition model. Both screens and all project source/tests are Kotlin; the live CameraX `PreviewView` is hosted in Compose with `AndroidView`. All direct camera, OCR, and UI dependencies are Google-published AndroidX or ML Kit artifacts; their published dependency graphs also include Kotlin and Java support artifacts. The demo scans card numbers and expiry dates on device, then returns editable values to the form. CVV remains manual, and there is no payment submission path.

The demo form and camera overlay provide an add-card flow with a live scanner. The Android system provides the live status bar and keyboard, so their appearance varies by device.

CameraX `PreviewView` center-crops the preview without stretching, so a card keeps its natural proportions. CameraX `MlKitAnalyzer` feeds live frames to ML Kit and maps text bounds to preview coordinates for the card guide. The scanner shows detected digits and expiry on the preview. Invalid or expired expiry text appears in red alongside a red warning, as do number validation failures and unsupported card types. Warnings remain visible briefly so the user can read them; unreadable text gets neutral positioning and lighting guidance. Tapping the preview asks CameraX to focus at that point and briefly shows a focus marker. The Figma flash icon stays visible at the top right; tapping it explains when a device has no flash. The demo then fills the Add card form with the confirmed number and any valid expiry read from the card.

OCR candidates are restricted to the card guide, checked for Luhn validity and Visa, Mastercard and Amex prefixes/lengths, and confirmed across three frames. These are the only card types this sample scanner accepts. Analysis requests a 1280×720 stream so the smaller expiry print has enough pixels when supported by the camera. The sample does not guess missing digits. Expiry must be a valid future date; the scanner accepts common slash, dash, dotted, and spaced formats, including text split into nearby OCR lines. It retains a confirmed number while scanning expiry in later frames, even when OCR stops seeing the number. If expiry remains unreadable after twelve seconds, only the number is returned and the form asks for manual expiry. The production host must replace the sample card-network rules with its actual payment SDK rules.

When glare hides part of a number, the scanner can retain a short OCR fragment and ask the user to tilt the card. It combines two fragments only when each was read in at least two frames, they overlap by six or more digits, and the resulting complete number is unique, passes Luhn, and belongs to a supported card type. Fragments expire after a five-second gap, stay only in memory, and are never returned as an incomplete card number. The user should still review the result on the Add card form; manual entry remains available for cards that cannot be read reliably.

## Install version 1.0.0

The release is published to GitHub Packages. Add the repository to your dependency resolution settings. GitHub requires a username and a classic personal access token with `read:packages`, including for public Maven packages; keep these values outside source control.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/numesh/cardscan-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

```kotlin
dependencies {
    implementation("com.andronuke:cardscan:1.0.0")
}
```

Set `gpr.user` and `gpr.key` in the user-level `~/.gradle/gradle.properties`, never the project file. You can also download the AAR from the GitHub `v1.0.0` release, but the Maven package is recommended because its POM includes transitive dependencies.

## Build from source

Open the root directory in Android Studio and build `:demo:assembleDebug` and `:cardscan:assembleRelease` with Android SDK 36 installed. For a private Nexus Maven repository, set `NEXUS_URL`, `NEXUS_USERNAME`, and `NEXUS_PASSWORD`, then run `:cardscan:publishReleasePublicationToNexusRepository`. No repository credentials are stored here.

## Security boundary

- Use synthetic test cards for evaluation; the demo has no payment submission path.
- Camera frames and OCR output are processed in memory. The app does not save or log them.
- The camera preview and demo screen use `FLAG_SECURE`.
- The demo disables Android backup and cleartext traffic.
- Camera permission is requested at runtime. Denial leaves manual entry available.
- Google's ML Kit model is bundled for offline first-use recognition. Review ML Kit's terms and metrics disclosures before production use.
- This sample has not undergone a penetration test or PCI assessment. Production integration must be reviewed against the payment SDK's protected-field and tokenization requirements.

## Library API

The Kotlin demo launches `CardScanContract.createIntent(context)` with the Activity Result API and parses the outcome using `CardScanContract.parse(resultCode, data)`. The older `CardScanContract.start(activity, requestCode)` entry point remains available to existing hosts. The result contains a number and, when reliably read, an expiry. The host should show both values for review and use its existing protected payment flow only after the user explicitly submits.
