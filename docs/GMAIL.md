# Gmail Setup And Usage

This guide explains how to use the Gmail input workflow in this project, including:

- creating Google API credentials
- placing the `credentials.json` file where the program expects it
- understanding the token cache
- running Gmail exports into EA-PDF

Code reference:
- [GmailDownloadToPdf.java](../src/main/java/eapdf/GmailDownloadToPdf.java)

Official Google references used for the setup steps:
- Gmail Java quickstart: https://developers.google.com/workspace/gmail/api/quickstart/java
- Google Workspace credentials guide: https://developers.google.com/workspace/guides/manage-credentials
- Create credentials: https://developers.google.com/workspace/guides/create-credentials

## Live check — C95, 2026-10-23

The user-authorized account was checked with read-only access. Three inbox messages produced individual PDFs, an archive and a collection; another message supplied a PDF attachment. All outputs and collection children pass the existing EA-PDF, PDF/A and selected ISO checks. Original email and attachment bytes match. Repeating the three-message export skips completed outputs.

For this account, the working Google Java token store was `~/StoredCredential`; its directory is supplied with `--token-dir`. The separate `~/.gmail_token_store/StoredCredential` copy was expired. Verification used temporary private credential/token copies and left the originals untouched. Private sample PDFs are in local verification records. No email was sent, deleted, relabeled or marked read.

## What This Workflow Does

The Gmail entrypoint connects to the Gmail API, downloads matching messages in raw RFC822 form, maps them into the existing email pipeline, and writes either:

- one PDF per message, or
- one archive/collection PDF via `--archive-profile`

Current Gmail behavior from the code:
- OAuth desktop-app login flow
- Gmail scope: `GMAIL_READONLY`
- local browser callback on port `8888`
- token cache stored in a local directory

## Output filenames

New individual PDFs use a readable subject followed by `_gmail-` and the message ID. Different messages with the same subject keep separate files when the selection or ordering changes. Long subject portions are shortened to fit the filesystem; full subjects remain in the PDFs. Existing completed resume entries continue using their recorded filenames. Original emails follow the selected preservation profile.

Automatically named archive and collection PDFs gain `_2`, `_3`, and so on if the filename is already taken, preserving earlier exports. A path supplied with `--archive-output` is treated as the requested replacement destination.

## Resume An Interrupted Export

Set `EAPDF_GMAIL_RESUME_DIR` to a private directory before running the same Gmail command again:

```bash
export EAPDF_GMAIL_RESUME_DIR="$HOME/.local/share/emailtopdfa/gmail-resume"
java -cp dist/emailtopdfa-2.1.0-beta.1.jar eapdf.GmailDownloadToPdf --folder INBOX --out-pdf /tmp/eapdf-gmail
```

The existing credential discovery and OAuth settings still apply. `-Deapdf.gmailResumeDir=/private/path` is the equivalent JVM setting.

The first attempt freezes the selected Gmail message IDs and their order. Completed ordinary PDFs are skipped only when their bytes still match the checkpoint. Missing or changed outputs are recreated from staged source emails. Archive jobs reassemble the selected messages from staged downloads; they do not need to download those messages again. Transient downloads receive at most three application-level attempts. Export settings, the authenticated account, output location, and release version distinguish checkpoints, and a file lock prevents two processes from updating the same export together.

The printed resume directory contains original private email bytes, labels, output paths, and checksums. It is retained until explicitly deleted, including after success. POSIX directories use mode `0700` and files use `0600`. Delete that export's printed directory to discard its snapshot and begin a new selection that includes newer matching messages. Unsetting the variable disables future persistent staging but does not delete earlier checkpoints. With no resume directory configured, staging remains temporary and is removed on exit.

## Prerequisites

- a Google account with Gmail enabled
- access to Google Cloud Console
- the Gmail API enabled for your Google Cloud project
- a Desktop OAuth client downloaded as `credentials.json`

## Step 1: Create Or Choose A Google Cloud Project

In Google Cloud Console:

1. Create a new project, or select an existing one.
2. Enable the Gmail API for that project.

Official reference:
- https://developers.google.com/workspace/gmail/api/quickstart/java

## Step 2: Configure OAuth Consent

In Google Cloud Console:

1. Open `Google Auth platform`.
2. Configure the OAuth consent screen if Google asks for it.
3. For a simple local/test workflow, follow Google’s current quickstart guidance.

Important practical note:
- if the app is in testing mode, the Google account you use must usually be added as a test user

## Step 3: Create The OAuth Client

Create a desktop OAuth client, not a web client.

In Google Cloud Console:

1. Go to `Google Auth platform > Clients`
2. Click `Create Client`
3. Choose `Desktop app`
4. Give it a name
5. Download the JSON credentials file

The code expects a standard Google desktop OAuth client JSON file.

## Step 4: Put `credentials.json` In The Right Place

The program resolves credentials in this order:

1. `--credentials /path/to/credentials.json`
2. `config/credentials.json`
3. `credentials.json` in the project root

Recommended location:
- `config/credentials.json`

The [public example](../config/credentials.example.json) shows the expected structure;
use your own downloaded OAuth client file. Do not commit real credentials.

## Step 5: Understand The Token Store

On first successful login, the Google client libraries write OAuth tokens into a local token directory.

Default token store from the code:
- `~/.gmail_token_store`

You can override that with:
- `--token-dir /path/to/token_store`

The token cache is separate from `credentials.json`:

- `credentials.json`: identifies the OAuth client
- token store: stores the user authorization and refresh token

If you need to force a fresh consent/login:
- delete the token directory you used previously

## Step 6: First Run Behavior

On the first Gmail run:

1. the program starts a local receiver on port `8888`
2. your browser opens the Google login/consent flow
3. after you approve, Google redirects back to the local receiver
4. the token is stored in the token directory

Relevant code:
- [authorize(...)](../src/main/java/eapdf/GmailDownloadToPdf.java)

## Common Commands

### One PDF Per Message

```bash
java -cp dist/emailtopdfa-2.1.0-beta.1.jar eapdf.GmailDownloadToPdf \
  --folder INBOX \
  --out-pdf out
```

### Archive Output (`1m`, `1mi`, `1c`, `1ci`)

```bash
java -cp dist/emailtopdfa-2.1.0-beta.1.jar eapdf.GmailDownloadToPdf \
  --folder INBOX \
  --archive-profile m \
  --archive-output /tmp/eapdf-gmail-inbox_1m.pdf
```

### Use An Explicit Credentials File

```bash
java -cp dist/emailtopdfa-2.1.0-beta.1.jar eapdf.GmailDownloadToPdf \
  --credentials config/credentials.json \
  --folder ALL_MAIL
```

### Use A Custom Token Directory

```bash
java -cp dist/emailtopdfa-2.1.0-beta.1.jar eapdf.GmailDownloadToPdf \
  --credentials config/credentials.json \
  --token-dir .gmail_token_store_local \
  --folder ALL_MAIL
```

### Only Unread Messages

```bash
java -cp dist/emailtopdfa-2.1.0-beta.1.jar eapdf.GmailDownloadToPdf \
  --folder INBOX \
  --unread-only
```

### Limit The Number Of Downloaded Messages

```bash
java -cp dist/emailtopdfa-2.1.0-beta.1.jar eapdf.GmailDownloadToPdf \
  --folder INBOX \
  --limit 100
```

## Gmail CLI Switches

Current Gmail switches from the code:

- `--user`
- `--credentials`
- `--token-dir`
- `--folder`
- `--out-pdf`
- `--debug-dir`
- `--font`
- `--icc`
- `--global-id` / `-g`
- `--email` / `-e`
- `--limit`
- `--omit-source`
- `--archive-profile`
- `--archive-output`
- `--unread-only`
- `--debug`

## Folder / Label Behavior

`--folder` is used as the Gmail label/folder selector.

Examples:
- `INBOX`
- `SENT`
- `ALL_MAIL`
- a custom Gmail label

The selector accepts actual Gmail label IDs or exact label names. Names are resolved before querying Gmail; unknown or ambiguous names produce an error. A selection matching one complete label name takes precedence over splitting it at commas, so a label such as `Team, Finance` can be selected intact. The existing `ALL`, `ALL_MAIL`, and `*` aliases still select all mail. Message labels are retained as separate values for collection grouping, even when their names contain commas.

## Current Gmail Query Behavior

The current code selects all messages returned for the chosen mailbox/labels, subject only to `--unread-only` and `--limit`. There is no hardcoded participant-address filter.

`--email` records archive-owner metadata; it does not exclude other participants' messages.

This is implemented in:
- [buildMessageQuery(...)](../src/main/java/eapdf/GmailDownloadToPdf.java)

## Troubleshooting

### `missing --credentials /path/to/credentials.json`

The program did not find:

- an explicit `--credentials` path
- `config/credentials.json`
- or `credentials.json` in the project root

### Browser Does Not Complete Login

Check:

- port `8888` is not blocked or already in use
- your browser can open the Google consent page
- your OAuth client is a `Desktop app`
- your Google account is permitted to use the OAuth app

### Need To Re-Authorize

Delete the token directory, then run again.

Default:
- `~/.gmail_token_store`

### Wrong Account Or Stale Tokens

Use a fresh token directory:

```bash
java -cp dist/emailtopdfa-2.1.0-beta.1.jar eapdf.GmailDownloadToPdf \
  --credentials config/credentials.json \
  --token-dir .gmail_token_store_test \
  --folder INBOX
```

## Notes On Security

- treat `credentials.json` as sensitive configuration
- treat the token store as sensitive user access data
- do not publish either one in a public repo

## Notes On This Project’s Defaults

Current defaults from the code:

- credentials path fallback: `config/credentials.json`
- token store fallback: `~/.gmail_token_store`
- OAuth callback port: `8888`
- Gmail scope: read-only

That means the Gmail workflow is designed for download-and-preserve behavior, not mailbox modification.
