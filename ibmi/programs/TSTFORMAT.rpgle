**free
// Invented test program for the gateway (kickoff 05, program 12): the date and time formats and separators not seen yet.
// The input date comes back in *DMY, *USA and *JIS with their default separators, and in *MDY with a comma and with a blank
// (&); the input time in *JIS, and in *HMS with a comma and with a blank.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  inDate date(*iso) const;
  inTime time(*iso) const;
  dmyDate date(*dmy);
  usaDate date(*usa);
  jisDate date(*jis);
  mdyComma date(*mdy,);
  mdyBlank date(*mdy&);
  jisTime time(*jis);
  hmsComma time(*hms,);
  hmsBlank time(*hms&);
end-pi;

dmyDate = inDate;
usaDate = inDate;
jisDate = inDate;
mdyComma = inDate;
mdyBlank = inDate;
jisTime = inTime;
hmsComma = inTime;
hmsBlank = inTime;

*inlr = *on;
return;
