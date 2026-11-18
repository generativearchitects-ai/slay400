# Notices for third-party components

The libraries in `lib/` are distributed under their own licences. Third-party libraries are not covered by the gateway's
licence (`LICENSE` in the gateway's repository).

Every library of this distribution is listed, with its version and licence, in `bom.json`: a CycloneDX bill of materials,
written at build time from the dependency list of the gateway. This file adds the notice that the licence of JTOpen asks for.

## JTOpen ${jt400.version}

- File: `lib/jt400-${jt400.version}.jar` (Maven Central, `net.sf.jt400:jt400:${jt400.version}`)
- Licence: IBM Public License Version 1.0. The full text, as shipped in the JTOpen jar, is in `licenses/jtopen/license.html`.
- JTOpen is distributed here, unmodified, under the IBM Public License, not under the gateway's licence.
- Copyright (C) 1997-2024 International Business Machines Corporation and others. All Rights Reserved.
- Source code: the source of this version is available as `net.sf.jt400:jt400:${jt400.version}:sources` on Maven Central,
  https://repo1.maven.org/maven2/net/sf/jt400/jt400/${jt400.version}/jt400-${jt400.version}-sources.jar
