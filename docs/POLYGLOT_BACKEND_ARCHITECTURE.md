# Polyglot backends: same-host logic (Mode A) and decoupled backends (Mode B)

Status: **Mode B is implemented in `modules/bridge` (Stage 2-2b).**

- Tested on Clojure/JVM and on ClojureCLR 1.12.6 and master, with an in-process backend
  and with real child processes: the Java reference backend, and `backend.R`
  (R_VISUALIZATION_INTEGRATION.md).
- The C# reference backend builds and answers on .NET 8/10.
- The Dart reference backend is written but not run here (no Dart SDK).
- ClojureDart has the contract but no process transport yet (§9).

`bb init --logic backend` generates a Mode B app (README, "Start a project").

## 1. Two ways to place the logic

| | Mode A: same-host (default) | Mode B: decoupled backend |
|---|---|---|
| Where the logic runs | in the GUI process, as `.cljc` shared by every target | in a separate process, in any language that reads stdin and writes stdout |
| How the GUI reaches it | events and pure handlers; I/O through effects (`:storage/*`, `:http`, `:file/*`, timers) | the `:backend/call` / `:backend/stream` effects; answers come back as events |
| What it costs | nothing extra | a second runtime to ship, a process boundary per call (~a millisecond locally, plus serialization) |
| Choose it when | the app is written in Clojure (most apps) | the logic already exists in Java/C#/Dart/Python/R; a heavy or crash-prone engine must not take the UI down; a GPL tool must stay a separate program; a team owns the engine in another language |

The views, the store and the events are the same in both modes. A Mode A app can
become Mode B one call at a time. `cloview.bridge.loopback` runs a Clojure function as
the backend over the real wire protocol, so the contract can be prototyped in
`.cljc` and moved to another language later without touching the views.

## 2. Packaging verdict: an opt-in module in this repository

| Option | Verdict |
|---|---|
| Inside `modules/fx` | **No.** Every app loads `fx`. Process spawning, threads and a protocol client would ship in every Mode A app and widen fx's host surface. |
| **Opt-in `modules/bridge`** | **Chosen.** It depends only on `core` and `fx` and is added by the apps that need it (`bb init --logic backend` does this). Its tests run in the same harness on every host, and the protocol and the effect contract version together. |
| A companion library in its own repository | **Not now.** The protocol (`cloview-bridge/1`), the effect shapes and the reference backends must change in lockstep with the store and effects; separate releases would add a compatibility matrix for no user benefit. Revisit once the protocol is frozen and has outside implementers. |

The foundational effect hooks for Stage 3 are in place: `:backend/call`,
`:backend/stream`, `:backend/cancel`, `:backend/start` and `:backend/stop` (§4).

## 3. The wire protocol, `cloview-bridge/1`

**Transport.**

- Newline-delimited JSON: one JSON-RPC 2.0 message per line, UTF-8 without BOM.
- Requests go GUI → backend on the backend's **stdin**; responses and notifications go
  backend → GUI on its **stdout**.
- **stderr** is free-form log text; the GUI forwards it to `:on-log`.
- JSON escapes newlines inside strings, so a message is always exactly one line.
- Request ids are integers chosen by the GUI. Responses may come in any order.
- Lines longer than `max_message_bytes` (default 16 Mi) are skipped and logged, and the
  request that expected them ends with `:timeout`. The launcher counts decoded
  characters, not bytes, so for non-ASCII text the limit is looser than its name.

**Reserved methods** (prefix `$/`):

| Method | Kind | Direction | Params → result |
|---|---|---|---|
| `$/initialize` | request | GUI → backend | `{protocol, app}` → `{protocol, name, version, methods}`; the first message, answered before anything else |
| `$/shutdown` | request | GUI → backend | → `null`. Take no new work, let the requests in flight finish and answer them, then answer and exit |
| `$/ping` | request | GUI → backend | → `"pong"` (health check, optional) |
| `$/cancel` | notification | GUI → backend | `{id}`: best effort; the GUI has already reported the request as cancelled |
| `$/progress` | notification | backend → GUI | `{id, value}`: for `:on-progress` |
| `$/stream` | notification | backend → GUI | `{id, item}`: for `:on-item` |
| `$/log` | notification | backend → GUI | `{level, message}`: for `:on-log` |

**Error codes.** The standard JSON-RPC codes, plus the bridge's own codes in the
implementation-defined range:

| Code | Reason | Meaning |
|---|---|---|
| -32700 / -32600 / -32601 / -32602 / -32603 | `:parse-error` `:invalid-request` `:method-not-found` `:invalid-params` `:internal-error` | standard |
| -32001 | `:cancelled` | cancelled by the app, or ended because the backend is stopping |
| -32002 | `:timeout` | no answer within the request timeout |
| -32003 | `:not-running` | the backend is stopped, crashed and not restarted, not installed, or could not start (for example, its program is missing; `:error` says why) |
| -32004 | `:crashed` | the backend exited while the request was pending |
| -32005 | `:too-large` | reserved, not produced yet: an oversized line cannot be matched to its request, so that request ends with `:timeout` |
| -32006 | `:unsupported` | no process transport on this host yet |
| any other | `:rpc` | an application error of the backend; its `message` and `data` reach the app |

**Rules for every backend.**

1. Answer `$/initialize` with `{protocol, name, version, methods}`.
2. Answer each request exactly once, with a result or an error, in any order.
3. Write nothing but protocol messages to stdout. Logs go to stderr.
4. On `$/shutdown`: take no new work; let the requests in flight finish (long ones treat
   the stop like a cancellation) and answer them; then answer the shutdown and exit.
   Also exit when stdin reaches EOF: the GUI is gone, so a backend never outlives its
   GUI, even when the GUI is killed.

Rule 4's drain was added in Stage 2-2b after a generated C# project lost the answer of
a request that was still running when `$/shutdown` arrived. The Java, C# and Dart
reference backends implement it. The Java and C# ones were checked with a request in
flight at shutdown (it was answered with -32001 before the shutdown reply), by review
scripts that are not part of the repository's tests; the Dart one has not been run
(§7). The reference backends wait at most 2 s for requests in flight. A request still
running then gets no answer, and the GUI fails it with `:not-running` when the process
exits.

## 4. Effects and events

```clojure
(bridge/install! {:manifests [(manifest/parse (slurp "backend/backend-manifest.json"))]
                  :vars {:appDir app-dir :dataDir data-dir :tempDir tmp}
                  :dispatch! #(st/dispatch! store %)
                  :on-status [:app/backend-status]            ; (conj ev backend-id status info)
                  :on-log (fn [id {:keys [level message]}] ...)
                  :app-info {:name "wmark" :version "1.2.0"}})

;; a handler
{:backend/call {:backend :engine                  ; optional with one backend
                :method "wm/remove" :params {:path p}
                :id [:job 42]                     ; optional, to cancel it
                :timeout-ms 600000                ; optional
                :on-success [:job/done]           ; (conj ev result)
                :on-failure [:job/failed]         ; (conj ev failure)
                :on-progress [:job/progress]}}    ; (conj ev value) per $/progress
```

| Effect | Value |
|---|---|
| `:backend/call` | as above; a vector of calls is allowed |
| `:backend/stream` | like `:backend/call`, plus `:on-item` per `$/stream` item; `:on-success` gets the final result |
| `:backend/cancel` | `{:backend :engine :id [:job 42]}`: `:on-failure` gets `:cancelled` at once, and the backend receives `$/cancel` |
| `:backend/start` | `{:backend :engine}`: start an on-demand backend, or revive a crashed one |
| `:backend/stop` | `{:backend :engine}` |

How results and failures reach the app:

- **Fallback events.** Without `:on-success` / `:on-failure`, results arrive as
  `[:backend/response {:backend :method :id :result}]` and failures as
  `[:backend/error {...failure... :backend :method :id}]`.
- **Failure map.** `{:error "message" :code n :reason r :data d}`, with the reasons of
  §3. A backend that could not start reports why to every call: `:unsupported` (no
  process transport on this host) or `:manifest` (for example an unresolved
  placeholder), with the details in `:data`.
- **Status events.** `(conj on-status backend-id status info)`, where status is one of
  `:starting :ready :stopping :stopped :crashed :restarting`.

The mapping is exercised end to end by `bridge_test.cljc` (effects through a store),
`process_test.cljc` (a real Java child process) and `rplot_test.cljc` (`:r/plot`, built
on `:backend/call`, against real R).

## 5. `backend-manifest.json`

```json
{
  "manifest_version": 1,
  "id": "engine",
  "name": "Watermark engine",
  "protocol": "cloview-bridge/1",
  "command": {
    "windows": ["${appDir}/backend/engine.exe"],
    "default": ["java", "-jar", "${appDir}/backend/engine.jar"]
  },
  "cwd": "${appDir}/backend",
  "env": {"ENGINE_LOG": "${dataDir}/engine.log"},
  "start": "on-launch",
  "startup_timeout_ms": 15000,
  "request_timeout_ms": 60000,
  "shutdown_timeout_ms": 3000,
  "restart": {"policy": "on-crash", "max_restarts": 3, "window_ms": 60000},
  "max_message_bytes": 16777216
}
```

| Field | Default | Rule |
|---|---|---|
| `manifest_version` | required | `1` |
| `id` | required | lower-case letters, digits and dashes; the `:backend` keyword in effects |
| `protocol` | required | `"cloview-bridge/1"` |
| `command` | required | platform (`windows` `macos` `linux` `android` `ios`) or `default` → non-empty array of strings. Run directly, **never through a shell**, so arguments need no quoting. |
| `cwd`, `env` | none, `{}` | strings; `env` is added to the GUI's environment |
| `transport` | `"stdio"` | `"tcp"` is reserved for a later version |
| `start` | `"on-launch"` | or `"on-demand"`: started by the first call |
| `startup_timeout_ms`, `request_timeout_ms`, `shutdown_timeout_ms` | 15000, 60000, 3000 | positive integers |
| `restart` | `{"policy": "on-crash", "max_restarts": 3, "window_ms": 60000}` | policy `"never"` or `"on-crash"` |
| `max_message_bytes` | 16777216 | positive integer |

**Placeholders.** `${appDir}`, `${dataDir}`, `${tempDir}`, `${platform}`, plus any
`:vars` the app passes. They are substituted in `command`, `cwd` and `env`. An
unresolved placeholder is an error at start (reason `:manifest`), never a literal path.
`cloview.bridge.manifest/problems` lists every violation; `parse` / `normalize` throw
with all of them.

## 6. Launcher lifecycle

```
idle ──start──▶ starting ──$/initialize answered──▶ ready ──stop──▶ stopping ──exit──▶ stopped
                   │  (startup timeout,                 │
                   │   process exits)                   ▼ (process exits unexpectedly)
                   ▼                                    │
                crashed ◀───────────────────────────────┘
                   │ policy on-crash, < max_restarts in window_ms
                   ▼
               restarting ──after 0.5 s · 2^n (≤ 10 s)──▶ starting

starting ──program cannot start (missing, unsupported host, bad manifest)──▶ crashed (no restart)
```

- **Start.** The launcher spawns the command with piped stdio and sends `$/initialize`
  with the startup timeout. Calls made before `:ready` are queued and sent in order once
  it is ready. When the program cannot be started at all, no process exists to restart:
  the backend stays `:crashed`, and calls fail with `:unsupported`, `:manifest` or, for
  any other cause (a missing program), `:not-running` with the cause in `:error`.
- **Requests.** Each request has a timeout: the manifest's, overridable per call. On
  expiry the app gets `:timeout`, the backend gets `$/cancel`, and a late answer is
  dropped.
- **Cancel.** `:backend/cancel` fails the request with `:cancelled` immediately and
  sends `$/cancel`. Backends that check it end the work early (the reference
  `demo/count` does).
- **Stop.** `:stopping` is set first, then `$/shutdown` is sent (with
  `shutdown_timeout_ms`) and stdin is closed (EOF). If the process is still alive
  500 ms after the shutdown timeout, the launcher kills it with its descendants: on the
  JVM through `ProcessHandle.descendants`, on .NET with `Process.Kill(true)`. Requests
  still pending fail with `:not-running`.
- **Crash.** Pending requests fail with `:crashed`, and the restart policy applies with
  exponential backoff. A manual `:backend/start` revives a crashed backend at any time;
  a `:backend/stop` during the backoff cancels the restart, and the status becomes
  `:stopped`.
- **App exit.** A JVM shutdown hook or the CLR's `ProcessExit` handler calls
  `shutdown-all!` with a 3 s budget. App shells may call it when the main window
  closes.
- **No orphans.** A GUI killed without a chance to clean up still closes the pipes, and
  the backend exits on EOF (rule 4).

## 7. Reference backends (`modules/bridge/backends`)

The Java, C# and Dart backends implement `math/add`, `text/upper` and `demo/count`
(progress, stream items, cancellation) with no dependencies beyond the language's
standard library. `backend.R` implements `plot/render` and `plot/list` and needs
jsonlite and the plotting packages (R_VISUALIZATION_INTEGRATION.md).

| Backend | Runs with | Tested here |
|---|---|---|
| `java/Backend.java` | `java Backend.java` (JDK 11+ single-file launch), or packaged as a jar | ✅ through the launcher on JVM and CLR (`process_test`), and in a generated Mode B app |
| `csharp/` | .NET 8+ (`System.Text.Json`); `dotnet build`, or `dotnet publish` as one exe | ✅ builds offline; answers and drains on shutdown |
| `dart/bin/backend.dart` | `dart backend.dart` (dart:io only). On Windows, Flutter's `dart` is `dart.bat`, which a launcher cannot start without a shell: use `cmd /c dart …` (what `bb init` generates) or `dart compile exe` | ❌ not run (no Dart SDK in the sandbox) |
| `r/backend.R` | `Rscript --vanilla backend.R` (jsonlite) | ✅ with R 4.3.3 through the launcher on JVM and CLR (`rplot_test`) |

## 8. Prototyping in-process

`cloview.bridge.loopback/transport-fn` turns a Clojure function
`(fn [{:keys [method params]} {:keys [respond! fail! notify!]}] ...)` into a transport.
Every message is still encoded to a line and decoded again, so the protocol code runs
exactly as with a process. Generated Mode B apps use it in their tests
(`<app>.core/backend-contract`). It also lets a team write a method in `.cljc` first and
move it into the backend's language later.

## 9. Host support and Stage 3

| Host | Status |
|---|---|
| Clojure/JVM | ✅ `ProcessBuilder`; daemon reader threads; descendants killed on stop |
| ClojureCLR | ✅ `ProcessStartInfo.ArgumentList`; UTF-8 pipes; `Kill(true)` |
| ClojureDart | contract only: `process/spawn` throws `{:reason :unsupported}`, and calls fail with `:unsupported`. Stage 3: `dart:io` `Process.start` (Flutter desktop) |

Stage 3 items:

1. The Dart process transport, then R plots and the Dart reference backend on Flutter.
2. A run on real Windows of `process_test`, `rplot_test` and a generated Mode B app;
   confirm that `Kill(true)` reaches grandchildren there.
3. The `tcp` transport for backends that already run as services.
4. A conformance kit: the request-during-shutdown, cancel and oversize checks as a
   script any third-party backend can run.

## 10. Security notes

- **Commands.** The manifest ships with the app and is not user input. Commands are
  executed without a shell, so placeholders cannot inject shell syntax.
- **Clean stdout.** A backend that prints anything else to stdout corrupts nothing: the
  line is reported through `:on-log` as invalid and ignored.
- **Memory.** `max_message_bytes` bounds the memory a misbehaving backend can make the
  GUI allocate.
- **Secrets.** Anything put in `env` is visible to the child process and to tools that
  list process environments. Pass secrets through a request instead.
