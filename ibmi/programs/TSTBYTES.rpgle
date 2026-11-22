**free
// Invented test program for the gateway (kickoff 06, program 13): raw bytes, char and varchar with ccsid(*hex).
// Every output is computed from the 8-byte input, so that no byte is a constant of the program: the input followed by its
// bitwise complement (16 bytes), its first five bytes as a varying field with a 2-byte prefix, the input twice as a varying
// field with a 4-byte prefix, and the input again in a field that the overlay reads as text.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  inBytes char(8) ccsid(*hex) const;
  outFixed char(16) ccsid(*hex);
  outVar2 varchar(32) ccsid(*hex);
  outVar4 varchar(32:4) ccsid(*hex);
  outText char(8) ccsid(*hex);
end-pi;

outFixed = inBytes + %bitnot(inBytes);
outVar2 = %subst(inBytes: 1: 5);
outVar4 = inBytes + inBytes;
outText = inBytes;

*inlr = *on;
return;
