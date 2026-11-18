**free
// Invented test program for the gateway (kickoff 03, program 3): an array of structs with a count field.
// Deterministic: fills rows 1 to the wanted number (at most 10): id i, label 'ROW i', amount i * 1.5; count is the number filled.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-ds row_t qualified template;
  id int(10);
  label char(12);
  amount packed(7:2);
end-ds;

dcl-pi *n;
  wanted int(10) const;
  count int(10);
  rows likeds(row_t) dim(10);
end-pi;

dcl-s limit int(10);
dcl-s i int(10);

clear rows;
limit = wanted;
if limit > 10;
  limit = 10;
elseif limit < 0;
  limit = 0;
endif;
count = limit;
for i = 1 to limit;
  rows(i).id = i;
  rows(i).label = 'ROW ' + %char(i);
  rows(i).amount = i * 1.5;
endfor;

*inlr = *on;
return;
