**free
// Invented test program for the gateway (kickoff 03, program 1): scalars only, in and out.
// Deterministic: echoes the text with a prefix, doubles the packed value, adds one to the zoned value, triples the integer.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  inText char(10) const;
  inPacked packed(7:2) const;
  inZoned zoned(5:0) const;
  inInt int(10) const;
  outText char(20);
  outPacked packed(9:2);
  outZoned zoned(7:0);
  outInt int(20);
end-pi;

outText = 'ECHO:' + inText;
outPacked = inPacked * 2;
outZoned = inZoned + 1;
outInt = inInt * 3;

*inlr = *on;
return;
