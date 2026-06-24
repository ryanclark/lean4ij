package lean4ij.language

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor

/**
 * Conservative Lean formatter. On a whole-file Reformat Code it applies [Lean4Format.normalize] (sort
 * consecutive `import` lines, collapse blank-line runs, trim trailing whitespace). Token content is preserved
 * byte-for-byte, but trailing whitespace and runs of 2+ blank lines ARE normalized document-wide, including
 * inside expression/tactic bodies. Implemented as text normalization (not the block-indent engine) because
 * Lean keeps WHITE_SPACE as a PSI token for hover, which the indent engine can't manage. Partial-selection
 * reformats are left untouched to avoid corrupting a fragment.
 */
class Lean4ImportSortPostFormatProcessor : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement = source

    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        if (source !is Lean4PsiFile) return rangeToReformat
        val doc = source.viewProvider.document ?: return rangeToReformat
        // Only act on a whole-file reformat (Ctrl+Alt+L with no selection); never on a partial range.
        if (rangeToReformat.startOffset != 0 || rangeToReformat.endOffset != doc.textLength) return rangeToReformat
        val old = doc.text
        val new = Lean4Format.normalize(old)
        if (new == old) return rangeToReformat
        doc.setText(new)
        PsiDocumentManager.getInstance(source.project).commitDocument(doc)
        return TextRange(0, new.length)
    }
}
