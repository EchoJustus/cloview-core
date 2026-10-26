// Reference backend for the cloview bridge, protocol "cloview-bridge/1"
// (docs/POLYGLOT_BACKEND_ARCHITECTURE.md). Plain Java 11+, no dependencies.
//
//   java Backend.java                     (JDK 11+ runs a single source file directly)
//   javac Backend.java && java Backend    (or package it as a jar)
//
// Wire format: JSON-RPC 2.0, one message per line, UTF-8, on stdin (requests from the
// GUI) and stdout (responses and notifications). stderr is for logs. A real backend
// would use Jackson or Gson; the tiny JSON reader/writer at the bottom only keeps this
// file dependency-free.
//
// Rules every backend follows:
//   1. answer "$/initialize" with {protocol, name, version, methods};
//   2. answer each request exactly once (result or error), in any order;
//   3. never write anything but protocol messages to stdout;
//   4. on "$/shutdown": take no new work, let the requests in flight finish (long ones
//      see the stop like a cancellation), answer them, then answer the shutdown and exit;
//      exit as well when stdin reaches EOF (the GUI is gone).

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Backend {
    static final PrintStream OUT;
    static {
        try {
            OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
    static final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    static volatile boolean stopping = false;

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "backend-worker");
            t.setDaemon(true);
            return t;
        });
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            Object parsed;
            try {
                parsed = Json.parse(line);
            } catch (RuntimeException e) {
                send(error(null, -32700, "parse error: " + e.getMessage()));
                continue;
            }
            if (!(parsed instanceof Map)) {
                send(error(null, -32600, "invalid request"));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) parsed;
            Object method = msg.get("method");
            if (!(method instanceof String)) continue;          // responses are not expected
            @SuppressWarnings("unchecked")
            Map<String, Object> params = msg.get("params") instanceof Map
                    ? (Map<String, Object>) msg.get("params") : new LinkedHashMap<>();
            if (!msg.containsKey("id")) {                       // a notification
                if ("$/cancel".equals(method)) cancelled.add(String.valueOf(params.get("id")));
                continue;
            }
            Object id = msg.get("id");
            switch ((String) method) {
                case "$/initialize": {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("protocol", "cloview-bridge/1");
                    r.put("name", "java-reference");
                    r.put("version", "1.0.0");
                    r.put("methods", Arrays.asList("math/add", "text/upper", "demo/count"));
                    send(result(id, r));
                    break;
                }
                case "$/ping":
                    send(result(id, "pong"));
                    break;
                case "$/shutdown":
                    stopping = true;                             // long requests end early
                    pool.shutdown();                             // no new work
                    pool.awaitTermination(2, TimeUnit.SECONDS);  // in-flight answers go out first
                    send(result(id, null));
                    OUT.flush();
                    System.exit(0);
                    break;
                default:
                    final String m = (String) method;
                    pool.submit(() -> handle(id, m, params));
            }
        }
        System.exit(0);                                          // stdin EOF: the GUI is gone
    }

    static void handle(Object id, String method, Map<String, Object> p) {
        try {
            switch (method) {
                case "math/add": {
                    Object a = p.get("a"), b = p.get("b");
                    if (!(a instanceof Number) || !(b instanceof Number)) {
                        send(error(id, -32602, "a and b must be numbers"));
                    } else if (a instanceof Long && b instanceof Long) {
                        send(result(id, (Long) a + (Long) b));
                    } else {
                        send(result(id, ((Number) a).doubleValue() + ((Number) b).doubleValue()));
                    }
                    break;
                }
                case "text/upper":
                    send(result(id, String.valueOf(p.get("text")).toUpperCase(Locale.ROOT)));
                    break;
                case "demo/count": {                             // progress, stream items, cancellation
                    long to = p.get("to") instanceof Number ? ((Number) p.get("to")).longValue() : 5;
                    long delay = p.get("delay_ms") instanceof Number ? ((Number) p.get("delay_ms")).longValue() : 100;
                    for (long i = 1; i <= to; i++) {
                        if (cancelled.remove(String.valueOf(id)) || stopping) {
                            send(error(id, -32001, stopping ? "backend stopping" : "cancelled"));
                            return;
                        }
                        Thread.sleep(delay);
                        Map<String, Object> value = new LinkedHashMap<>();
                        value.put("done", i);
                        value.put("total", to);
                        send(notification("$/progress", mapOf("id", id, "value", value)));
                        send(notification("$/stream", mapOf("id", id, "item", i)));
                    }
                    send(result(id, mapOf("counted", to)));
                    break;
                }
                default:
                    send(error(id, -32601, "method not found: " + method));
            }
        } catch (Exception e) {
            send(error(id, -32603, e.toString()));
        }
    }

    static synchronized void send(Map<String, Object> msg) {
        OUT.print(Json.write(msg));
        OUT.print('\n');
        OUT.flush();
    }

    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Object> result(Object id, Object result) {
        return mapOf("jsonrpc", "2.0", "id", id, "result", result);
    }

    static Map<String, Object> error(Object id, int code, String message) {
        return mapOf("jsonrpc", "2.0", "id", id, "error", mapOf("code", code, "message", message));
    }

    static Map<String, Object> notification(String method, Object params) {
        return mapOf("jsonrpc", "2.0", "method", method, "params", params);
    }

    /** Minimal JSON (RFC 8259): objects -> LinkedHashMap, arrays -> ArrayList, integers -> Long. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json p = new Json(text);
            Object v = p.value();
            p.ws();
            if (p.i != p.s.length()) throw new IllegalArgumentException("trailing content at " + p.i);
            return v;
        }

        private void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i++) != ':') throw new IllegalArgumentException("expected : at " + (i - 1));
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + (i - 1));
            }
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (i - 1));
            }
        }

        private String string() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);                        // \" \\ \/
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(start, i);
            if (t.isEmpty()) throw new IllegalArgumentException("unexpected character at " + start);
            if (t.matches("-?\\d{1,18}")) return Long.parseLong(t);
            return Double.parseDouble(t);
        }

        static String write(Object v) {
            StringBuilder b = new StringBuilder();
            write(v, b);
            return b.toString();
        }

        private static void write(Object v, StringBuilder b) {
            if (v == null) b.append("null");
            else if (v instanceof String) quote((String) v, b);
            else if (v instanceof Number || v instanceof Boolean) b.append(v);
            else if (v instanceof Map) {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), b);
                    b.append(':');
                    write(e.getValue(), b);
                }
                b.append('}');
            } else if (v instanceof Collection) {
                b.append('[');
                boolean first = true;
                for (Object x : (Collection<?>) v) {
                    if (!first) b.append(',');
                    first = false;
                    write(x, b);
                }
                b.append(']');
            } else quote(v.toString(), b);
        }

        private static void quote(String s, StringBuilder b) {
            b.append('"');
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            b.append('"');
        }
    }
}
