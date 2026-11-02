# Normalized input inventory corpus

`PreservationInventoryTests` freezes expected inputs before mutation and checks
final bytes through the atomic creation gate. Its authored source is
`../batch11/source.eml`; the Java recipe substitutes A/B/C subjects and native
payloads. Nine positive cases preserve all inputs or intentionally omit original
sources under isolation policy. Sixteen negative cases omit messages, omit native
attachments, change retained/native bytes, swap attachment owners, reuse one
annotation for two identical occurrences, or remove a listed collection child.

Generate and independently inspect:

```
mvn -o -Deapdf.build.directory=/tmp/eapdf-improvement-batch12/build -Dtest=PreservationInventoryTests -Deapdf.inventoryCorpus=/media/lepore/Work/thinkbox/convert/docs/improvement/batch-12-corpus test
env -u PYTHONPATH python3 -B scripts/verify_eapdf_input_inventory_fixtures.py
```

Each candidate has `.expected-inputs.json` captured before mutation and
`.creator.json` with the acceptance/rejection diagnostic. The Python verifier
checks input hashes against authored MIME bytes, independently compares final
content and then records PDF-only EA-PDF/PDF-A outcomes. Missing expected inputs
can coexist with PDF-only PASS; the external inventory is essential to those
comparisons. Existing destination bytes are asserted unchanged on rejection.

This boundary begins with normalized records at writer entry. It cannot certify
messages or assets omitted earlier by a reader or input dispatcher. No new CLI
option, production sidecar or independent publication gate is introduced.
