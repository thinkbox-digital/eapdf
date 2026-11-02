# Batch 05 effective UserProperties fixtures

ISO 32000-2:2020 Table 355 and 14.7.6.1–.4, with current clause 14 errata.
Recipes use explicit PDF objects independently of the validator resolver. Classes apply in array order, direct attributes override classes, and the final P replaces the whole earlier P array. Revision numbers do not rank values. An undefined class contributes no attributes.

Eighteen creator-derived PDFs cover dictionary/array classes, class/direct revision metadata, repeated owners, direct overrides, whole-P replacement/empty P and source comparisons through class-only properties. The cases declare exact mandatory failures; source mismatches remain recommendation WARN. Malformed-resolution cases belong to focused unit tests because independent PDF/A validity is not assumed.

Run with EAPDF_RULE_FIXTURE_SET=batch05 and EAPDF_GENERATOR_CLASSPATH pointing to the freshly packaged creator. Final acceptance archives the generated corpus before temporary cleanup.
