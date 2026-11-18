**free
// Invented test program for the gateway (kickoff 04, program 8): floating point of 4 and 8 bytes, and a field of raw bytes.
// Doubles the 4-byte input, divides the 8-byte input by 4, returns two fixed floats, echoes the bytes and returns fixed ones.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  in4 float(4) const;
  in8 float(8) const;
  inBytes char(4) ccsid(*hex) const;
  out4 float(4);
  out8 float(8);
  fixed4 float(4);
  fixed8 float(8);
  echoBytes char(4) ccsid(*hex);
  fixedBytes char(4) ccsid(*hex);
end-pi;

out4 = in4 * 2;
out8 = in8 / 4;
fixed4 = -1.5;
fixed8 = 1.25E10;
echoBytes = inBytes;
fixedBytes = x'00FF7F80';

*inlr = *on;
return;
