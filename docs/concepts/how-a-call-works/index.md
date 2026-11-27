---
title: How a call works
description: From an HTTP request to an RPG program on IBM i and back, in the order the gateway does it.
section: concepts
slug: how-a-call-works
order: 10
appliesTo: "0.1"
---

# How a call works

What the gateway does with one request, from the moment it arrives to the answer, and why it is built that way.

## What the gateway knows before the first request

At start, the gateway reads its configuration file and every bundle it names. A bundle holds the descriptor of each operation: its route and method, its parameters with their types, and the layout of the program's parameters as the compiler described them in the PCML. The gateway checks all of it before it listens, and builds for each operation its route, its codec and, for an `ibmi` environment, the byte layout of the call. It never loads generated code: the descriptor alone says what to do. An `ibmi` environment signs on once at start, to check the connection.

## One request, in order

1. **The listener** reads the request line and the headers within its limits. What is over a limit is refused here (400, 413, 414), before anything else.
2. **The set of bundles.** The request takes the set that is current when it arrives and keeps it to the end, even if a new set is published meanwhile.
3. **Tenant and environment**, the first two segments of the path. An unknown one is 404.
4. **The route.** The rest of the path and the method are matched with the routes of the tenant's bundles ([URLs and routes](/docs/reference/urls-and-routes/)): 404, or 405 when the route has no operation for the method.
5. **The request.** Path, query, headers and body are read into the API's values and checked against their types and limits. Every problem is collected, and the answer is one 400 that lists them all.
6. **The encoding.** The values are turned into the program's parameters: the wire types of the PCML, padding, the environment's injected values such as its code. A value that does not fit is a 400.
7. **The call.** The environment's backend runs the program: the mock answers from the bundle's mock definitions; `ibmi` takes a connection from the environment's pool and calls the program with JT400. No connection free or the system not reachable is 503; no answer within `callTimeoutSeconds` is 504; a failed call (a missing program, a refused sign-on, an escape message) is 502.
8. **The decoding.** The program's output parameters are turned back into the API's values. Output that the API cannot represent is a 502.
9. **The business error.** When the operation has an error convention and the program set it, the answer is a problem with the program's code and message, and the status of the error mapping (422 unless it says otherwise).
10. **The answer**, as JSON, with `X-Correlation-Id`, and one access line.

Each error is a problem-details body ([errors reference](/docs/reference/errors/)); something that nobody expected is a 500 that says nothing of the cause.

## Why it is built this way

- **Two contracts.** The PCML, written by the compiler, says how the program is called; the overlay, written by you, says what the API looks like. The gateway follows both and invents neither.
- **The same codec at build time and at run time.** The generator checks values with the code that the gateway uses to encode them, so that a bundle that passed its checks is called as it was checked.
- **Nothing of the system in an answer.** An answer never holds an IBM i message, a library or a job: the caller of an API sees the API. The operator finds the cause in the log by the correlation ID.
- **Tenant and environment everywhere.** They are part of the address, of every access line and of every error, so that a request can always be placed.
