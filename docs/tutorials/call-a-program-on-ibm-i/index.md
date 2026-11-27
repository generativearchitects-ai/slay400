---
title: Call a program on IBM i
description: Compile an invented RPG program with PCML, build its bundle, and call it through the gateway on your IBM i.
section: tutorials
slug: call-a-program-on-ibm-i
order: 10
appliesTo: "0.1"
time: 10 min
---

# Call a program on IBM i

At the end of this page you will have compiled a small invented RPG program on your IBM i (AS/400) with its PCML, built a bundle for it, and called it through the gateway, and you will have seen what the gateway answers when the program reports an error, when a program is missing, and when the sign-on is refused.

Before you start, you need:

- What [Serve with a mock](/docs/get-started/serve-with-a-mock/) needs: a POSIX shell, `curl`, and Java 21 or later (`java -version`).
- An IBM i that you may compile on and call, with SSH, a user that may create a program in one of its libraries, and `scp` and `ssh` on your machine. Check with `ssh <your-user>@<your-system> system "DSPLIB <your-library>"`.

Every command marked "on your IBM i" below connects to the system. The others run on your machine only.

In the commands, replace `<your-system>` with the host name of your IBM i, `<your-user>` with your user, `<your-library>` with the library for the program, and `<your-password>` with your password.

## Install the tools

This installs `slay400`, the generator:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#install-slay400 -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
```

It prints:

<!-- snippet: examples/call-a-program-on-ibm-i/expected/install-slay400.out -->
```text
slay400 <slay400 version> installed in <workdir>/_home/.slay400/<slay400 version>
Add this to your shell profile, or run it in your current shell:
  export PATH="<workdir>/_home/.slay400/bin:$PATH"
Then check with: slay400 --version
```

This installs the gateway:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#install-gateway -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
```

It prints:

<!-- snippet: examples/call-a-program-on-ibm-i/expected/install-gateway.out -->
```text
slay400-gateway <version> installed in <workdir>/_home/.slay400-gateway/<version>
Add this to your shell profile, or run it in your current shell:
  export PATH="<workdir>/_home/.slay400-gateway/bin:$PATH"
Then check with: slay400-gateway --version
```

## Add JT400

slay400 does not include JT400 (JTOpen). To reach an IBM i you download it yourself, under its own licence; this page shows how.

JT400 is the IBM Toolbox for Java, under the IBM Public License 1.0. These commands download the version the gateway was checked with from Maven Central into a folder of its own, check its SHA-256, and tell the gateway where it is with `SLAY400_GATEWAY_EXT`; the folder stays when you install a newer gateway. On macOS, use `shasum -a 256 -c -` in place of `sha256sum -c -`.

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#add-jt400 -->
```sh
mkdir -p "$HOME/.slay400-gateway/ext"
curl -fsSLo "$HOME/.slay400-gateway/ext/jt400-21.0.7.jar" https://repo1.maven.org/maven2/net/sf/jt400/jt400/21.0.7/jt400-21.0.7.jar
echo "d2c06bba78d20b4733a3fabc9fe6ccabac337f7298bea95c6766108fa0b420f8  $HOME/.slay400-gateway/ext/jt400-21.0.7.jar" | sha256sum -c -
export SLAY400_GATEWAY_EXT="$HOME/.slay400-gateway/ext"
```

It prints:

<!-- snippet: examples/call-a-program-on-ibm-i/expected/add-jt400.out -->
```text
<workdir>/_home/.slay400-gateway/ext/jt400-21.0.7.jar: OK
```

Without JT400, a gateway with an `ibmi` environment does not start: it stops with GWY026, which names the folder it looked in.

## Compile the program, on your IBM i

The program is `TSTNESTED`, an invented order lookup: for a key it returns three order lines and a total, and for the key `FAIL` it reports an error through three fields. Save its source as `TSTNESTED.rpgle`:

<!-- snippet: examples/call-a-program-on-ibm-i/input/TSTNESTED.rpgle -->
```text
**free
// Invented test program for the gateway (kickoff 03, program 4): a struct holding an array of structs, and the three
// fields of an error convention. The key FAIL makes the program report an error; any other key fills three lines.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-ds line_t qualified template;
  sku char(8);
  qty int(10);
end-ds;

dcl-ds order_t qualified template;
  total packed(9:2);
  lines likeds(line_t) dim(3);
end-ds;

dcl-pi *n;
  key char(5) const;
  order likeds(order_t);
  errFlag char(2);
  errCode char(7);
  errText char(40);
end-pi;

dcl-s i int(10);

clear order;
if key = 'FAIL';
  errFlag = 'KO';
  errCode = 'TST0001';
  errText = 'The key is refused';
  *inlr = *on;
  return;
endif;

errFlag = 'OK';
errCode = *blanks;
errText = *blanks;
for i = 1 to 3;
  order.lines(i).sku = %trimr(key) + '-' + %char(i);
  order.lines(i).qty = i * 10;
endfor;
order.total = 123.45;

*inlr = *on;
return;
```

These commands copy the source to your home folder on the system, compile it into `<your-library>` with `PGMINFO(*PCML)`, which makes the compiler write the PCML of the program, and copy the PCML back next to the overlay. `OUTPUT(*NONE)` leaves out the compile listing, `REPLACE(*YES)` replaces a program of the same name, `DBGVIEW(*NONE)` keeps no debug view, and `TGTCCSID(*JOB)` compiles in the CCSID of your job:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#compile -->
```sh
# requires: ibmi
scp TSTNESTED.rpgle '<your-user>@<your-system>:TSTNESTED.rpgle'
ssh '<your-user>@<your-system>' "system \"CRTBNDRPG PGM(<your-library>/TSTNESTED) SRCSTMF('TSTNESTED.rpgle') PGMINFO(*PCML) INFOSTMF('TSTNESTED.pcml') OUTPUT(*NONE) REPLACE(*YES) DBGVIEW(*NONE) TGTCCSID(*JOB)\""
scp '<your-user>@<your-system>:TSTNESTED.pcml' apis/TSTNESTED/TSTNESTED.pcml
```

The build of this documentation does not run these three lines. It compiles the same source with the gateway's setup tool, which gives the compiler this same `CRTBNDRPG` command with `PGMINFO(*PCML)`, but writes the source and reads the PCML back through JT400 rather than with `scp` and `ssh`. Every other step of this page that needs IBM i is run on a real system.

The [guide on PCML from the compiler](/docs/guides/get-pcml-from-the-compiler/) shows other ways to compile and what the file holds.

## Build the bundle

The overlay `apis/TSTNESTED/api.yaml` says what the API looks like: the key is part of the address, the order is the answer, and the three error fields are the convention by which the program reports an error. Save it with this content:

<!-- snippet: examples/call-a-program-on-ibm-i/input/apis/TSTNESTED/api.yaml -->
```yaml
program: TSTNESTED
pcml: TSTNESTED.pcml
route: /orders/{key}
method: GET
version: 1.0.0
summary: Returns an order with its lines, or a business error for the key FAIL (invented test program)

parameters:
  KEY: { name: key }
  ORDER:
    direction: response
    name: order
    fields:
      TOTAL: { name: total, type: decimal }
      LINES:
        name: lines
        fields:
          SKU: { name: sku }
          QTY: { name: quantity, type: integer }
  ERRFLAG: { direction: internal }
  ERRCODE: { direction: internal }
  ERRTEXT: { direction: internal }

error:
  when: { field: ERRFLAG, equals: "KO" }
  code: ERRCODE
  message: ERRTEXT
  httpStatus: 422
  statusByCode: { TST0001: 409 }
```

The example also has `apis/TSTNONE/`, the overlay and PCML of a program that is never created, to show the call of a missing program; its files are in the [examples of this page](https://github.com/generativearchitects-ai/slay400/tree/main/docs/examples/call-a-program-on-ibm-i/input/apis/TSTNONE). Then build the bundle:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#bundle -->
```sh
slay400 init
slay400 bundle --name orders --version 1.0.0 --out build/orders-1.0.0.rpgapi
```

It prints a warning about `TSTNONE`, which has no error convention, and no error:

<!-- snippet: examples/call-a-program-on-ibm-i/expected/bundle.out -->
```text
0 errors, 0 warnings, 0 notes
apis/TSTNONE/api.yaml:1:1: warning OVL006: Operation tstnone has no error mapping: a business error reported by the program is returned as a success
0 errors, 1 warning, 0 notes
```

## Configure an ibmi environment

The environment `prod` calls your IBM i. Its host, library, user and password are `${NAME}` placeholders: the gateway reads them from environment variables when it starts, and refuses a user or password written in the file (GWY015).

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#config -->
```sh
cat > config.yaml <<'YAML'
tenants:
  acme:
    bundles: [./build/orders-1.0.0.rpgapi]
    environments:
      prod:
        code: P0001
        backend: ibmi
        host: ${IBMI_HOST}
        libraryList: ["${IBMI_LIBRARY}"]
        credentials:
          user: ${IBMI_USER}
          password: ${IBMI_PASSWORD}
YAML
```

Set the four variables in the shell that starts the gateway:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#credentials -->
```sh
# requires: ibmi
export IBMI_HOST='<your-system>'
export IBMI_LIBRARY='<your-library>'
export IBMI_USER='<your-user>'
export IBMI_PASSWORD='<your-password>'
```

Check the configuration, on your machine only: `check` opens no connection to the system.

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#check -->
```sh
# requires: ibmi
slay400-gateway check --config config.yaml
```

It prints:

<!-- snippet: examples/call-a-program-on-ibm-i/expected/check.out -->
```text
slay400-gateway check: config.yaml can be served, 2 operations, 0 warnings
```

If a variable is not set, it names it, with GWY007.

## Start the gateway and call the program, on your IBM i

The start signs on to the system once, to check the environment before it listens. This starts the gateway in the background and shows its start line, which names the JT400 version it found:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#start -->
```sh
# requires: ibmi
slay400-gateway run --config config.yaml > gateway.log 2>&1 &
until curl -fs -o /dev/null http://127.0.0.1:8080/health/ready; do sleep 1; done
grep '"event":"started"' gateway.log
```

It prints the start line:

<!-- snippet: examples/call-a-program-on-ibm-i/expected/start.out -->
```text
{"event":"started","address":"127.0.0.1","port":8080,"tenants":1,"environments":1,"operations":2,"ext":"<workdir>/_home/.slay400-gateway/ext","jt400":"21.0.7"}
```

If the command does not end within a few seconds, press Ctrl-C and read `gateway.log`.

Ask for the order with the key `A1`:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#call -->
```sh
# requires: ibmi
curl -s http://127.0.0.1:8080/acme/prod/orders/A1
```

The program answers with three lines, `A1-1` to `A1-3`, and a total:

<!-- snippet: examples/call-a-program-on-ibm-i/expected/call.out -->
```text
{"order":{"total":"123.45","lines":[{"sku":"A1-1","quantity":10},{"sku":"A1-2","quantity":20},{"sku":"A1-3","quantity":30}]}}
```

## When the program reports an error

The key `FAIL` makes the program set its error fields. The overlay maps the error code `TST0001` to 409:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#call-refused-key -->
```sh
# requires: ibmi
curl -s -w '\n%{http_code}\n' http://127.0.0.1:8080/acme/prod/orders/FAIL
```

It prints the body, then the status:

<!-- snippet: examples/call-a-program-on-ibm-i/expected/call-refused-key.out -->
```text
{"type":"https://slay400.dev/problems/program-error","title":"The program reported an error","status":409,"detail":"The key is refused","instance":"urn:slay400:request:53440ff5-9019-4b18-a5ee-005734db4147","code":"TST0001","tenant":"acme","environment":"prod","correlationId":"53440ff5-9019-4b18-a5ee-005734db4147"}
409
```

The gateway answers 409 with a problem-details body that holds the program's code, `TST0001`, and its message. The [errors reference](/docs/reference/errors/) lists every status.

## When the program is missing

The operation `tstnone` calls a program that does not exist on your system:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#call-missing -->
```sh
# requires: ibmi
curl -s -w '\n%{http_code}\n' http://127.0.0.1:8080/acme/prod/missing
```

It prints the body, then the status:

<!-- snippet: examples/call-a-program-on-ibm-i/expected/call-missing.out -->
```text
{"type":"https://slay400.dev/problems/program-call-failed","title":"The program call failed","status":502,"instance":"urn:slay400:request:d4ecd7b9-38ea-471c-9267-da509394b53e","tenant":"acme","environment":"prod","correlationId":"d4ecd7b9-38ea-471c-9267-da509394b53e"}
502
```

The gateway answers 502, `program-call-failed`. The body does not say why; the gateway's log does, without the library or the message of the system.

Stop the gateway:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#stop -->
```sh
# requires: ibmi
kill $!
```

## When the sign-on is refused

This starts the gateway with a wrong password, in the foreground. It signs on once, is refused, and stops:

<!-- snippet: examples/call-a-program-on-ibm-i/steps.sh#sign-on-refused -->
```sh
# requires: ibmi
IBMI_PASSWORD='not-the-password' slay400-gateway run --config config.yaml
```

It reports GWY018, "The backend ibmi of the environment prod cannot be opened", with the reason, and exits with 1. Each refused sign-on counts towards the limit of your system for that user; a correct sign-on resets the count.

## Clean up

Delete the folder, and on your IBM i the program `<your-library>/TSTNESTED` and the two files in your home folder.

## Next

Replace a bundle while the gateway runs: [Publish a new version](/docs/tutorials/publish-a-new-version/).
