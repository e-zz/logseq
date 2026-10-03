/**
 * Protocols Logseq opens silently, without a confirmation dialog.
 *
 * `http:`, `https:` and `mailto:` have always opened silently. `file:`,
 * `zotero:` and `logseq:` are handed to apps already installed on the
 * machine, so confirming each one is noise, not a security boundary.
 * Anything outside this set still asks the user first.
 *
 * This is the single source of truth for the main process
 * (`electron.window/open-default-app!`).
 *
 * Exported onto `module.exports` directly rather than via a top-level
 * `const`: shadow-cljs's CommonJS wrapper rewrites top-level bindings to
 * reuse the wrapper's `require` parameter, which clobbers `require` inside
 * the module.
 */
module.exports.ALLOWED_EXTERNAL_PROTOCOLS = new Set([
  'https:',
  'http:',
  'mailto:',
  'zotero:',
  'file:',
  'logseq:',
])
