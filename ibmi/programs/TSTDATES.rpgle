**free
// Invented test program for the gateway (kickoff 04, program 9): date, time and timestamp parameters in several formats.
// The input date comes back one day later in *ISO and unchanged in *EUR, *JUL and *YMD with a period; the lowest and highest
// dates of *ISO and *MDY; the input time one hour later in *HMS and unchanged in *USA and *EUR; the input timestamp one
// second later, and the lowest timestamp.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  inDate date(*iso) const;
  inTime time(*iso) const;
  inStamp timestamp const;
  isoNext date(*iso);
  isoLow date(*iso);
  isoHigh date(*iso);
  mdyLow date(*mdy);
  mdyHigh date(*mdy);
  eurDate date(*eur);
  julDate date(*jul);
  ymdDot date(*ymd.);
  hmsNext time(*hms);
  usaTime time(*usa);
  eurTime time(*eur);
  stampNext timestamp;
  stampLow timestamp;
end-pi;

isoNext = inDate + %days(1);
isoLow = *loval;
isoHigh = *hival;
mdyLow = *loval;
mdyHigh = *hival;
eurDate = inDate;
julDate = inDate;
ymdDot = inDate;
hmsNext = inTime + %hours(1);
usaTime = inTime;
eurTime = inTime;
stampNext = inStamp + %seconds(1);
stampLow = *loval;

*inlr = *on;
return;
