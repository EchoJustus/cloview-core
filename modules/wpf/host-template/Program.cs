// CloViewHost — the tiny [STAThread] C# host that runs cloview's ClojureCLR/WPF backend.
//
// Why a C# host: the stock Clojure.Main tool targets plain netX.0 (no
// Microsoft.WindowsDesktop.App framework reference), so WPF assemblies are not reliably
// loadable from it. This exe targets netN.0-windows with UseWPF, references the
// `Clojure` NuGet package, and calls into Clojure through clojure.clr.api.Clojure.
//
// Source resolution is deterministic: CLOJURE_LOAD_PATH is set to exactly ONE staged
// directory (default: <repo>/target/clr-src, produced by `bb wpf:sync` or
// verify/run-windows-checks.ps1) BEFORE the first Clojure call. ClojureCLR re-reads the
// variable on every namespace lookup (RT.GetFindFilePathsRaw); extension order is
// .cljr > .cljc > .clj. Note: the exe directory and the current directory are searched
// before CLOJURE_LOAD_PATH, so never put .clj/.cljc/.cljr files next to the exe.
//
// Usage:
//   CloViewHost.exe [--src <dir>] [--ns <entry-ns>] [--repl <port>]
//                   [--headless-smoke | --run-tests] [--out <file.json>]
//
//   (default)          start!               — run the app (Application.Run)
//   --headless-smoke   run-headless-smoke!  — off-screen WPF checks, JSON report, exit code
//   --run-tests        run-tests!           — the portable unit tests (core, fx, bridge, licensing), exit code

using System;
using System.IO;
using clojure.clr.api;
using clojure.lang;

namespace CloView.Host
{
    internal enum HostMode { App, Smoke, Tests }

    internal sealed class HostOptions
    {
        public string Src;
        public string Ns = "greeter.wpf.main";
        public string Out;
        public int ReplPort;
        public bool Help;
        public HostMode Mode = HostMode.App;

        public static HostOptions Parse(string[] args)
        {
            var o = new HostOptions();
            for (int i = 0; i < args.Length; i++)
            {
                string a = args[i];
                string next = i + 1 < args.Length ? args[i + 1] : null;
                switch (a)
                {
                    case "--src": o.Src = next; i++; break;
                    case "--ns": o.Ns = next; i++; break;
                    case "--out": o.Out = next; i++; break;
                    case "--repl": o.ReplPort = int.Parse(next); i++; break;
                    case "--headless-smoke": o.Mode = HostMode.Smoke; break;
                    case "--run-tests": o.Mode = HostMode.Tests; break;
                    case "-h":
                    case "--help": o.Help = true; break;
                    default:
                        Console.Error.WriteLine("[cloview] ignoring unknown argument: " + a);
                        break;
                }
            }
            return o;
        }
    }

    internal static class Program
    {
        private const string Marker = "cloview/core/tree.cljc";

        [STAThread]
        private static int Main(string[] args)
        {
            HostOptions opts = HostOptions.Parse(args);
            if (opts.Help)
            {
                Console.WriteLine("CloViewHost [--src <dir>] [--ns <ns>] [--repl <port>] [--headless-smoke | --run-tests] [--out <file>]");
                return 0;
            }

            string src = ResolveSourceRoot(opts.Src);
            if (src == null)
            {
                Console.Error.WriteLine("[cloview] staged sources not found (looked for " + Marker + "). " +
                                        "Run `bb wpf:sync` or pass --src <repo>/target/clr-src.");
                return 3;
            }

            // 1. Deterministic source resolution: exactly the staged tree, set before any
            //    Clojure call (RT reads the variable lazily on every lookup).
            Environment.SetEnvironmentVariable("CLOJURE_LOAD_PATH", src);
            Console.WriteLine("[cloview] CLOJURE_LOAD_PATH=" + src);

            // 2. ClojureCLR resolves type names in assemblies already loaded in the
            //    AppDomain: load WPF before any WPF symbol is compiled.
            PreloadWpf();

            try
            {
                IFn require = Clojure.var("clojure.core", "require");
                require.invoke(Clojure.read(opts.Ns));
                Console.WriteLine("[cloview] loaded " + opts.Ns + " on Clojure " +
                                  Clojure.var("clojure.core", "clojure-version").invoke() +
                                  " / " + System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription);

                if (opts.ReplPort > 0) StartRepl(opts.ReplPort);

                switch (opts.Mode)
                {
                    case HostMode.Smoke:
                        return ToExitCode(Clojure.var(opts.Ns, "run-headless-smoke!")
                            .invoke(opts.Out ?? DefaultOut(src, "clr-wpf-smoke.json")));
                    case HostMode.Tests:
                        return ToExitCode(Clojure.var(opts.Ns, "run-tests!")
                            .invoke(opts.Out ?? DefaultOut(src, "clr-unit-tests.json")));
                    default:
                        var app = new System.Windows.Application();
                        app.DispatcherUnhandledException += (sender, e) =>
                        {
                            Console.Error.WriteLine("[cloview] unhandled UI exception: " + e.Exception);
                            e.Handled = true;
                        };
                        Clojure.var(opts.Ns, "start!").invoke(app);
                        return app.Run();
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("[cloview] fatal: " + ex);
                return 1;
            }
        }

        /// <summary>--src, then CLOVIEW_CLR_SRC, then &lt;exe&gt;/clr-src (copied at build time),
        /// then target/clr-src found by walking up from the exe and the working directory.</summary>
        private static string ResolveSourceRoot(string explicitSrc)
        {
            foreach (string candidate in new[]
                     {
                         explicitSrc,
                         Environment.GetEnvironmentVariable("CLOVIEW_CLR_SRC"),
                         Path.Combine(AppContext.BaseDirectory, "clr-src"),
                         FindUpwards(AppContext.BaseDirectory),
                         FindUpwards(Directory.GetCurrentDirectory())
                     })
            {
                if (string.IsNullOrEmpty(candidate)) continue;
                string full = Path.GetFullPath(candidate);
                if (File.Exists(Path.Combine(full, Marker))) return full;
            }
            return null;
        }

        private static string FindUpwards(string start)
        {
            for (DirectoryInfo dir = new DirectoryInfo(start); dir != null; dir = dir.Parent)
            {
                string candidate = Path.Combine(dir.FullName, "target", "clr-src");
                if (File.Exists(Path.Combine(candidate, Marker))) return candidate;
            }
            return null;
        }

        /// <summary>&lt;repo&gt;/verify/out/&lt;name&gt; when the sources are the staged
        /// &lt;repo&gt;/target/clr-src, else next to the working directory.</summary>
        private static string DefaultOut(string src, string name)
        {
            DirectoryInfo target = new DirectoryInfo(src).Parent;
            if (target != null && target.Name == "target" && target.Parent != null)
                return Path.Combine(target.Parent.FullName, "verify", "out", name);
            return Path.Combine(Directory.GetCurrentDirectory(), name);
        }

        private static void PreloadWpf()
        {
            Type[] anchors =
            {
                typeof(System.Windows.DependencyObject),                 // WindowsBase
                typeof(System.Windows.Threading.Dispatcher),             // WindowsBase
                typeof(System.Windows.Media.Brush),                      // PresentationCore
                typeof(System.Windows.Automation.AutomationProperties),  // PresentationCore
                typeof(System.Windows.Window),                           // PresentationFramework
                typeof(System.Windows.Controls.Button),                  // PresentationFramework
                typeof(Microsoft.Win32.OpenFileDialog)                   // PresentationFramework
            };
            foreach (Type t in anchors)
            {
                // touching the Assembly forces the load into the AppDomain
                if (t.Assembly.FullName == null) Console.Error.WriteLine("[cloview] could not load " + t);
            }
        }

        private static void StartRepl(int port)
        {
            Clojure.var("clojure.core", "require").invoke(Clojure.read("clojure.core.server"));
            Clojure.var("clojure.core.server", "start-server").invoke(
                Clojure.read("{:name \"cloview-repl\" :port " + port + " :accept clojure.core.server/repl}"));
            Console.WriteLine("[cloview] socket REPL on localhost:" + port +
                              " (wrap UI work in cloview.wpf.dispatch/on-ui!)");
        }

        private static int ToExitCode(object o)
        {
            if (o is long l) return (int)l;
            if (o is int i) return i;
            return 1;
        }
    }
}
