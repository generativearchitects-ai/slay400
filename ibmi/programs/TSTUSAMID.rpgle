**free
// Invented probe program for the gateway (kickoff 05): how the system writes and reads midnight in the USA time format.
// An ISO midnight converted to USA gives the form the system itself uses for midnight; the USA inputs 12:00 AM and 00:00 AM
// converted to ISO show whether the system reads them as 00.00.00 or 24.00.00.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  isoMid time(*iso) const;
  usa12 time(*usa) const;
  usa00 time(*usa) const;
  usaFromIso time(*usa);
  isoFrom12 time(*iso);
  isoFrom00 time(*iso);
end-pi;

usaFromIso = isoMid;
isoFrom12 = usa12;
isoFrom00 = usa00;

*inlr = *on;
return;
