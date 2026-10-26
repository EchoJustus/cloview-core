# Roadmap and extensions

Where cloview stands at the end of Stage 2-2b, what Stage 3 should do, and what is
deliberately left for later. Store packaging (§4) is **Future / Non-Priority for V1**:
it is documented so the V1 design does not block it, not because V1 needs it.

## 1. V1 at the end of Stage 2-2b

| Area | JavaFX (Clojure/JVM) | WPF (ClojureCLR) | Flutter (ClojureDart) |
|---|---|---|---|
| Canonical views, store, reconcile | ✅ tested on Linux and Windows | ✅ tested on Windows (real WPF), and on Linux with stand-ins that are not shipped | ✅ Greeter widget tests passed on Windows (Flutter 3.47.8, 2026-10-25) |
| Fitness example (now in [cloview-examples](https://github.com/echojustus/cloview-examples)) | ✅ 37/37 oracles + GUI smoke | ✅ 37/37 oracles + GUI smoke | ✅ 47 Dart tests + 37/37 oracles on Windows (2026-10-25) |
| `modules/bridge` (Mode B backends) | ✅ incl. real Java and R child processes, on Linux and on Windows | ✅ same, on ClojureCLR 1.12.6 and master, and in the real WPF host on Windows | contract only: the process transport is Stage 3 |
| `cloview.bridge.rplot` (R plots) | ✅ with real R 4.3.3 (Linux) and R 4.6.1 (Windows) | ✅ with real R 4.3.3 (Linux) and R 4.6.1 (Windows, in the real WPF host) | Stage 3, with the process transport |
| `modules/licensing` | ✅ Linux and Windows | ✅ Linux and Windows (real host) | `install!` refuses: the crypto is Stage 3 |
| `bb init` project initializer (README, "Start a project") | ✅ generated projects tested (tests in a child JVM, entry points load against cljfx); the initializer's own tests pass on Windows | ⚠️ generated entry points run on ClojureCLR against WPF stand-ins, which exist only in the build sandbox and are not shipped (STAGE_2_2_DECISIONS.md item 39); a real `bb init` → `bb dev` on Windows is still to do | generated and checked statically; the ClojureDart compile of a generated project awaits a Windows run |

Version baseline to keep in mind:

- **JavaFX.** 17 is an LTS line "until Oct 2026", 21 and 25 are LTS, and 27 went GA in
  Sep 2026 and needs JDK 25 ([Gluon](https://gluonhq.com/products/javafx/), read
  2026-10-25). cloview pins no JavaFX version: `modules/javafx` uses the JavaFX that
  cljfx 1.10.363 brings for the running JDK (`modules/javafx/deps.edn`). On JDK 11 the
  newest LTS line is 17, so new apps should run on **JDK 21 or newer**.
- **.NET 10** is LTS until 14 Nov 2028
  ([policy](https://dotnet.microsoft.com/en-us/platform/support/policy/dotnet-core)).
- **Flutter 3.47** supports Windows 10/11, macOS 12–27 and current Debian/Ubuntu LTS
  ([platforms](https://docs.flutter.dev/reference/supported-platforms)).

## 2. Stage 3 work list

Ordered by what unblocks app authors first. Each item names the document that holds its
details.

### P1: close the verification loop

0. **CI green** (DEVELOPMENT_WORKFLOW.md §2, §8): `ci.yml` on pushes to `main` and on
   pull requests, then the
   `windows-harness.yml` run on a GitHub-hosted Windows runner. Neither has run yet; the
   repository was not on GitHub when they were written.
1. **The Windows checks the harness does not cover yet** (WINDOWS_VERIFICATION_REPORT.md
   §8.5): `bb init` → `bb test` / `bb test:clr` / `bb dev` for each target, the Dart
   reference backend, and visual checks; R with `jsonlite` is covered since 2026-10-26
   (windows-harness). The Stage 2-2b run itself passed every step. Tickets for them are
   ready in `ops/jobs/` (DEVELOPMENT_WORKFLOW.md §8).
2. **ClojureDart process transport** (`dart:io` `Process.start`) for
   `cloview.bridge.process`. Then the bridge, R plots and the Dart reference backend
   work on Flutter desktop (POLYGLOT_BACKEND_ARCHITECTURE.md §9).
3. **ClojureDart licensing crypto** (`package:crypto` + pointycastle), run against the
   same test vectors (LICENSING.md §12).
4. **Distribution of the modules** (open questions 8 and 11). Today generated apps use
   `:local/root` paths to a checkout, recorded in `cloview.edn` under `:cloview {:root …}`
   (STAGE_2_2_DECISIONS.md item 51). Git dependencies with `:deps/root`, and later
   Clojars / NuGet / pub.dev releases, change only what `bb init` writes there (README,
   "Using the modules without `bb init`").

### P2: capabilities apps are asking for

5. `:file/pick` on Flutter (`file_picker`); a visible missing-media state through an
   `:on-error` on `:ui/image` (STAGE_2_2_DECISIONS.md item 25, open question 10).
6. Media and 3D: the `:fitness/media-player` renderers, and the 3D body model
   ([`NATIVE_CONTRACTS.md`](https://github.com/echojustus/cloview-examples/blob/main/docs/NATIVE_CONTRACTS.md)
   §3.5, §8, in cloview-examples).
7. `:ui/pressable` (open question O3).
8. LAN-sync network runners
   ([`FITNESS_CAPABILITY_MAP.md`](https://github.com/echojustus/cloview-examples/blob/main/docs/FITNESS_CAPABILITY_MAP.md)
   §4.2, in cloview-examples).
9. Licensing: `:revoked` lists, store purchase providers behind `wrap-gated-event`, an
   optional keychain mirror of trial state (LICENSING.md §12).
10. R: Windows and macOS runs with a bundled portable R, and a `bb` task to assemble it
    (R_VISUALIZATION_INTEGRATION.md §9).
11. Bridge: the `tcp` transport (the manifest key is reserved) for backends that already
    run as services.

### P3: developer experience

12. `bb init` templates published with the modules, `bb target:add` for Flutter
    projects with existing native code, and an upgrade note per release.
13. Host smoke checks for the licence dialog and plot views on all three hosts (test
    ids `license-*`, `r-plot-*`).

## 3. Extensions considered and not planned for V1

| Idea | Status | Why |
|---|---|---|
| Avalonia 12 or WinUI 3 as the CLR target | deferred | WPF on net10.0-windows ships today, needs no XAML toolchain, and is "supported and continues to receive feature, performance, accessibility, and Fluent-style improvements" ([FAQ](https://learn.microsoft.com/en-us/windows/apps/get-started/windows-developer-faq)). Microsoft recommends WinUI 3 for new general-purpose apps, so revisit it if WPF becomes a liability. Avalonia would bring CLR apps to macOS/Linux, which JavaFX and Flutter already cover (docs/research/02). |
| WinForms | rejected | no layout model close to the canonical vocabulary (docs/research/02) |
| A web (ClojureScript/DOM) host | not planned | cloview is native-first. The shared `.cljc` would compile, and a DOM adapter would be a fourth host, but it would compete with mature ClojureScript UI libraries. |
| Mobile-specific effects (permissions, health data, purchases) | after Stage 3 | The Flutter host compiles for mobile already. The effects need platform channels and store policies (§4, LICENSING.md §8). |
| Embedding R in-process | not planned | See R_VISUALIZATION_INTEGRATION.md §2 (UI isolation, GPL linking, per-host bindings). |

## 4. Future / Non-Priority for V1: store packaging

V1 apps are distributed directly: a zip, an installer, a DMG or an AppImage built with
each toolkit's own tools:

- **JavaFX:** `jpackage`;
- **WPF:** `dotnet publish`;
- **Flutter:** `flutter build`.

Store submission adds a sandbox, signing and review on top. Nothing in the V1 design
blocks it. The licensing module already separates direct builds from store builds
(LICENSING.md §8), and all file access goes through effects, which is what a sandbox
needs.

Facts below were checked on 2026-10-25; store policies change, so re-check them before
a submission.

### 4.1 Microsoft Store

- **Formats.** MSIX (`.msix`, `.msixbundle`); Microsoft recommends `.msixupload`, which
  bundles the packages with symbols. The Store re-signs MSIX packages, so no own
  certificate is needed
  ([packaging](https://learn.microsoft.com/en-us/windows/msix/package/packaging-uwp-apps),
  [distribution paths](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/choose-distribution-path)).
- **Plain `.msi`/`.exe` installers** are also allowed for non-game apps
  ([Store Policies §10.2.9](https://learn.microsoft.com/windows/uwp/publish/store-policies),
  v7.20). The requirements:
  - a versioned HTTPS URL whose binary never changes;
  - a silent, standalone install;
  - every PE file signed with a certificate from a Microsoft Trusted Root Program CA;
  - updates handled by the publisher.
- **Commerce.** Non-game PC apps "may either use a secure third-party purchase API or the
  Microsoft Store in-product purchase API" (§10.8.1, v7.20, read 2026-10-25). The policy
  does not mention licence keys. Our reading is that a sale through your own shop,
  unlocked with a key, counts as such a third-party purchase; on that reading this is the
  one store where `modules/licensing` applies as is.
- **Per target:**

  | Target | Route |
  |---|---|
  | WPF | [Windows Application Packaging Project](https://learn.microsoft.com/en-in/windows/msix/desktop/vs-package-overview) or [MakeAppx](https://learn.microsoft.com/en-gb/windows/win32/appxpkg/make-appx-package--makeappx-exe-) + SignTool |
  | JavaFX | `jpackage` to MSI (needs WiX), then the [MSIX Packaging Tool](https://learn.microsoft.com/windows/msix/mpt-overview); or submit the signed MSI under §10.2.9 |
  | Flutter | [`msix` 3.18.0](https://pub.dev/packages/msix): `dart run msix:create`, with `store: true` signed by the Store |

- **Registration.** Individual developer registration has been free since 10 Sep 2025
  ([Windows blog](https://blogs.windows.com/windowsdeveloper/2025/09/10/free-developer-registration-for-individual-developers-on-microsoft-store/)).

### 4.2 Apple

- **Mac App Store (Guideline 2.4.5).**
  - Apps are sandboxed and "packaged and submitted using technologies provided in Xcode;
    no third-party installers allowed".
  - They may not "require license keys, or implement their own copy protection", and
    they update only through the store
    ([guidelines](https://developer.apple.com/app-store/review/guidelines/)).
  - Features are unlocked with in-app purchase (3.1.1).
- **Package and upload.** Build a signed `.pkg` with `productbuild` + `productsign` (Mac
  Installer Distribution certificate). Upload with Xcode, Transporter, altool or the App
  Store Connect API
  ([Flutter macOS release](https://docs.flutter.dev/deployment/macos),
  [upload builds](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds)).
- **iOS / iPadOS** is reachable from the Flutter target only: `flutter build ipa`
  produces an `.xcarchive` and an `.ipa`
  ([Flutter iOS](https://docs.flutter.dev/deployment/ios)).
- **Outside the store** (the V1 route for macOS):
  - Developer ID signing, Hardened Runtime and a secure timestamp, then notarization with
    `notarytool`; altool notarization ended 1 Nov 2023
    ([notarization](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution));
  - own licence keys are fine there.
- **JavaFX on the Mac App Store** is risky:
  - `jpackage --mac-app-store` exists since JDK 17
    ([man page](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html)).
    JDK 21's jpackage rejects a Mac App Store runtime that contains `bin/` and asks for
    jlink's `--strip-native-commands` (seen in JDK 21.0.12's jpackage resources).
    Background: [JDK-8286122](https://bugs.openjdk.org/browse/JDK-8286122), "App bundle
    cannot upload to Mac App Store due to info.plist embedded in java exe".
  - Apple rejected an app that bundled JavaFX's `libjfxwebkit` for a non-public symbol
    ([openjfx-dev](https://mail.openjdk.org/pipermail/openjfx-dev/2024-June/047388.html)).
  - jpackage cannot add a TestFlight provisioning profile
    ([JDK-8274346](https://bugs.openjdk.org/browse/JDK-8274346)).
  - Prefer the Flutter target for Mac App Store builds.
- **Flutter macOS builds are sandboxed by default.** Network access needs
  `network.client` and file pickers `files.user-selected.*`
  ([doc](https://docs.flutter.dev/platform-integration/macos/building)).

### 4.3 Linux

- **Flathub.**
  - Apps are built from source with no network during the build, with validated MetaInfo
    and a desktop file.
  - Static permissions stay minimal, and where a portal exists "using the portal becomes
    mandatory"
    ([requirements](https://docs.flathub.org/docs/for-app-authors/requirements)).
  - A file picked through the file-chooser portal is accessible without filesystem
    permissions
    ([Flatpak](https://docs.flatpak.org/en/latest/sandbox-permissions.html)).
  - JavaFX precedent: Cryptomator ships on Flathub with JavaFX
    ([Flathub](https://flathub.org/apps/org.cryptomator.Cryptomator)). Flutter precedent:
    LocalSend ([Flathub](https://flathub.org/apps/org.localsend.localsend_app)).
  - Unverified: whether JavaFX's `FileChooser` uses the portal.
- **Snap.** Strict confinement is the default, with access through interfaces; classic
  confinement needs Store approval
  ([Snapcraft](https://snapcraft.io/docs/explanation/security/classic-confinement/index.html)).
  Flutter has a documented recipe
  ([Flutter Linux](https://docs.flutter.dev/deployment/linux)).
- **For cloview,** the `:file/pick` effect (and any future save dialog) should go through
  the portal on Flatpak. Today it uses the toolkit dialogs.

### 4.4 Google Play (Flutter target)

- **Billing.** Play Billing is required for unlocking "new features not available in the
  free version", except through enrolled programs
  ([policy](https://support.google.com/googleplay/android-developer/answer/9858738)).
  New service fees roll out by region through 30 Sep 2027
  ([fees](https://support.google.com/googleplay/android-developer/answer/16954621)).
- **Outside Play.** Developer verification applies from 30 Sep 2026 in Brazil,
  Indonesia, Singapore and Thailand, and globally in 2027
  ([Android](https://developer.android.com/developer-verification)).

### 4.5 What cloview would add (Stage 4+)

- `bb package:<target>` tasks that wrap `jpackage`, `dotnet publish` + MSIX and
  `flutter build`.
- A `:package` section in `cloview.edn` (app id, publisher, icons, entitlements).
- A store build flag that swaps licence keys for the store's purchase provider.

None of this changes the views, the store or the effects.
