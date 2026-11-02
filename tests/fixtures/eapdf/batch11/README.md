# Final collection child acceptance

The authored CRLF MIME message contains one plain-text body and one native
attachment. `CollectionChildAcceptanceTests` creates c/ci collections from it
and retains eight PDFs when `eapdf.childAcceptanceCorpus` is set. Each mutation
changes one child property: required Sent, page DPart, or native-attachment MD5.
It reserializes the child and updates the parent's embedded size/checksum to
describe the actual replacement bytes. The tests require rejection before an
existing destination can be replaced. They also check valid/invalid grandchildren,
eight permitted nesting levels, and shared count/expanded-byte limits.

Generate with:

```
mvn -o -Deapdf.build.directory=/tmp/eapdf-improvement-batch11/build -Dtest=CollectionChildAcceptanceTests -Deapdf.childAcceptanceCorpus=/media/lepore/Work/thinkbox/convert/docs/improvement/batch-11-corpus test
env -u PYTHONPATH python3 -B scripts/verify_eapdf_collection_child_fixtures.py
```

`cases.json` declares exact independent results. The Python verifier inspects
the changed PDF objects, checks the outer embedded checksum, and requires both
container and child PDF/A PASS. Both valid files must PASS EA-PDF. Six mutations
must fail only the declared child rule plus the container's child-validation
rule. The creator's gate remains a narrower Java consistency check, without an
integrated independent PDF/A/complete EA-PDF acceptance gate or original-input
completeness inventory. It cannot certify content omitted before this boundary.
