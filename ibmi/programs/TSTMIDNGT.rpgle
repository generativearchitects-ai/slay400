**free
// Invented test program for the gateway (kickoff 05, program 11): midnight and the end of the day.
// For each accepted time format: the input time returned unchanged, and the lowest and highest time. Two USA inputs, to see
// which texts the system takes as midnight. Timestamps: the input one second later, and the lowest and highest timestamp.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  isoIn time(*iso) const;
  hmsIn time(*hms) const;
  usaIn time(*usa) const;
  usaIn2 time(*usa) const;
  eurIn time(*eur) const;
  inStamp timestamp const;
  isoEcho time(*iso);
  isoLow time(*iso);
  isoHigh time(*iso);
  hmsEcho time(*hms);
  hmsLow time(*hms);
  hmsHigh time(*hms);
  usaEcho time(*usa);
  usaEcho2 time(*usa);
  usaLow time(*usa);
  usaHigh time(*usa);
  eurEcho time(*eur);
  eurLow time(*eur);
  eurHigh time(*eur);
  stampNext timestamp;
  stampLow timestamp;
  stampHigh timestamp;
end-pi;

isoEcho = isoIn;
isoLow = *loval;
isoHigh = *hival;
hmsEcho = hmsIn;
hmsLow = *loval;
hmsHigh = *hival;
usaEcho = usaIn;
usaEcho2 = usaIn2;
usaLow = *loval;
usaHigh = *hival;
eurEcho = eurIn;
eurLow = *loval;
eurHigh = *hival;
stampNext = inStamp + %seconds(1);
stampLow = *loval;
stampHigh = *hival;

*inlr = *on;
return;
