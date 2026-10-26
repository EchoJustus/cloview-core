# Licensing: feature unlock and metered trials (`modules/licensing`)

Status: **initial module, Stage 2-2b.** It runs on Clojure/JVM and ClojureCLR. The
`licensing` suite (21 portable tests with 239 assertions, plus one JVM-only DER test)
passes on ClojureCLR 1.12.6, ClojureCLR master and the JVM, and the seller's key tool has
its own JVM test. ClojureDart support is a Stage 3 task (§11).

`cloview.licensing` lets a small app ship one binary with a free tier, a metered trial
of paid features, and offline licence keys that unlock them. There is no server, no
account and no network call: the seller signs keys with a private key that never leaves
their machine, and the app checks them with the matching public key.

```clojure
(require '[cloview.licensing :as licensing])

(licensing/install! {:product "wmark"
                     :public-keys {"2026-1" "BAUacvnZ...SOXsk"}   ; from `clojure -M:license keygen`
                     :trial-secret "wmark-trial-1"})
(st/dispatch! store [:licensing/init])     ; at every start, after the :storage/* effects

(def handlers
  {:events-fx
   {:pro/remove-watermark
    (licensing/wrap-gated-event :pro/remove-watermark {:mode :trial-count :max-uses 5}
      (fn [cofx [_ path]] {:backend/call {:method "wm/remove" :params {:path path}}}))}})

(defn root-view [db]
  [:ui/column {}
   ...
   (ui/trial-badge db :pro/remove-watermark {:mode :trial-count :max-uses 5})
   (licensing/modal db {:product-name "Watermark Pro" :on-buy [:app/open-shop]})])
```

Files: `src/cloview/licensing.cljc` (events, the wrapper, install), `licensing/gate.cljc`
(the pure decision), `token.cljc`, `crypto.cljc`, `device.cljc`, `trial.cljc`, `ui.cljc`;
the seller's tool is in `tool/cloview/licensing/issue.clj` and is never part of an app.

## 1. Scope

Use it for **direct distribution**: your website, a Developer ID–signed Mac app, a
Windows installer, a Linux tarball or AppImage, and the **Microsoft Store** (§8). Do not
use it in builds for the Apple App Store, the Mac App Store or Google Play. Those
stores require their own in-app purchase for unlocking features.

What it is not: DRM. JVM and .NET bytecode is easy to decompile, and anyone who patches
the app can remove a gate. The goal is to make honest purchase the easy path and casual
sharing inconvenient, without punishing paying users with online checks (§10).

## 2. The gate

`(licensing/wrap-gated-event feature gate handler)` wraps an ordinary event-fx handler.
The decision is a pure function, `cloview.licensing.gate/decide`, of the
`:cloview/licensing` slice of the db:

| Gate spec | Runs when | An unlicensed run |
|---|---|---|
| `{:mode :hard}` | a licence includes the feature | is refused (`:locked`) |
| `{:mode :trial-count :max-uses n}` | a licence includes it, or fewer than `n` trial uses so far | consumes one use, which is saved before the next event |

`decide` returns `{:allow? true :consume? bool}` or `{:allow? false :reason r}`. It checks
in this order:

1. **A licence that includes the feature** wins (`"*"` includes everything).
2. **`:loading`**: the stored state has not been read yet, so no licence is known either.
3. **`:locked`**: a hard gate.
4. **`:tampered`**: the trial state failed its check (§6).
5. **`:storage-failed`**: a trial count could not be saved in this session, so trial uses
   stop. Failing open would make the trial endless on read-only storage, because every
   restart would read the old count again.
6. **`:trial-exhausted`**.
7. **`:unknown-gate`**: an unknown `:mode`, which fails closed.

When the gate refuses, the handler does not run. The wrapper stores
`{:feature :reason :gate :event}` under `[:cloview/licensing :prompt]`, which opens the
dialog, and the event is **re-dispatched automatically** after a successful unlock that
includes the feature.

`:loading` is different: the event is **held**, not refused. Up to 16 events are kept in
order (`:pending`) and run as soon as `:licensing/loaded` or `:licensing/load-failed`
has run, whether or not the user closed the "Reading the licence data…" dialog. So a
click before the storage answers (possible on Flutter, where reads are async) is delayed
rather than lost. `:licensing/activate` and `:licensing/deactivate` are held the same
way, because saving before the read would overwrite the stored state, and the read
would then overwrite the new key.

A consumed trial use is merged into the handler's effects: `:db` gets the new count, and
a `:storage/put` saves it. If the handler also returns a `:storage/put`, the app's write
comes first and both run (`:storage/put` accepts a vector).

The `:cloview/licensing` slice belongs to the module:

- **The wrapper keeps it.** The slice is taken from the db the handler received, so a
  gated handler that returns a fresh db (`{:db {:doc :blank}}`) cannot drop it or save it
  back half-empty. Other handlers that replace the whole db must keep it themselves.
- **Gated handlers need the root db.** Register them without a `path` interceptor. The
  wrapper throws with an explanation when it finds no licensing state: `[:licensing/init]`
  not dispatched yet, a `path` interceptor, or a handler that dropped the slice.

Events (all registered by `install!`):

| Event | Use |
|---|---|
| `[:licensing/init]` | at start: puts the device code in the db, reads the stored state |
| `[:licensing/prompt]`, `[:licensing/prompt feature]` | open the dialog from a menu ("Enter licence key…") |
| `[:licensing/key-input s]`, `[:licensing/activate]`, `[:licensing/dismiss]` | the dialog |
| `[:licensing/deactivate]` | remove the licence from this machine, e.g. before moving it |
| `:licensing/loaded`, `:licensing/load-failed`, `:licensing/save-failed` | internal callbacks |

Activation outcomes:

- **Invalid key:** shows the reason in words (§3); nothing changes.
- **Valid key that does not include the prompted feature:** the licence is saved (it may
  unlock other features). The dialog stays open with "Your licence is active, but it does
  not include this feature", and the refused event is not re-run.

`(licensing/status db feature gate)` returns `{:licensed? bool :remaining n :reason r}` for
badges and menus:

- `:remaining` counts the trial uses that can run now: `nil` for other gates and while
  the stored state is read, and `0` when the trial is not usable (`:tampered`,
  `:storage-failed`);
- `:reason` is why the gate would refuse now, or `nil`.

## 3. Licence keys

```
CLV1.<payload>.<signature>
```

- **payload:** base64url (no padding) of a UTF-8 JSON object, written by
  `cloview.fx.codec/write-json`. Keys are in ordinal order and numbers are invariant, so
  every runtime produces the same bytes for the same map.
- **signature:** base64url of the 64-byte ES256 signature (IEEE P1363 `r‖s`) over the
  ASCII text `CLV1.<payload>`. Because the prefix is part of the signed input, a token
  can never be reinterpreted under another format version.
- **Pasting:** characters that cannot be part of a key are ignored: spaces and line wraps
  from email, quotes, Unicode spaces. The rule is one explicit character class, so a
  pasted key reads the same on every runtime (`\s` matches different characters on the
  JVM, .NET and Dart). Keys longer than 4096 characters are `:malformed`.
- **One spelling.** Both parts must be canonical base64url (no stray bits in the last
  character), so a key has no look-alike spellings in that respect. A token is still not
  a unique string, because ECDSA admits a second valid signature `(r, n−s)`; identify
  licences by the payload `id`, never by the token text.

Payload fields:

| Field | Required | Meaning |
|---|---|---|
| `v` | yes | `1` |
| `alg` | yes | `"ES256"` |
| `kid` | yes | key id; selects the public key embedded in the app (rotation, §9) |
| `id` | yes | licence id, for support, refunds and revocation lists |
| `product` | yes | must equal the app's `:product` |
| `features` | yes | `["pro/remove-watermark" …]` or `["*"]` |
| `licensee` | no | display name |
| `device` | no | `"D1-XXXX-XXXX-XXXX-XXXX"`; binds the key to one machine (§5) |
| `issued` | yes | `"YYYY-MM-DD"` |
| `expires` | no | `"YYYY-MM-DD"`, the last valid day (UTC); omitted for perpetual keys |

`cloview.licensing.token/verify` checks in this order and stops at the first failure. Each
failure has a message in the dialog:

| Reason | When |
|---|---|
| `:malformed` | not three dot-separated parts, wrong prefix, bad or non-canonical base64url, longer than 4096 characters, payload not a JSON object (or nested more than 4 levels) |
| `:unsupported` | `v` ≠ 1 or `alg` ≠ ES256 (a key for a newer app) |
| `:unknown-key` | `kid` not among the app's public keys |
| `:bad-signature` | the signature does not verify |
| `:wrong-product` | `product` differs |
| `:wrong-device` | bound to another device code (or this machine has none) |
| `:expired` | today (UTC) is after `expires` |

A stored key that stops verifying is reported as `:stored-key-problem` (for example,
it expired, the disk was moved to another machine, or an app version lacks its `kid`),
but it is never deleted: every save writes it back as it was. The user sees why, and an
app update with the right key list brings it back. Only activating another key or
`[:licensing/deactivate]` replaces or removes it.

## 4. Cryptography

`cloview.licensing.crypto` uses only what each runtime ships:

| | Clojure/JVM | ClojureCLR | ClojureDart |
|---|---|---|---|
| SHA-256, HMAC-SHA256 | `MessageDigest`, `Mac` | `SHA256`, `HMACSHA256` | Stage 3 (`package:crypto`) |
| ES256 verify/sign | `SHA256withECDSA`, DER ↔ P1363 conversion in the namespace | `ECDsa` with `ECParameters`, P1363 native | Stage 3 (`package:pointycastle`) |
| base64url, UTF-8 | `java.util.Base64`, `StandardCharsets` | `Convert` + alphabet swap, `Encoding.UTF8` | Stage 3 (`dart:convert`) |

Key formats are the same on every host:

- public key: 65 bytes, the uncompressed point `0x04‖X‖Y`;
- private key: 32 bytes, the scalar;
- signature: 64 bytes, `r‖s`.

A key signed on the JVM verifies on ClojureCLR and the reverse; both directions are
fixed vectors in the test suite.

**Why ES256 rather than Ed25519.** Ed25519 keys and signatures are smaller and simpler,
but neither desktop runtime gives it to us for free:

- **Java:** Ed25519 arrived in JDK 15 ([JEP 339](https://openjdk.org/jeps/339)). JDK 11's
  SunEC provides ECDSA on P-256
  ([JDK 11 providers](https://docs.oracle.com/en/java/javase/11/security/oracle-providers.html)).
- **.NET 10:** no built-in Ed25519. It appears in neither
  [What's new in .NET 10](https://learn.microsoft.com/en-us/dotnet/core/whats-new/dotnet-10/libraries)
  nor the
  [cross-platform cryptography](https://learn.microsoft.com/en-us/dotnet/standard/security/cross-platform-cryptography)
  page, while ECDsa P-256 works on every OS there. Ed25519 would need
  [NSec](https://www.nuget.org/packages/NSec.Cryptography) (libsodium) or
  [BouncyCastle](https://www.nuget.org/packages/BouncyCastle.Cryptography).
- **Dart:** [pointycastle](https://pub.dev/packages/pointycastle) has ECDSA on secp256r1.
  [cryptography](https://pub.dev/packages/cryptography) has pure-Dart Ed25519 but no
  pure-Dart P-256, so ES256 on Dart means pointycastle.

ES256 keeps the JVM and CLR paths dependency-free. The token header (`alg`, `v`) leaves
room for an Ed25519 variant later without breaking existing keys.

**jlink images.** On JDK 17–21, an image that omits the `jdk.crypto.ec` module has no
EC algorithms at all. Verification then fails closed: every key is reported as
`:bad-signature`. Add `--add-modules jdk.crypto.ec`. SunEC moved into `java.base` in JDK 22
([JDK-8308398](https://bugs.openjdk.org/browse/JDK-8308398), "Move SunEC crypto provider
into java.base", fixed in JDK 22).

## 5. Device binding

A bound key carries the customer's **device code**:

```
device-code = "D1-" + first 64 bits of HMAC-SHA256("cloview-device:" + product, machine-id)
              as 4 groups of 4 upper-case hex digits
```

The machine id is read once in `install!`; only the derived code goes into the db and
the dialog. Sources:

| OS | Source | Notes |
|---|---|---|
| Windows | `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid`, read with `reg.exe … /reg:64` | Not documented by Microsoft. Community answers say it is created at install, reset by Sysprep /generalize, and copied by clones that were not generalized ([Q&A](https://learn.microsoft.com/en-us/answers/questions/1489139/identifying-unique-windows-installation)). |
| macOS | `IOPlatformUUID` (`ioreg`) | Derived by the kernel from firmware ([xnu](https://opensource.apple.com/source/xnu/xnu-6153.101.6/iokit/Kernel/IOPlatformExpert.cpp.auto.html)); not a documented API. |
| Linux | `/etc/machine-id`, else `/var/lib/dbus/machine-id` | [machine-id(5)](https://www.freedesktop.org/software/systemd/man/latest/machine-id.html) calls it confidential and asks apps to use a keyed hash with an application-specific key, which is exactly what the code does. |

Privacy:

- **Unlinkable per product.** The code is keyed by the product, so two products on the
  same machine see unrelated codes, and the code cannot be turned back into the id.
- **Still an identifier.** It identifies a device to you, the seller. Under GDPR
  [Recital 30](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679),
  device identifiers can be personal data, so mention device-bound licences in your
  privacy notice. Collect the code only when the customer chooses a bound licence, and
  only by them sending it to you.
- **Apple platforms.** Apple forbids fingerprinting on its platforms "regardless of
  whether a user gives your app permission to track"
  ([required-reason APIs](https://developer.apple.com/tutorials/data/documentation/bundleresources/describing-use-of-required-reason-api.md)).
  The module does not read hardware ids on iOS. The Stage 3 mobile path uses the
  vendor-scoped ids each platform intends for this
  ([identifierForVendor](https://developer.apple.com/documentation/uikit/uidevice/identifierforvendor.md),
  [ANDROID_ID per signing key](https://developer.android.com/about/versions/oreo/android-8.0-changes)).

Without a readable machine id (some containers and minimal images), device-bound keys
cannot be activated; unbound keys and trials still work.

Recommendation: sell **unbound** keys by default. Device binding costs support time
whenever a customer reinstalls the OS or replaces hardware. Bind only where sharing is
a real problem, and offer a self-service rebind.

## 6. Trials

Trial use counts live in the app's storage (`cloview.fx.storage`, key
`:cloview.licensing/state`) together with the licence key:

```clojure
{:token "CLV1..." :trial {:v 1 :uses {"pro/remove-watermark" 3} :mac "<hex>"}}
```

- **The seal.** `:mac` = HMAC-SHA256 over the canonical JSON of the state, keyed by
  SHA-256(`trial-secret` | machine-id). The seal is checked on every start.
- **One shape.** Only the exact shape the module writes is accepted: the keys `:v`,
  `:uses`, `:mac` and an optional `:void true`; string feature ids; non-negative integer
  counts. The JSON writer encodes `:a` and `"a"` alike, so without this check an edit of
  the key types (a keyword feature id that reads as 0 uses, a `"void"` string key that
  reads as a fresh trial) would keep a valid seal.
- **Tamper-evident, not tamper-proof.** The secret ships inside the app, so a determined
  user can recompute the seal. It does stop the two common resets: editing the counts in
  the file, and copying the file from a machine with an unused trial.
- **Fails closed.** Only "nothing stored" counts as a first run. A seal that does not
  verify, a stored value without a well-formed `:trial`, or unreadable storage gives
  `:trial-ok? false`. Every trial gate then reports `:tampered` until a licence is
  activated.
- **Unsaved counts stop the trial.** When a save fails (read-only storage, full disk),
  the use that triggered it has run, and further trial uses in this session are refused
  with `:storage-failed`. A licence key still unlocks the features in this session
  (while the storage stays read-only, the key cannot be saved either).
- **No laundering.** From then on, every save writes the sealed `void-state`
  (`{:void true}`), never the edited counts. Activating and deactivating a licence cannot
  turn edited counts back into valid ones; the test suite covers this.
- **Known reset.** Deleting the storage file resets the trial, exactly like a fresh
  install. A count that survives reinstalls needs a server or an OS keychain (Stage 3
  option, §12).

## 7. Dialog and badges

`(licensing/modal db opts)`, or `cloview.licensing.ui/license-modal`, renders a
`:ui/modal` built only from canonical tags, so it needs no host code. It is open while
`[:cloview/licensing :prompt]` is set. It shows:

- the reason in words;
- a key field, with Unlock enabled once something is pasted;
- the error of the last attempt;
- the device code;
- optional Buy and "Not now" buttons.

Options: `:product-name`, `:title`, `:on-buy` (an event vector, e.g. one that opens your
shop URL with the OS), `:buy-label`. Data-only views can use the registered composite
`[:cloview/license-modal {:state (:cloview/licensing db) …}]`. Composites receive props,
not the db, hence `:state`.

`(ui/trial-badge db feature gate)` renders "3 free uses left" / "1 free use left" /
"No free uses left", or "Free uses unavailable" when the trial cannot be used
(`:tampered`, `:storage-failed`). It renders nothing when the feature is licensed or not
trial-gated, and while the stored state is being read.

All `test-id`s start with `license-` (`license-key`, `license-activate`, `license-buy`,
`license-dismiss`, `license-reason`, `license-error`, `license-device`) for host smokes.

## 8. Where licence keys are allowed

| Channel | Own licence keys | Source |
|---|---|---|
| Direct download (website), any OS | yes | — |
| macOS outside the store (Developer ID + notarization) | yes; App Review does not apply | [notarization](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution) |
| Microsoft Store, non-game apps | yes, as we read it: non-game PC apps "may either use a secure third-party purchase API or the Microsoft Store in-product purchase API". The policy does not mention licence keys; a sale through your own shop, unlocked with a key, is such a third-party purchase on our reading | [Store Policies §10.8.1](https://learn.microsoft.com/windows/uwp/publish/store-policies) (v7.20, read 2026-10-25) |
| Mac App Store | **no**: 2.4.5 forbids requiring licence keys or own copy protection | [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) |
| iOS / iPadOS App Store | **no**: 3.1.1 requires in-app purchase to unlock features, "Apps may not use their own mechanisms … such as license keys" | same |
| Google Play | **no**: Play Billing is required for "new features not available in the free version" (enrolled alternative-billing programs aside) | [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738) |

Notes on the edges, because they move:

- **Apple, US storefront.** Guideline 3.1.1(a) lets US storefront apps link out to web
  purchases. That frees the link, not the unlock: features unlocked inside the
  app still need in-app purchase.
- **Apple, EU (DMA).** Since 1 Oct 2026 one set of EU business terms applies: Attachment
  14 of the Apple Developer Program License Agreement superseded the Alternative Terms
  Addendum for Apps in the EU
  ([Apple DMA page](https://developer.apple.com/support/dma-and-apps-in-the-eu/), read
  2026-10-25).
- **Google Play.** New service fees roll out by region through 2027
  ([Play fees](https://support.google.com/googleplay/android-developer/answer/16954621)).
- **Re-check before shipping.** Every row in this table is a policy, not a law of
  nature; check the linked pages before a store release.

Practical rule: for store builds, compile the gates against the store's purchase API
(Stage 3: a `:purchase` provider behind the same `wrap-gated-event`), and keep licence
keys for the direct builds.

## 9. Seller workflow

```bash
clojure -M:license keygen --kid 2026-1                  # once; writes license-keys/2026-1.private.edn
clojure -M:license issue --key license-keys/2026-1.private.edn --product wmark \
        --features pro/remove-watermark --licensee "Ada Lovelace"
clojure -M:license issue ... --device D1-A457-7A9B-8B2D-A2D4 --expires 2027-10-24
clojure -M:license inspect CLV1.... --public <base64url>
clojure -M:license device-code --product wmark           # this machine's code
```

- **keygen** refuses to overwrite a key. On POSIX the file is created owner-only
  (`rw-------`) from the start, and a folder it creates is `rwx------`. The repository
  `.gitignore` excludes `*.private.edn` and `license-keys/`. Keep a backup offline; a
  lost private key means new keys under a new `kid`.
- **issue** validates its input before signing:
  - the product, without leading or trailing spaces (the app compares it exactly);
  - the features and the device code format;
  - real calendar dates (`2027-02-30` is refused), expiry not before issue.

  It then verifies the new key with the public key before printing it.
- **Unknown options are errors.** Each command accepts only its own options, and an
  option given twice or a stray argument is refused (exit code 2). A misspelt
  `--expire` would otherwise issue a perpetual key, and keys cannot be revoked yet.
- **Rotation.** Generate `2027-1`, ship an app version whose `:public-keys` has both
  kids, and issue new keys under `2027-1`. Old keys keep working for as long as their
  kid stays in the map.
- **Revocation** (refunds, leaked keys) needs no server either: ship a list of revoked
  `id`s with an app update. Stage 3 adds the `:revoked` option; the payload already
  carries the `id`.
- **Automating sales.** A web shop's order webhook can call the same `issue` function
  on a small JVM service and email the key. That service, and the private key it holds,
  are outside cloview.

## 10. Threat model and limits

| Attack | Result |
|---|---|
| Generate keys without the private key | Infeasible (ECDSA P-256) |
| Edit a payload (features, expiry, device) | `:bad-signature` |
| Share an unbound key | Works; that is what unbound means. Use device binding or `licensee` names (social deterrent) |
| Copy the storage file to another machine | Trial: `:tampered`. Bound key: `:wrong-device`. Unbound key: works |
| Edit trial counts, or the type of a key in the trial state | `:tampered`, sticky via `void-state` |
| Remove the `:trial` entry, or replace the stored value | `:tampered` (only "nothing stored" is a first run) |
| Delete the storage file | Trial resets (like reinstalling) |
| Make the storage read-only | Trial uses stop after the first unsaved one (`:storage-failed`) |
| Paste a crafted, deeply nested key | `:malformed` before the signature check; the JSON reader limits nesting, which matters on .NET, where a stack overflow ends the process |
| Set the clock back | Extends an expiring key. Offline checks cannot prevent this; sell perpetual keys with "updates for a year" instead of expiring keys if it matters |
| Patch or decompile the app | Removes any client-side check. Out of scope: obfuscation and native compilation raise the cost but never remove the possibility |

## 11. Status per runtime and tests

| | Clojure/JVM | ClojureCLR 1.12.6 / master | ClojureDart |
|---|---|---|---|
| Tokens, trials, device codes, gate, dialog | ✅ tested | ✅ tested | `install!` throws `{:reason :unsupported}` |
| Key tool (`tool/`) | ✅ tested | — (JVM tool) | — |

Tests: `clj -M:test licensing licensing-tool` (JVM). On ClojureCLR the `licensing` suite
runs with the other portable suites (`CloViewHost --run-tests`, and
`verify/run-windows-checks.ps1` step 04). What the suite covers:

- SHA-256 and HMAC against FIPS/RFC 4231 vectors;
- base64url;
- ES256 sign/verify (40 random signatures, and a JVM-only DER↔P1363 test);
- the JVM- and CLR-signed fixed tokens;
- every rejection reason;
- device codes;
- the trial seal, including EDN round trip, edits, another machine, void, and every
  shape the check must refuse (edited key types, extra keys, negative or fractional
  counts);
- every gate decision, including `:storage-failed`;
- the calendar against the host's, up to 2517;
- end-to-end flows through a real store with an EDN-round-tripping fake storage that can
  answer reads late or fail writes:
  - events held while the stored state is read, run in order after it;
  - activation while the stored state is read;
  - trial consumption and refusal, the dialog, a wrong then a right key, re-dispatch;
  - a licence without the feature, restart, deactivation;
  - tamper laundering, wrong-shape stored data, device binding;
  - stored keys that stopped verifying (expired, unknown `kid`) surviving saves;
  - a handler that returns a fresh db, failed saves, effect merging and badges.

## 12. Stage 3 work

1. **ClojureDart crypto**: SHA-256/HMAC via `package:crypto`, ES256 via pointycastle,
   base64url via `dart:convert`. Run the same test vectors, then lift the `install!`
   guard.
2. **Mobile device ids** via platform channels: identifierForVendor and ANDROID_ID, never
   hardware ids.
3. **`:revoked`** licence-id sets, and an optional `:on-check` hook for apps that do
   want an online check.
4. **Store purchase providers** (Microsoft Store, StoreKit, Play Billing) behind
   `wrap-gated-event`, selected per build target.
5. **Optional OS-keychain mirror of the trial state** (Windows DPAPI / macOS Keychain /
   libsecret) so a deleted storage file no longer resets the trial.
6. **Host smoke checks** for the dialog on JavaFX, WPF and Flutter, using the
   `license-*` test-ids.
