**free
// Invented test program for the gateway (kickoff 04, program 9): date and time parameters in several formats.
// The input date comes back one day later in *ISO and unchanged in *EUR, *JUL and *YMD with a period; the lowest and highest
// dates of *ISO and *MDY; the input time one hour later in *HMS and unchanged in *USA and *EUR. (Timestamps were taken out
// after the first run: the compiler's PCML states no format for them, so the gateway refuses them.)
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  inDate date(*iso) const;
  inTime time(*iso) const;
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

*inlr = *on;
return;
