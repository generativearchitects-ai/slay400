# gateway-ibmi

The `ibmi` backend of the gateway: program calls on IBM i through JTOpen (JT400). It is the only module that depends on JT400;
nothing of JT400 appears in `gateway-runtime` or in the call interface.

JTOpen is compiled and tested against (`provided` scope) and never packaged (decision 143). In the distribution this module
sits in `lib/ibmi/` and is loaded, with the JTOpen jar the operator puts into `lib/ext/`, by a class loader of its own; it is
found there as a `BackendProvider` service. See "JTOpen for the ibmi backend" in the README of the gateway.

## Configuration

An environment with `backend: ibmi` (all values below are examples; credentials are always environment variables):

```yaml
environments:
  test:
    code: T0001
    backend: ibmi
    host: ${ACME_TEST_HOST}
    libraryList: [APPLIB_T]
    credentials:
      user: ${ACME_TEST_USER}
      password: ${ACME_TEST_PASSWORD}
    tls: true                      # default
    pool: { maxConnections: 4 }    # default
    callTimeoutSeconds: 30         # default
```

| Key | Required | Rule |
|---|---|---|
| `host` | yes | a host name or an address, without scheme, path or port |
| `libraryList` | yes | 1 to 250 library names (1 to 10 characters: letters, digits, `$ # @ _ .`, not starting with a digit, `_` or `.`), kept in upper case |
| `credentials.user`, `credentials.password` | yes | each exactly one `${NAME}` placeholder; a value written in the file stops the start (GWY015) |
| `tls` | no, `true` | `true`: secure host servers, certificate verified against the JVM's trust store. `false`: plain host servers |
| `pool.maxConnections` | no, 4 | 1 to 64 connections for the tenant and environment |
| `callTimeoutSeconds` | no, 30 | 1 to 3600 |

No message, log line or `toString` names the host, the libraries, the user or the password.

## Behaviour

- Every operation uses the raw call: one byte array per top-level parameter, laid out from the wire types of the descriptor
  and the formats the compiler's PCML states. Counts and lengths that name another field, the `byte` type, service program
  procedures, return values, and date and time formats no real system has returned (decision 121) are refused at start
  (GWY017).
- The program is called as `/QSYS.LIB/%LIBL%.LIB/<name>.PGM`, through the library list of the environment, which is set on each
  connection when it is opened with `CHGLIBL LIBL(...) CURLIB(*CRTDFT)`. The library in the PCML is not used.
- A connection is checked before use and replaced when broken. When no connection is free within 2 seconds, the call is 503.
- Cannot connect, sign-on refused, no free connection: 503. No answer within `callTimeoutSeconds`: 504, and the connection is
  closed. Escape message or program not found: 502, with the message ids and first-level text in the operator's log only.
- A refused sign-on is logged once at error level, without the user, and is not tried again until the gateway restarts.
- Character data of CCSID 65535 (`ccsid(*hex)` in RPG) is bytes, base64 in JSON. With `type: string` in the overlay it is read
  as text in the CCSID of the job: bytes that are control characters there end up as control characters in the JSON string,
  escaped, which is the overlay author's choice.
- The pool of an environment is opened once and kept for as long as the gateway runs: a reload of the bundles builds new
  layouts over the same connections.
- At start, each `ibmi` environment opens one connection (the host resolves, a connection opens, signs on and gets its library
  list) without calling a program. A failure stops the start (GWY018). `--no-connect-check` skips it.

## Tests against a real IBM i

Everything is tested without a system. The tests that need one are in the Maven profile `ibmi` and are skipped unless
`IBMI_HOST`, `IBMI_USER`, `IBMI_PASSWORD` and `IBMI_LIBRARY` are set. The owner runs them through the workflow `ibmi`
(`.github/workflows/ibmi.yml`): by hand, or by putting the label `ibmi` on a pull request of this repository. The job runs in
the GitHub environment `ibmi`, which holds the four values as secrets.

| Step | Class | What it does on the system |
|---|---|---|
| Setup | `IbmiSetupIT` | copies `ibmi/programs/*.rpgle` to `<home>/gateway-ibmi-test`, compiles each into `IBMI_LIBRARY` with `PGMINFO(*PCML)` and `OUTPUT(*NONE)`, brings the PCML back |
| Spike | `SpikeIT` | calls programs 1 to 4 with `ProgramCallDocument` and with the raw call, eight calls |
| Gateway | `GatewayIT` (in `gateway-server`) | the five programs through the whole gateway over HTTP, eight calls |

The artifact `ibmi-results` holds the compiler's PCML (library written as `OWNERLIB`), `pcml-differences.md` and
`spike-report.md`. The system is shared: one run at a time, one connection at a time, a handful of calls per run, objects only
in the owner's library and folder.
