**free
// Invented probe program for the gateway (kickoff 05): a date in the *LONGJUL format.
// The input comes back in the field below. If the compiler refuses the field as a parameter, that is the finding.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pi *n;
  inDate date(*iso) const;
  outDate date(*longjul);
end-pi;

outDate = inDate;

*inlr = *on;
return;
