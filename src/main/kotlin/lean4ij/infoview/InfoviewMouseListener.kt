package lean4ij.infoview

import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener

class InfoviewMouseListener(private val context: LeanInfoviewContext) : EditorMouseListener {

    override fun mouseClicked(event: EditorMouseEvent) {
        context.rootObjectModel.getChild(event.offset)?.click(context)
    }
}
