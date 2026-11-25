# Notices for third-party components

The libraries in `lib/` are distributed under their own licences. Third-party libraries are not covered by the gateway's
licence (`LICENSE`).

Every library of this distribution is listed, with its version and licence, in `bom.json`: a CycloneDX bill of materials,
written at build time from the dependency list of the gateway.

## JTOpen is not included

The `ibmi` backend calls programs on IBM i through JTOpen (JT400, IBM Public License 1.0). JTOpen is not part of this
distribution and is not shipped with it: the operator of a gateway that has an `ibmi` environment downloads it from Maven
Central (`net.sf.jt400:jt400`) and puts its jar into `lib/ext/` (in the container image, a folder mounted at
`/opt/slay400/ext`). The README of the gateway gives the commands, and the versions verified on a real system. A gateway
with `mock` environments only does not need it.
