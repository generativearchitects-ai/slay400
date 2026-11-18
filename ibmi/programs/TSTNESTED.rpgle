**free
// Invented test program for the gateway (kickoff 03, program 4): a struct holding an array of structs, and the three
// fields of an error convention. The key FAIL makes the program report an error; any other key fills three lines.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-ds line_t qualified template;
  sku char(8);
  qty int(10);
end-ds;

dcl-ds order_t qualified template;
  total packed(9:2);
  lines likeds(line_t) dim(3);
end-ds;

dcl-pi *n;
  key char(5) const;
  order likeds(order_t);
  errFlag char(2);
  errCode char(7);
  errText char(40);
end-pi;

dcl-s i int(10);

clear order;
if key = 'FAIL';
  errFlag = 'KO';
  errCode = 'TST0001';
  errText = 'The key is refused';
  *inlr = *on;
  return;
endif;

errFlag = 'OK';
errCode = *blanks;
errText = *blanks;
for i = 1 to 3;
  order.lines(i).sku = %trimr(key) + '-' + %char(i);
  order.lines(i).qty = i * 10;
endfor;
order.total = 123.45;

*inlr = *on;
return;
