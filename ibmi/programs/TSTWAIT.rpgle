**free
// Invented test program for the gateway (kickoff 03, program 5): waits the given number of seconds (1 to 10), for the time-out.
ctl-opt dftactgrp(*no) actgrp(*caller) option(*srcstmt);

dcl-pr qcmdexc extpgm('QCMDEXC');
  command char(200) const options(*varsize);
  length packed(15:5) const;
end-pr;

dcl-pi *n;
  seconds int(10) const;
  done char(1);
end-pi;

dcl-s wait int(10);
dcl-s command varchar(200);

wait = seconds;
if wait < 1;
  wait = 1;
elseif wait > 10;
  wait = 10;
endif;
command = 'DLYJOB DLY(' + %char(wait) + ')';
qcmdexc(command: %len(command));
done = 'Y';

*inlr = *on;
return;
