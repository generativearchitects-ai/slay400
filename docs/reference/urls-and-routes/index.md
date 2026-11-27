---
title: URLs and routes
description: The addresses the gateway serves, how a request finds its operation, and what each listener answers.
section: reference
slug: urls-and-routes
order: 40
appliesTo: "0.1"
---

# URLs and routes

Every address the gateway serves, and how it matches a request with an operation.

## The API listener

| Address | What |
|---|---|
| `/<tenant>/<environment>/<route>` | An operation: the tenant and the environment of the configuration, then the route of the operation's `api.yaml` |
| `/health/live` | `{"status":"ok"}` once the gateway runs |
| `/health/ready` | `{"status":"ok"}` once it serves its bundles |

This is the shape of the address of the operation `ORDSTATUS` of the examples, for the tenant `acme` and the environment `test`; it is a shape, not a command:

```text
GET /acme/test/orders/{orderNumber}/status
```

The route, `/orders/{orderNumber}/status`, and the method come from `api.yaml`, as the OpenAPI document of the bundle publishes them. `health` cannot be the name of a tenant.

## The management listener

Plain HTTP, `127.0.0.1:9090` by default (`management`). It serves `/health/live` and `/health/ready`, and never an operation: any other address is 404. `management.port: 0` turns it off.

## How a route is matched

What follows the tenant and the environment is matched with the routes of the tenant's bundles:

- The path is split at `/`, and each segment is percent-decoded once. A segment that does not decode, that decodes to something with `/` or a control character, or that is longer than 4096 characters, matches nothing.
- A placeholder, such as `{orderNumber}`, matches one or more characters of a segment, but not `.` or `..` alone. A segment can mix text and a placeholder, as in `/files/{name}.json`.
- Nothing is normalised: `..`, an empty segment, a trailing slash and repeated slashes match only a route that has them. Case matters.
- A literal segment wins over a placeholder, and a segment with more literal text wins over one with less. When the best choice leads nowhere, the next is tried.
- The first complete match is the route, whatever its methods. If it has no operation for the method of the request, the answer is 405, with the header `Allow`.

No match is 404, and so is an unknown tenant or environment. See the [errors reference](/docs/reference/errors/) for both bodies.

## Parameters

Where each parameter of an operation travels is in its `api.yaml`: in the path (a placeholder of the route), in the query, in a header, or in the JSON body. The OpenAPI document of the bundle lists them for each operation.

## Headers of every response

- `X-Correlation-Id`: the value the request sent in the same header, when it is one value of 1 to 64 letters, digits, `.`, `_` and `-`; otherwise one the gateway makes. The access line and the body of an error carry the same value.
- `Content-Type`: `application/json` for an answer, `application/problem+json` for an error.
