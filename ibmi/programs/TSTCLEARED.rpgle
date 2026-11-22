**free
// Invented test program for the gateway (kickoff 06, program 15): what a cleared structure holds. Three arrays of the same
// struct; the first elements, as many as the input says (at most 3), are filled, and the rest are left as they are: in the
// first array after clear, in the second as inz left them, in the third as a declaration without inz left them.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-ds rec_t qualified template;
  name char(4);
  amount packed(5:2);
  isoDate date(*iso);
  ymdDate date(*ymd);
  atTime time(*iso);
  stamp timestamp;
end-ds;

dcl-pi *n;
  filled int(10) const;
  cleared likeds(rec_t) dim(3);
  inzd likeds(rec_t) dim(3);
  untouched likeds(rec_t) dim(3);
end-pi;

dcl-ds byClear likeds(rec_t) dim(3);
dcl-ds byInz likeds(rec_t) dim(3) inz;
dcl-ds byNothing likeds(rec_t) dim(3);
dcl-s i int(10);
dcl-s n int(10);

clear byClear;
n = filled;
if n > 3;
  n = 3;
elseif n < 0;
  n = 0;
endif;
for i = 1 to n;
  byClear(i).name = 'R' + %char(i);
  byClear(i).amount = i * 1.25;
  byClear(i).isoDate = d'2026-11-22' + %days(i);
  byClear(i).ymdDate = d'2026-11-22' + %days(i);
  byClear(i).atTime = t'10.00.00' + %minutes(i);
  byClear(i).stamp = z'2026-11-22-10.00.00.000000' + %seconds(i);
  byInz(i) = byClear(i);
  byNothing(i) = byClear(i);
endfor;
for i = 1 to 3;
  cleared(i) = byClear(i);
  inzd(i) = byInz(i);
  untouched(i) = byNothing(i);
endfor;

*inlr = *on;
return;
