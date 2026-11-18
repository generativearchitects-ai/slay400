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

The PCML that the repository believes the compiler writes for each program, and the overlay of its API, are in
`fixtures/ibmi/apis/<PROGRAM>/`. `fixtures/ibmi/apis/TSTNONE` describes a program that is never created.

The programs are compiled only by the setup tool of the `ibmi` Maven profile, which the owner runs through the `ibmi` workflow:
see `gateway-ibmi/README.md`.
