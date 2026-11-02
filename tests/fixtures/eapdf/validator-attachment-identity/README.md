# Supplied attachment identity type

Regenerate with `python3 -B scripts/build_eapdf_attachment_identity_fixtures.py`.
Table 19 makes Mail_GUID presence conditional on Message-ID; its prescribed text
type still applies when the field is supplied. Five legal forms and three
malformed supplied-GUID forms use the same source PDF and retain a valid owning
Message-ID. Normal, absent, qualified and attribute forms pass, as does an
unrelated foreign-namespace extension. URI, array and structured Mail_GUID
values fail exactly ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID. No GUID lexical
format or additional identity requirement is introduced.

The manifest records source/output hashes. Complete same-byte before/after
reports are retained in `docs/improvement/validator-15`.
