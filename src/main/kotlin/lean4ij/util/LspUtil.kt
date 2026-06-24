package lean4ij.util

object LspUtil {
    private val PREFIX = if (OsUtil.isWindows()) {
        "file:///"
    } else {
        "file://"
    }

    /**
     * See the document of [com.intellij.platform.lsp.api.LspServerDescriptor#getFileUri]
     * for the fix here:
     * The LSP spec [requires](https://microsoft.github.io/language-server-protocol/specification/#uri)
     * that all servers work fine with URIs in both formats: `file:///C:/foo` and `file:///c%3A/foo`.
     *
     * VS Code always sends a lowercased Windows drive letter, and always escapes colon
     * (see this [issue](https://github.com/microsoft/vscode-languageserver-node/issues/1280)
     * and the related [pull request](https://github.com/microsoft/language-server-protocol/pull/1786)).
     *
     * NOTE: this only ensures the `file://`/`file:///` prefix is present; it does NOT percent-escape the
     * path (e.g. the Windows drive colon). If a server is ever found to require the escaped `file:///c%3A/...`
     * form, that escaping must be added here.
     * check also [com.redhat.devtools.lsp4ij.LSPIJUtils.toTextDocumentIdentifier]
     */
    fun quote(url: String) : String {
        if (url.startsWith(PREFIX)) {
            return url
        }
        return PREFIX+url
    }

    /**
     * unquoting the path "file:///C:/Users/..." to "C:/Users/.."
     * if it's not with the pattern, then just return it
     */
    fun unquote(url: String) : String {
        if(!url.startsWith(PREFIX)) {
            return url
        }
        return url.substring(PREFIX.length)
    }
}