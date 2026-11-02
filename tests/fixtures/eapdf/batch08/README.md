# Batch 08 observed rendered-header inventory

Two source emails have the same Message-ID, equal-length distinct subjects and an encoded UTF-8 sender name. The creator renders five known fields per message. The fixture oracle is the input data plus the creator's documented header order, independent of inventory findings.

Six archive PDFs cover matching observed fields, swapped Subject value references, missing GUID with ambiguous duplicate Message-ID, omitted originals in mi, a wrong enclosing GUID, and class-map identity. Swapping references preserves painted PDF bytes and source assets; ParentTree owners and page references are updated independently.

Every file must pass independent PDF/A. Counts in cases.json describe observed equal/different/unavailable comparisons, not complete rendered-header coverage. Rule 107 remains partial WARN. The missing-GUID case separately fails its existing mandatory presence rule. The wrong-owner cases expose inventory discrepancies without inventing an additional normative rule. Full untagged/standard-tagged header inventory and complete source-association proof remain open.
