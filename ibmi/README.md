# Test programs for IBM i

Invented ILE RPG programs, free-form, for the tests of the `ibmi` backend (gateway kickoff 03). Each is a few lines with
deterministic behaviour that a test can assert. Names and values are made up.

| Program | Shape | Behaviour |
|---|---|---|
| `TSTSCALAR` | scalars: char, packed, zoned, int, in and out | echoes the text after `ECHO:`, doubles the packed value, adds one to the zoned value, triples the integer |
| `TSTSTRUCT` | one struct, in and out | prefixes the code with `X`, adds one to the quantity, doubles the price, sets the status to `U` |
| `TSTARRAY` | an array of 10 structs with a count field | fills rows 1 to `wanted` (at most 10): id `i`, label `ROW i`, amount `i * 1.5` |
| `TSTNESTED` | a struct holding an array of 3 structs, and the three error fields | the key `FAIL` sets `KO`, `TST0001` and a message; any other key fills three lines and a total of 123.45 |
| `TSTWAIT` | an int in, a char out | waits the given number of seconds, 1 to 10, then sets `Y` |
| `TSTSIGNS` | zoned, packed, int and uns at their limits | the zoned input negated; zoned and packed -12345.67 (packed), 0 and all nines; 2-, 4- and 8-byte integers at their smallest and largest; unsigned integers at their largest |
| `TSTVARCHAR` | varchar with a 2- and a 4-byte prefix, char | the varchar and char inputs echoed; varchar outputs empty, `ABC`/`XYZ` and full; a char field `@!{}[]\|~#$ÄÖÜß`, whose bytes differ between EBCDIC code pages |
| `TSTFLOAT` | float of 4 and 8 bytes, raw bytes | the 4-byte input doubled, the 8-byte input divided by 4, fixed -1.5 and 1.25E10; the bytes echoed and fixed `00FF7F80` |
| `TSTDATES` | date, time and timestamp in several formats | the input date one day later (`*ISO`) and unchanged (`*EUR`, `*JUL`, `*YMD.`); the lowest and highest dates of `*ISO` and `*MDY`; the input time one hour later (`*HMS`) and unchanged (`*USA`, `*EUR`); the input timestamp one second later, and the lowest timestamp |
| `TSTCOUNTS` | arrays whose number of elements a parameter, or a member of the same struct, gives | items `ITEM1` to `ITEMn` and lines `LINE1` to `LINEm` for the number asked for, at most 10 and 5; written with varying-dimension arrays |

The PCML the compiler wrote for each program, as the setup tool brought it back (decision 119), and the overlay of its API,
are in `fixtures/ibmi/apis/<PROGRAM>/`; programs 6 to 10 get theirs from their first run. `fixtures/ibmi/apis/TSTNONE` describes a program that is never created.

The programs are compiled only by the setup tool of the `ibmi` Maven profile, which the owner runs through the `ibmi` workflow:
see `gateway-ibmi/README.md`.
