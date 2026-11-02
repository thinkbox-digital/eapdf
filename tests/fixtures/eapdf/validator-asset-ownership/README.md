# Source-asset association

The retained `_source.pdf` was generated from `source-1.eml` and `source-2.eml`
with the verified creator JAR, SHA-256
`272ad741590552489dfb6d096f7ee4b01035fb3faf1c76d817394c0c2ad5e16a`.
The inputs intentionally have the same original basename in separate folders.
The creator exports them as distinct generated filenames.

`scripts/build_eapdf_asset_ownership_fixtures.py` authors six cases from that
control. The assets Bag is unordered. Original XMP filenames can include paths;
embedded PDF filenames can be sanitized or generated. Reversing the Bag must
not let a matching filename tail override a unique matching checksum.

Four valid permutations must pass. An identifiable incorrect checksum must
fail. The final case has ambiguous names and checksums matching no source;
association remains unavailable, while the independently provable checksum
violation still fails. Expected warnings are separate from mandatory failures.

Recipes, hashes and exact outcomes are in `expectations.json`; before/after
evidence is in `docs/improvement/validator-12/`. These comparisons do not prove
original filename identity, secure hash identity or complete source enumeration.
