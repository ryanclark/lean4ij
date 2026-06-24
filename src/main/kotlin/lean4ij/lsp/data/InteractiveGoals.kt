package lean4ij.lsp.data

import com.intellij.openapi.editor.FoldRegion
import lean4ij.infoview.dsl.*

// TODO clean startOffset and endOffset and isAllMessages and placeholderText fields from it
data class FoldingData(val startOffset: Int, val endOffset: Int, val placeholderText: String, val expanded: Boolean=true,
                       // this is to denote that the folding is for "All Messages"
                       // TODO it's very adhoc and blur define it here, maybe some better way to do this
                       // TODO cannot be removed... it's used for avoiding wrongly collapsed
                       val isAllMessages: Boolean=false,
                       val listener: ((FoldRegion)->Unit)?=null
    )

/**
 * see [src/Lean/Widget/InteractiveGoal.lean#L106-L105](https://github.com/leanprover/lean4/blob/23e49eb519a45496a9740aeb311bf633a459a61e/src/Lean/Widget/InteractiveGoal.lean#L106-L105)
 */
class InteractiveGoals(
    val goals : List<InteractiveGoal>) {

    fun toInfoObjectModel(): InfoObjectModel = info {
        fold {
            h2("Tactic state")
            if (goals.isEmpty()) {
                +"No goals"
                return@fold
            }
            if (goals.size == 1) {
                +"1 goal"
            } else {
                +"${goals.size} goals"
            }
            br()
            for ((index, goal) in goals.withIndex()) {
                add(goal.toInfoObjectModel())
                if (index != goals.lastIndex) {
                    br()
                }
            }
        }
    }

    /**
     * TODO add unittest for this and the above
     * TODO this should be DRY with [lean4ij.lsp.data.InteractiveTermGoal.getCodeText]
     */
    fun getCodeText(offset : Int) : Triple<ContextInfo, Int, Int>? {
        for (goal in goals) {
            for (hyp in goal.hyps) {
                val type = hyp.type
                if (type.startOffset <= offset && offset < type.endOffset) {
                    return type.getCodeText(offset, null)
                }
            }
            if (goal.getStartOffset() <= offset && offset < goal.getEndOffset()) {
                return goal.getCodeText(offset)
            }
        }
        return null
    }
}
