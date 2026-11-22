# EA-PDF Desktop Viewer

Open a local PDF with **Open PDF…**, drag a file onto the window, or pass a path
at launch. The desktop interface follows the Full extension's header, page
toolbar, adjustable panes, search panel, and footer. It uses system interface
fonts and renders PDF text at the visible size and display pixel density.

## Read and navigate

- Browse messages, expand **PDF bookmarks**, or switch to Conversations in
  the navigation pane. Drag a divider or focus it and use Left and Right.
  Fresh layouts give each sidebar 27% of the width, matching the Full
  extension. Use **Show messages** and **Show files** to restore either
  hidden pane.
- Select a message to jump to its body pages and see its associated files.
  Use Up and Down in the message pane to open adjacent rows. In the PDF pane,
  Up and Down scroll the document. Filter by subject, sender or date.
  **All files** restores the full file list.
- Open a collection member in the same window. Folders start collapsed.
  Opening a member reveals its containing folders while keeping the collection
  tree in place. Use **Parent** or the header path to return to the containing
  PDF at its previous scroll position. Wheel gestures at a document boundary
  can open a sibling in the same verified folder.
- Use the page number, page arrows, Fit page, Fit width or zoom percentages.
  Continuous scrolling updates the page number and active message.
  The app remembers the window bounds, pane layout, and zoom. Opening or
  saving a PDF keeps a maximized window in place.
- Open embedded PDFs, preview supported images or inert text, and save the
  original bytes of any readable asset with the eye and download icons.
  File cards show variants and recorded attachment pages. Internal links can
  jump to a page or open an embedded file.
- In a PDF Portfolio, browse the file list and the selected PDF together.
  Use **Details**, **Tiles**, **Folder tree**, **Filmstrip**, or **Preview and
  details**. Filter by filename and metadata, use the folder history and path,
  or move to the previous or next file. **Cover page** returns to the
  container. **Read PDF** hides the Portfolio pane.
- The status bar shows the active PDF and page, archive totals, the declared
  profile, and the desktop build version from the Maven package.
- **Print…** opens the print dialog for the active PDF. When no printer is
  configured, it offers to save a copy of the PDF.

## Search and export

- **Search** searches this PDF or follows embedded collection PDFs under
  **All files**. Click once to open a result, or use Up and Down to open
  adjacent results. Scroll within the results to see more matches. When a
  supported embedded full-text index passes validation, the reader can use
  it for archive search.
- **Download this message** saves a standalone message or its verified body
  pages. **Export metadata…** offers configurable CSV and original XMP.
- In **Browse and export XMP streams…**, select a row to read its wrapped,
  indented XML. **Preview** opens it in a larger window. Press Esc to
  close either window. **Save ZIP…** exports the selected streams in
  their original form.
- Protected PDFs prompt for a password. PDFViewFX renders a private temporary
  working copy when a password is needed. Closing the PDF deletes that copy.

The desktop reader supports the v3 positional full-text index and falls back
to live PDF text extraction for other indexes. The extension's accessible tag
tree and specialized large-archive loading still differ.
