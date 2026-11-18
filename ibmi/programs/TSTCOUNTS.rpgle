**free
// Invented test program for the gateway (kickoff 04, program 10): an array whose number of elements is given by another
// parameter, and one whose number is given by a member of the same struct, each filled up to its count: items ITEM1 to
// ITEMn for n = wanted (at most 10), and lines LINE1 to LINEm in the box for m = wanted (at most 5).
// Written with varying-dimension arrays, the language's way to have a number of elements that changes at run time.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-ds box_t qualified template;
  n int(10);
  lines char(6) dim(*var:5);
end-ds;

dcl-pi *n;
  wanted int(10) const;
  count int(10);
  items char(8) dim(*var:10);
  box likeds(box_t);
end-pi;

dcl-s i int(10);
dcl-s limit int(10);

limit = wanted;
if limit > 10;
  limit = 10;
elseif limit < 0;
  limit = 0;
endif;
count = limit;
%elem(items) = limit;
for i = 1 to limit;
  items(i) = 'ITEM' + %char(i);
endfor;

if limit > 5;
  limit = 5;
endif;
box.n = limit;
%elem(box.lines) = limit;
for i = 1 to limit;
  box.lines(i) = 'LINE' + %char(i);
endfor;

*inlr = *on;
return;
