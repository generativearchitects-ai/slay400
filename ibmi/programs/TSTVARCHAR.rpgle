**free
// Invented test program for the gateway (kickoff 04, program 7): variable-length character fields with the 2-byte and the
// 4-byte prefix, empty, partly filled and full; a fixed character field with characters that differ between EBCDIC code pages.
// The inputs come back as they were sent; every other output is a fixed value.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  in2 varchar(10) const;
  in4 varchar(10:4) const;
  inText char(16) const;
  echo2 varchar(10);
  echo4 varchar(10:4);
  empty2 varchar(10);
  part2 varchar(10);
  full2 varchar(10);
  empty4 varchar(10:4);
  part4 varchar(10:4);
  full4 varchar(10:4);
  echoText char(16);
  pages char(16);
end-pi;

echo2 = in2;
echo4 = in4;
empty2 = '';
part2 = 'ABC';
full2 = 'ABCDEFGHIJ';
empty4 = '';
part4 = 'XYZ';
full4 = 'KLMNOPQRST';
echoText = inText;
pages = '@!{}[]|~#$ÄÖÜß';

*inlr = *on;
return;
