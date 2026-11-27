---
title: Logs reference
description: The access line of every request and the events of the gateway, field by field.
section: reference
slug: logs
order: 60
appliesTo: "0.1"
---

# Logs reference

What the gateway writes while it runs. On standard output: one JSON line per request (the access line) and one JSON line per event. On standard error: the diagnostics of a start or a check, and warnings, as text.

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

## Diagnostics and warnings

A start or a check that finds a problem writes one line per diagnostic to standard error: the file, line and column, the severity, the code and the message, then a count. See the [configuration reference](/docs/reference/configuration/) for the codes of the configuration file. Warnings while running, such as a set of bundles that cannot be loaded, are written to standard error as well, as text.
