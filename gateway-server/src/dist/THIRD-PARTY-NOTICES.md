# Notices for third-party components

The libraries in `lib/` are distributed under their own licences. Third-party libraries are not covered by the gateway's
licence (`LICENSE`).

Every library of this distribution is listed, with its version and licence, in `bom.json`: a CycloneDX bill of materials,
written at build time from the dependency list of the gateway.

## JT400 is not included

This distribution does not contain JT400 (JTOpen, the IBM Toolbox for Java). The `ibmi` backend needs it at run time.
Operators obtain it themselves, under its own licence, the IBM Public License 1.0; the documentation explains how to download
and add it.

The jar goes into `lib/ext/` (in the container image, a folder mounted at `/opt/slay400/ext`). The README of the gateway gives
the commands, and the versions verified on a real system. A gateway with `mock` environments only does not need it.
