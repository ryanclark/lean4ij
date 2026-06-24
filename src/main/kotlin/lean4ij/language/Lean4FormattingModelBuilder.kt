package lean4ij.language

import com.intellij.formatting.Block
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.psi.formatter.common.AbstractBlock

/**
 * Minimal formatting model so Reformat Code is enabled for Lean files. It intentionally formats nothing
 * structurally (a single leaf block at no indent): Lean keeps WHITE_SPACE as a real PSI token for hover, which
 * the block-indent engine can't manage. The actual conservative normalization (import sort, blank-line
 * collapse, trailing-whitespace trim) runs in [Lean4ImportSortPostFormatProcessor].
 */
class Lean4FormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val root: Block = object : AbstractBlock(file.node, null, null) {
            override fun buildChildren(): MutableList<Block> = mutableListOf()
            override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
            override fun isLeaf(): Boolean = true
            override fun getIndent(): Indent = Indent.getNoneIndent()
        }
        return FormattingModelProvider.createFormattingModelForPsiFile(
            file, root, formattingContext.codeStyleSettings
        )
    }
}
