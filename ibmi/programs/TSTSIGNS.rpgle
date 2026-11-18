**free
// Invented test program for the gateway (kickoff 04, program 6): signs and ranges.
// The zoned input comes back negated; every other output is a fixed value: zoned and packed numbers negative, zero and at
// their largest, 2-, 4- and 8-byte integers at their smallest and largest, unsigned integers at their largest.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  inZoned zoned(7:2) const;
  zonedNeg zoned(7:2);
  zonedZero zoned(7:2);
  zonedMax zoned(7:2);
  packedNeg packed(7:2);
  packedZero packed(7:2);
  packedMax packed(7:2);
  int2Min int(5);
  int2Max int(5);
  int4Min int(10);
  int4Max int(10);
  int8Min int(20);
  int8Max int(20);
  uns2Max uns(5);
  uns4Max uns(10);
  uns8Max uns(20);
end-pi;

zonedNeg = -inZoned;
zonedZero = 0;
zonedMax = *hival;
packedNeg = -12345.67;
packedZero = 0;
packedMax = *hival;
int2Min = *loval;
int2Max = *hival;
int4Min = *loval;
int4Max = *hival;
int8Min = *loval;
int8Max = *hival;
uns2Max = *hival;
uns4Max = *hival;
uns8Max = *hival;

*inlr = *on;
return;
