**free
// Invented test program for the gateway (kickoff 04, program 10, rewritten after the first run, decision 122): arrays of a
// fixed dimension with a separate count, which is how RPG programs pass a variable number of elements. The count of the
// items is another parameter; the count of the lines is a member of the same struct as the lines. Items ITEM1 to ITEMn are
// filled for n = wanted (at most 10), lines LINE1 to LINEm for m = wanted (at most 5); the rest stays blank.
// (The first version, with varying-dimension arrays dim(*var:n) as a parameter and as a subfield, did not compile:
// RNS9308, severity 30.)
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-ds box_t qualified template;
  n int(10);
  lines char(6) dim(5);
end-ds;

dcl-pi *n;
  wanted int(10) const;
  count int(10);
  items char(8) dim(10);
  box likeds(box_t);
end-pi;

dcl-s i int(10);
dcl-s limit int(10);

clear items;
clear box;
limit = wanted;
if limit > 10;
  limit = 10;
elseif limit < 0;
  limit = 0;
endif;
count = limit;
for i = 1 to limit;
  items(i) = 'ITEM' + %char(i);
endfor;

if limit > 5;
  limit = 5;
endif;
box.n = limit;
for i = 1 to limit;
  box.lines(i) = 'LINE' + %char(i);
endfor;

*inlr = *on;
return;
