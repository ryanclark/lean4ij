package lean4ij.infoview

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import lean4ij.language.Lean4SyntaxHighlighter
import lean4ij.lsp.data.InteractiveDiagnostics
import lean4ij.lsp.data.MsgEmbed
import lean4ij.lsp.data.MsgEmbedExpr
import lean4ij.lsp.data.MsgEmbedGoal
import lean4ij.lsp.data.MsgEmbedTrace
import lean4ij.lsp.data.MsgUnsupported
import lean4ij.lsp.data.TaggedText
import lean4ij.lsp.data.TaggedTextAppend
import lean4ij.lsp.data.TaggedTextTag
import lean4ij.lsp.data.TaggedTextText

/**
 * A piece of a Lean diagnostic message: either natural-language [Prose] (rendered plain) or a Lean [Code]
 * expression (rendered with the editor's Lean syntax highlighting). Lean's standard `publishDiagnostics`
 * message is undifferentiated plain text; only the interactive diagnostics ([TaggedText]<[MsgEmbed]>) carry
 * the prose-vs-code structure, which is what makes the prose/code split possible.
 */
sealed class DiagSegment {
    abstract val text: String
    data class Prose(override val text: String) : DiagSegment()
    data class Code(override val text: String) : DiagSegment()
}

/**
 * Flatten a Lean interactive diagnostic [message] into an ordered list of prose/code segments.
 *
 * The message tree interleaves plain [TaggedTextText] leaves (prose) with [TaggedTextTag] nodes whose tag is
 * an [MsgEmbed]; an [MsgEmbedExpr]/[MsgEmbedGoal] embed is a Lean code subtree. We reuse the existing
 * `toInfoObjectModel().toString()` flattening (see [lean4ij.infoview.dsl.InfoObjectModel.toString]) to get the
 * concatenated code string for an embed. This function is platform-free so it can be unit tested directly.
 */
fun segmentMessage(message: TaggedText<MsgEmbed>): List<DiagSegment> {
    val out = mutableListOf<DiagSegment>()
    fun emitProse(s: String) { if (s.isNotEmpty()) out.add(DiagSegment.Prose(s)) }
    fun emitCode(s: String) { if (s.isNotEmpty()) out.add(DiagSegment.Code(s)) }
    fun walk(node: TaggedText<MsgEmbed>) {
        when (node) {
            is TaggedTextText -> emitProse(node.text)
            is TaggedTextAppend -> node.append.forEach { walk(it) }
            is TaggedTextTag -> {
                when (val embed = node.f0) {
                    is MsgEmbedExpr -> emitCode(embed.expr.toInfoObjectModel().toString())
                    is MsgEmbedGoal -> emitCode(embed.goal.toInfoObjectModel().toString())
                    is MsgUnsupported -> emitProse(embed.message)
                    // Traces are structural; render their flattened text as prose (rare in hover tooltips).
                    is MsgEmbedTrace -> emitProse(embed.toInfoObjectModel().toString())
                    else -> emitProse(embed.toInfoObjectModel().toString())
                }
                walk(node.f1)
            }
            // Defensive: any unknown TaggedText variant -> its flattened text as prose.
            else -> emitProse(node.toInfoObjectModel().toString())
        }
    }
    walk(message)
    return out
}

/**
 * Render a Lean interactive diagnostic as an HTML tooltip: prose plain, Lean code spans syntax-highlighted
 * with the same colors the editor uses (driven by [Lean4SyntaxHighlighter]'s lexer + the global color
 * scheme). Wrapped in `<pre>` so Lean's intended indentation/newlines are preserved (the default lsp4ij
 * tooltip collapses them).
 *
 * Safe to call off the EDT: it only reads the (thread-safe) global color scheme and drives a fresh,
 * thread-confined lexer.
 */
fun renderInteractiveDiagnosticHtml(diagnostic: InteractiveDiagnostics): String {
    val scheme = EditorColorsManager.getInstance().globalScheme
    val highlighter = Lean4SyntaxHighlighter()
    val body = StringBuilder()
    for (segment in segmentMessage(diagnostic.message)) {
        when (segment) {
            is DiagSegment.Prose -> body.append(escapeHtml(segment.text))
            is DiagSegment.Code -> appendHighlightedLeanCode(body, segment.text, highlighter, scheme)
        }
    }
    val sb = StringBuilder("<html><pre style=\"margin: 0;\">")
    sb.append(body)
    sb.append("</pre>")
    if (diagnostic.source.isNotBlank()) {
        sb.append("<span style=\"font-style: italic;\">").append(escapeHtml(diagnostic.source)).append("</span>")
    }
    sb.append("</html>")
    return sb.toString()
}

/**
 * Tokenize [code] with the Lean lexer and append it to [sb] as HTML, coloring each token by its
 * [Lean4SyntaxHighlighter] highlight key resolved against [scheme]. Tokens with no foreground color (most
 * whitespace/punctuation) are appended escaped but uncolored.
 */
private fun appendHighlightedLeanCode(
    sb: StringBuilder,
    code: String,
    highlighter: Lean4SyntaxHighlighter,
    scheme: EditorColorsScheme,
) {
    val lexer = highlighter.highlightingLexer
    lexer.start(code)
    while (lexer.tokenType != null) {
        val tokenText = code.substring(lexer.tokenStart, lexer.tokenEnd)
        // getTokenHighlights returns keys outer-to-inner; the innermost (last) wins for the color.
        val color = highlighter.getTokenHighlights(lexer.tokenType)
            .reversed()
            .firstNotNullOfOrNull { scheme.getAttributes(it)?.foregroundColor }
        if (color != null) {
            sb.append("<span style=\"color: #")
                .append(String.format("%06x", color.rgb and 0xFFFFFF))
                .append(";\">")
                .append(escapeHtml(tokenText))
                .append("</span>")
        } else {
            sb.append(escapeHtml(tokenText))
        }
        lexer.advance()
    }
}

private fun escapeHtml(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
