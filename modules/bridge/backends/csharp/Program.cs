// Reference backend for the cloview bridge, protocol "cloview-bridge/1"
// (docs/POLYGLOT_BACKEND_ARCHITECTURE.md). C# on .NET 8 or newer, no NuGet packages.
//
//   dotnet run --project Backend.csproj
//   dotnet publish Backend.csproj -c Release -r win-x64 --self-contained -p:PublishSingleFile=true
//     -> one backend.exe for the manifest's "windows" command
//
// Wire format: JSON-RPC 2.0, one message per line, UTF-8, on stdin (requests from the
// GUI) and stdout (responses and notifications). stderr is for logs.
//
// Rules every backend follows:
//   1. answer "$/initialize" with {protocol, name, version, methods};
//   2. answer each request exactly once (result or error), in any order;
//   3. never write anything but protocol messages to stdout;
//   4. on "$/shutdown": take no new work, let the requests in flight finish (long ones
//      see the stop like a cancellation), answer them, then answer the shutdown and exit;
//      exit as well when stdin reaches EOF (the GUI is gone).

using System.Collections.Concurrent;
using System.Text;
using System.Text.Json.Nodes;

var utf8 = new UTF8Encoding(false);
var stdin = new StreamReader(Console.OpenStandardInput(), utf8);
var stdout = new StreamWriter(Console.OpenStandardOutput(), utf8) { AutoFlush = false };
var writeLock = new object();
var cancelled = new ConcurrentDictionary<string, bool>();
var inflight = new ConcurrentDictionary<int, Task>();
var stopping = new CancellationTokenSource();   // set by $/shutdown; long requests check it

void Send(JsonObject message)
{
    lock (writeLock)
    {
        stdout.Write(message.ToJsonString());
        stdout.Write('\n');
        stdout.Flush();
    }
}

JsonObject Result(JsonNode? id, JsonNode? result) =>
    new() { ["jsonrpc"] = "2.0", ["id"] = id?.DeepClone(), ["result"] = result };

JsonObject Error(JsonNode? id, int code, string message) =>
    new() { ["jsonrpc"] = "2.0", ["id"] = id?.DeepClone(), ["error"] = new JsonObject { ["code"] = code, ["message"] = message } };

JsonObject Notification(string method, JsonNode parameters) =>
    new() { ["jsonrpc"] = "2.0", ["method"] = method, ["params"] = parameters };

async Task Handle(JsonNode? id, string method, JsonObject p)
{
    try
    {
        switch (method)
        {
            case "math/add":
                var a = p["a"] as JsonValue;
                var b = p["b"] as JsonValue;
                if (a is null || b is null) { Send(Error(id, -32602, "a and b must be numbers")); break; }
                if (a.TryGetValue(out long la) && b.TryGetValue(out long lb)) Send(Result(id, la + lb));
                else Send(Result(id, a.GetValue<double>() + b.GetValue<double>()));
                break;
            case "text/upper":
                Send(Result(id, (p["text"]?.GetValue<string>() ?? "").ToUpperInvariant()));
                break;
            case "demo/count":   // progress, stream items, cancellation
                long to = p["to"]?.GetValue<long>() ?? 5;
                int delay = (int)(p["delay_ms"]?.GetValue<long>() ?? 100);
                for (long i = 1; i <= to; i++)
                {
                    if (cancelled.TryRemove(id?.ToJsonString() ?? "", out _) || stopping.IsCancellationRequested)
                    {
                        Send(Error(id, -32001, stopping.IsCancellationRequested ? "backend stopping" : "cancelled"));
                        return;
                    }
                    await Task.Delay(delay);
                    Send(Notification("$/progress", new JsonObject { ["id"] = id?.DeepClone(), ["value"] = new JsonObject { ["done"] = i, ["total"] = to } }));
                    Send(Notification("$/stream", new JsonObject { ["id"] = id?.DeepClone(), ["item"] = i }));
                }
                Send(Result(id, new JsonObject { ["counted"] = to }));
                break;
            default:
                Send(Error(id, -32601, "method not found: " + method));
                break;
        }
    }
    catch (Exception e)
    {
        Send(Error(id, -32603, e.Message));
    }
}

string? line;
while ((line = stdin.ReadLine()) != null)
{
    if (string.IsNullOrWhiteSpace(line)) continue;
    JsonObject? msg;
    try { msg = JsonNode.Parse(line) as JsonObject; }
    catch (Exception e) { Send(Error(null, -32700, "parse error: " + e.Message)); continue; }
    if (msg is null) { Send(Error(null, -32600, "invalid request")); continue; }
    if (msg["method"]?.GetValue<string>() is not string method) continue;   // responses are not expected
    var p = msg["params"] as JsonObject ?? new JsonObject();
    if (!msg.ContainsKey("id"))                                              // a notification
    {
        if (method == "$/cancel") cancelled[p["id"]?.ToJsonString() ?? ""] = true;
        continue;
    }
    var id = msg["id"];
    switch (method)
    {
        case "$/initialize":
            Send(Result(id, new JsonObject
            {
                ["protocol"] = "cloview-bridge/1",
                ["name"] = "csharp-reference",
                ["version"] = "1.0.0",
                ["methods"] = new JsonArray("math/add", "text/upper", "demo/count")
            }));
            break;
        case "$/ping":
            Send(Result(id, "pong"));
            break;
        case "$/shutdown":
            // no new work; requests in flight finish (long ones see the stop) and answer first
            stopping.Cancel();
            Task.WaitAll(inflight.Values.ToArray(), TimeSpan.FromSeconds(2));
            Send(Result(id, null));
            return 0;
        default:
            var request = p.DeepClone().AsObject();
            var work = Task.Run(() => Handle(id?.DeepClone(), method, request));
            inflight[work.Id] = work;
            _ = work.ContinueWith(t => inflight.TryRemove(t.Id, out _));
            break;
    }
}
return 0;   // stdin EOF: the GUI is gone
