**free
// Invented test program for the gateway (kickoff 03, program 2): one struct parameter, in and out.
// Deterministic: prefixes the code with X, adds one to the quantity, doubles the price, sets the status to U.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-ds item_t qualified template;
  code char(6);
  quantity packed(5:0);
  price zoned(7:2);
  status char(1);
end-ds;

dcl-pi *n;
  item likeds(item_t);
end-pi;

item.code = 'X' + %subst(item.code: 1: 5);
item.quantity = item.quantity + 1;
item.price = item.price * 2;
item.status = 'U';

*inlr = *on;
return;
