Invented fixtures for the tests: small API repositories (PCML, overlay, mock files). The tests build their bundles from them
with the modules of slay400-core; no binary bundle is committed. Names and values are made up.

- `shop`: two programs for the pipeline and the mock strategy.
- `ibmi`: the five test programs of `ibmi/programs/` and one program that does not exist, for the `ibmi` backend. Its PCML
  files are what the repository believes the RPG compiler writes; the setup tool of the `ibmi` profile compares them with the
  compiler's output.
