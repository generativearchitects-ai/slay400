**free
// Invented probe program for the gateway (kickoff 05): a timestamp with 12 digits of fractions, and one with none.
// The input comes back in the field below. If the compiler refuses the field as a parameter, that is the finding.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  inStamp timestamp(12) const;
  outStamp timestamp(12);
  outStamp0 timestamp(0);
end-pi;

outStamp = inStamp;
outStamp0 = inStamp;

*inlr = *on;
return;
