---
title: Logs reference
description: The access line of every request, the events and the log records of the gateway, field by field.
section: reference
slug: logs
order: 60
appliesTo: "0.1"
---

# Logs reference

What the gateway writes while it runs: every line, on either stream, is one JSON object. On standard output, one line per request (the access line) and one line per event. On standard error, one line per log record: the gateway's warnings and errors, and what Helidon, the web server inside the gateway, logs. What `check` and a start that fails print for a person, the diagnostics and their count, is text on standard error, and comes before the gateway serves anything.

## The access line

One line per request, once the answer is sent. This is its shape, with the values of a call of the examples; it is not a command:

```text
{"time":"2026-11-26T19:10:56.256497Z","tenant":"acme","environment":"test","operation":"ordstatus","method":"GET","route":"/orders/{orderNumber}/status","bundle":"shop","bundleVersion":"1.0.0","status":200,"durationMs":6,"correlationId":"9d26ad70-3be4-4000-a373-12dadf356997"}
```

| Field | What | When |
|---|---|---|
| `time` | The time of the request, in UTC (ISO 8601) | always |
| `tenant` | The tenant of the address | once it is known |
| `environment` | The environment of the address | once it is known |
| `operation` | The id of the operation | once the route is matched |
| `method` | The HTTP method; `OTHER` for a method the gateway does not know | always |
| `route` | The route template, not the path: no value of a parameter | once the route is matched |
| `bundle`, `bundleVersion` | The bundle and its version that served the request | once the route is matched |
| `status` | The HTTP status of the answer | always |
| `durationMs` | How long the request took, in milliseconds | always |
| `correlationId` | As in the header `X-Correlation-Id` | always |
| `problems` | The type of each problem (for example `program-error`, or a codec code such as `VAL104`), never a value | for an error |

A request that the listener refused by itself (a request line or headers too large, a body over the limit) has an access line with `time`, `method`, `status`, `durationMs`, `correlationId` and `problems`, and no tenant. The line never holds a value of a parameter, a body, a credential, or an IBM i message.

## The events

`started`, once the gateway listens. This is its shape:

```text
{"event":"started","address":"127.0.0.1","port":8080,"tenants":1,"environments":1,"operations":2,"ext":"/home/you/.slay400-gateway/ext","jt400":"21.0.7"}
```

The address and port of the API listener, how many tenants, environments and operations it serves, the folder in which it looked for JT400 (`null` when there is none), and the version of JT400 it found (`none` when there is none).

`reloaded`, each time the gateway switches to a new set of bundles. This is its shape:

```text
{"event":"reloaded","bundles":[{"tenant":"acme","name":"shop","version":"1.1.0"}],"operations":{"added":0,"removed":0,"changed":0},"durationMs":42}
```

The bundles now served, with their versions; how many operations were added, removed and changed; and how long the load took.

`stopping` and `stopped`, on `SIGTERM`: before the listeners stop taking connections, and after the requests in flight finished and the backends closed. They have no other field.

## Log records

One line per record, on standard error, the gateway's and Helidon's alike. This is the shape of a warning of the gateway, about a certificate near its end; it is not a command:

```text
{"time":"2026-11-27T10:13:03.123Z","level":"warning","logger":"slay400.gateway","message":"The certificate in tls.crt expires on 2026-12-07, in 9 days","code":"GWY025"}
```

| Field | What | When |
|---|---|---|
| `time` | When the record was written, in UTC (ISO 8601) | always |
| `level` | `error`, `warning` or `info` | always |
| `logger` | Who wrote it: `slay400.gateway` and `slay400.gateway.ibmi` for the gateway, `io.helidon.…` for Helidon | always |
| `message` | What happened, in words | always |
| `tenant`, `environment` | The tenant and the environment the record is about | when the gateway knows them |
| `operation` | The id of the operation | when the gateway knows it |
| `correlationId` | As in the header `X-Correlation-Id` and the access line, so that one value finds the request and its records | for a record about a request |
| `code` | The diagnostic code, such as GWY025 or VAL104 | when there is one |
| `exception` | The class and the message of an exception | with an exception |
| `stack` | Its stack trace, causes included, as one string | with an exception |
| `truncated` | `true` when the message, the exception or the stack was cut at its bound (4096, 1024 and 16384 characters) | when one was cut |

A record never breaks its line: line ends and other control characters inside a message or a stack are escaped. No record holds a value of a parameter, a body, a credential or key material. A text that came from the IBM i, such as the message of a failed call, has the host, user and libraries of its environment replaced by `<host>`, `<user>` and `<library>` before it is written.

The gateway logs at `info` and above. Watch `error` and `warning`; `info` records tell what the gateway and Helidon did, such as the listeners they started.

## Diagnostics of a start or a check

A start or a check that finds a problem writes, to standard error and as text for a person, one line per diagnostic: the file, line and column, the severity, the code and the message, then a count. See the [configuration reference](/docs/reference/configuration/) for the codes of the configuration file. A start that fails this way serves nothing and exits with 1.
