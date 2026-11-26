Every error is a real HTTP status with a problem-details body (RFC 9457), `Content-Type: application/problem+json`. Every response, an error or not, carries the header `X-Correlation-Id`: the value the request sent in that header, or one the gateway made. The same value is in the body and in the access line of the request (see [Logs reference](/docs/reference/logs/)), so one value finds both.

The fields of the body:

- `type`: what kind of error, as an address under `https://slay400.dev/problems/`; `about:blank` for a request the listener refused by itself (413, 414).
- `title`: the kind in words, the same for every error of that type.
- `status`: the HTTP status.
- `detail`: only when there is something to add: the program's message for a program error, the count of problems for an invalid request.
- `instance`: `urn:slay400:request:` and the correlation ID.
- `code`: the program's error code, for a program error only.
- `errors`: for an invalid request, one entry per problem: the `field`, a `code` and a `message`.
- `tenant` and `environment`: once the gateway knows them.
- `correlationId`: as in the header.

Nothing of IBM i reaches a body: no message ID, library, job, or text of an escape message. The access line names the problem, and the gateway's warnings give more for the operator.

Each response below was recorded from the gateway by a test of this repository, with the mock backend of the `shop` example. The 500 and the 502 come from a stub of the program call, since a mock cannot produce them. Where the gateway made the correlation ID itself, it is written `<generated>`.

The Community Edition never answers 401 or 403: authentication is not part of it.
