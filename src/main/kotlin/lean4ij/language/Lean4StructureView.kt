package lean4ij.language

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

/** Shows the file's top-level named declarations (def/theorem/structure/...) in the Structure tool window. */
class Lean4StructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
        object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                Lean4StructureViewModel(psiFile)
        }
}

class Lean4StructureViewModel(psiFile: PsiFile) :
    StructureViewModelBase(psiFile, Lean4StructureViewElement(psiFile as NavigatablePsiElement)),
    StructureViewModel.ElementInfoProvider {
    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        (element as? Lean4StructureViewElement)?.value !is Lean4PsiFile
}

class Lean4StructureViewElement(private val element: NavigatablePsiElement) :
    StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element
    override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)
    override fun canNavigate(): Boolean = element.canNavigate()
    override fun canNavigateToSource(): Boolean = element.canNavigateToSource()
    override fun getAlphaSortKey(): String = label()
    override fun getPresentation(): ItemPresentation = PresentationData(label(), null, icon(), null)

    override fun getChildren(): Array<TreeElement> {
        val file = element as? Lean4PsiFile ?: return TreeElement.EMPTY_ARRAY
        return PsiTreeUtil.findChildrenOfType(file, Lean4Command::class.java)
            .filter { Lean4PsiUtil.declName(it) != null }
            .map { Lean4StructureViewElement(it as NavigatablePsiElement) }
            .toTypedArray()
    }

    private fun label(): String = when (val e = element) {
        is Lean4Command -> Lean4PsiUtil.declName(e) ?: Lean4PsiUtil.keywordText(e) ?: "command"
        is PsiFile -> e.name
        else -> e.text
    }

    private fun icon(): Icon? = when (val e = element) {
        is Lean4Command -> iconForKeyword(Lean4PsiUtil.keywordText(e))
        else -> e.getIcon(0)
    }

    private fun iconForKeyword(kw: String?): Icon = when (kw) {
        "structure", "inductive", "class" -> AllIcons.Nodes.Class
        "instance" -> AllIcons.Nodes.Interface
        "theorem", "lemma", "example" -> AllIcons.Nodes.Constant
        "def", "abbrev" -> AllIcons.Nodes.Method
        else -> AllIcons.Nodes.Property
    }
}
