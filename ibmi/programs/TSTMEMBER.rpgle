**free
// Invented test program for the gateway (kickoff 06, program 14): an array of structs, each with its own count and a small
// array. Group i (1 to 4) gets the count given by input i (0 to 3) and its first items filled as Iij; the rest stay blank.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-ds group_t qualified template;
  n int(10);
  items char(4) dim(3);
end-ds;

dcl-pi *n;
  fill1 int(10) const;
  fill2 int(10) const;
  fill3 int(10) const;
  fill4 int(10) const;
  groups likeds(group_t) dim(4);
end-pi;

dcl-s fills int(10) dim(4);
dcl-s i int(10);
dcl-s j int(10);
dcl-s n int(10);

clear groups;
fills(1) = fill1;
fills(2) = fill2;
fills(3) = fill3;
fills(4) = fill4;
for i = 1 to 4;
  n = fills(i);
  if n > 3;
    n = 3;
  elseif n < 0;
    n = 0;
  endif;
  groups(i).n = n;
  for j = 1 to n;
    groups(i).items(j) = 'I' + %char(i) + %char(j);
  endfor;
endfor;

*inlr = *on;
return;
