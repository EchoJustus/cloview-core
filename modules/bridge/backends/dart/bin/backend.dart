// Reference backend for the cloview bridge, protocol "cloview-bridge/1"
// (docs/POLYGLOT_BACKEND_ARCHITECTURE.md). Dart 3, no packages.
//
//   dart run bin/backend.dart
//   dart compile exe bin/backend.dart -o backend      (one executable for the manifest)
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

import 'dart:async';
import 'dart:convert';
import 'dart:io';

final cancelled = <String>{};
final inflight = <Future<void>>{};
var stopping = false;

void send(Map<String, Object?> message) {
  stdout.write('${jsonEncode(message)}\n');
}

Map<String, Object?> result(Object? id, Object? result) =>
    {'jsonrpc': '2.0', 'id': id, 'result': result};

Map<String, Object?> error(Object? id, int code, String message) => {
      'jsonrpc': '2.0',
      'id': id,
      'error': {'code': code, 'message': message}
    };

Map<String, Object?> notification(String method, Object? params) =>
    {'jsonrpc': '2.0', 'method': method, 'params': params};

Future<void> handle(Object? id, String method, Map<String, Object?> p) async {
  try {
    switch (method) {
      case 'math/add':
        final a = p['a'], b = p['b'];
        if (a is int && b is int) {
          send(result(id, a + b));
        } else if (a is num && b is num) {
          send(result(id, a.toDouble() + b.toDouble()));
        } else {
          send(error(id, -32602, 'a and b must be numbers'));
        }
      case 'text/upper':
        send(result(id, '${p['text'] ?? ''}'.toUpperCase()));
      case 'demo/count': // progress, stream items, cancellation
        final to = (p['to'] as num?)?.toInt() ?? 5;
        final delay = (p['delay_ms'] as num?)?.toInt() ?? 100;
        for (var i = 1; i <= to; i++) {
          if (cancelled.remove('$id') || stopping) {
            send(error(id, -32001, stopping ? 'backend stopping' : 'cancelled'));
            return;
          }
          await Future<void>.delayed(Duration(milliseconds: delay));
          send(notification('\$/progress', {
            'id': id,
            'value': {'done': i, 'total': to}
          }));
          send(notification('\$/stream', {'id': id, 'item': i}));
        }
        send(result(id, {'counted': to}));
      default:
        send(error(id, -32601, 'method not found: $method'));
    }
  } catch (e) {
    send(error(id, -32603, '$e'));
  }
}

Future<void> main() async {
  // the protocol is UTF-8; Dart's default for stdout is the system encoding (on Windows
  // the ANSI code page), which would mangle non-ASCII text
  stdout.encoding = utf8;
  final lines = stdin.transform(utf8.decoder).transform(const LineSplitter());
  await for (final line in lines) {
    if (line.trim().isEmpty) continue;
    Object? msg;
    try {
      msg = jsonDecode(line);
    } catch (e) {
      send(error(null, -32700, 'parse error: $e'));
      continue;
    }
    if (msg is! Map<String, Object?>) {
      send(error(null, -32600, 'invalid request'));
      continue;
    }
    final method = msg['method'];
    if (method is! String) continue; // responses are not expected
    final params = msg['params'] is Map<String, Object?>
        ? msg['params'] as Map<String, Object?>
        : <String, Object?>{};
    if (!msg.containsKey('id')) {
      // a notification
      if (method == r'$/cancel') cancelled.add('${params['id']}');
      continue;
    }
    final id = msg['id'];
    switch (method) {
      case r'$/initialize':
        send(result(id, {
          'protocol': 'cloview-bridge/1',
          'name': 'dart-reference',
          'version': '1.0.0',
          'methods': ['math/add', 'text/upper', 'demo/count']
        }));
      case r'$/ping':
        send(result(id, 'pong'));
      case r'$/shutdown':
        // no new work; requests in flight finish (long ones see the stop) and answer first
        stopping = true;
        await Future.wait(inflight.toList())
            .timeout(const Duration(seconds: 2), onTimeout: () => <void>[]);
        send(result(id, null));
        await stdout.flush();
        exit(0);
      default:
        final work = handle(id, method, params);
        inflight.add(work);
        unawaited(work.whenComplete(() => inflight.remove(work)));
    }
  }
  await stdout.flush();
  exit(0); // stdin EOF: the GUI is gone
}
