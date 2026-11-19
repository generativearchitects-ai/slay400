**free
// Invented test program for the gateway (kickoff 04, program 8): floating point of 4 and 8 bytes.
// Doubles the 4-byte input, divides the 8-byte input by 4, and returns two fixed floats. (Raw bytes, char ccsid(*hex), were
// taken out after the first run: the compiler describes them as char with CCSID 65535, which slay400-core cannot carry yet.)
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  in4 float(4) const;
  in8 float(8) const;
  out4 float(4);
  out8 float(8);
  fixed4 float(4);
  fixed8 float(8);
end-pi;

out4 = in4 * 2;
out8 = in8 / 4;
fixed4 = -1.5;
fixed8 = 1.25E10;

*inlr = *on;
return;
