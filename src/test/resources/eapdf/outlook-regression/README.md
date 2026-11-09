# Synthetic Outlook regression fixtures

These small MSG files reproduce the specific property conditions formerly tested
with two unavailable local sources. They are synthetic test inputs, not recovered
copies of those messages or substitutes for broad real-world conversion coverage.
They are required test resources: a missing file fails the affected test.

| Fixture | Reproduced conditions | Former local dependency |
| --- | --- | --- |
| `native-reply.msg` | No transport headers; submission time `2019-10-08T14:07:48.3287079Z` at FILETIME's 100 ns precision; native reply/reference fields; zero attachments; unresolved represented author distinct from the SMTP delegate | `2019.10.08 DEP response.msg` |
| `transport-recipient-order.msg` | Native recipient rows ordered second/first, transport headers ordered first/second, with different native/header display names | `2019.10.10a additional comments and questions for DEP.msg` |

All names, addresses and body text are invented. The timestamp retains the original
regression value. The creator tests check XMP precision, embedded source bytes,
thread reconstruction and precedence, and transport ordering without replacing
native names. Validator tests check native evidence, ambiguous author handling,
a matching metadata baseline, and contradictions in subject, size, timestamp
(including loss of its fraction) and attachment count. Existing real-source tests
remain separate; the restored Weary-comments source is still optional local data.

`GenerateFixtures.java` writes the property tables and streams directly using POI.
No production reader or validator generates the expected properties. From the
repository root, using Java 21 and the versioned creator JAR (which includes POI):

```sh
java --class-path dist/emailtopdfa-2.0.0-beta.1.jar \
  src/test/resources/eapdf/outlook-regression/GenerateFixtures.java \
  /tmp/eapdf-outlook-fixture-reproduction
cmp src/test/resources/eapdf/outlook-regression/native-reply.msg \
  /tmp/eapdf-outlook-fixture-reproduction/native-reply.msg
cmp src/test/resources/eapdf/outlook-regression/transport-recipient-order.msg \
  /tmp/eapdf-outlook-fixture-reproduction/transport-recipient-order.msg
```

Verified SHA-256 values (POI 5.5.1):

```text
e291d186d805f1a4f1e5ae692c3ce935aa0e55796fe864e941eb7acce564ec6e  native-reply.msg
aac7e95c8f56e63d2491f35b1c1ccbd10783fafdcbed47b2b50289a8ab1f50db  transport-recipient-order.msg
```

Focused verification, from the repository root:

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-outlook-creator \
  -Dtest=MsgSentDateTests,MsgThreadPreservationTests,MsgAddressPreservationTests test
mvn -o -f validator-desktop/pom.xml \
  -Deapdf.build.directory=/tmp/eapdf-outlook-validator -Dtest=OutlookSourceTests test
```

On 2026-11-09 these ran 13 creator and 28 validator cases, all passing with no skips,
with the other real-source fixtures installed. Fixture regeneration matched both
MSG files byte for byte. This does not establish full-suite or PDF conformance.
