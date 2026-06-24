package lean4ij.util

import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object LeanUtil {

    fun isLeanFile(file: VirtualFile) : Boolean {
        return file.extension?.let { it == "lean" || it == "lean4"} == true
    }

    fun isLeanFile(url: String) : Boolean {
        return url.endsWith(".lean") || url.endsWith(".lean4")
    }

    /**
     * True when [start]/[end] form a usable highlighter range over a document of [textLength].
     * `StringUtil.lineColToOffset` returns -1 for an out-of-bounds line/col (e.g. a hover range that is stale
     * for the currently selected editor's document), and `MarkupModel.addRangeHighlighter(-1, -1, ...)` throws
     * `IllegalArgumentException` ("Incorrect offsets"). Callers must skip the highlight when this returns false.
     */
    fun isValidRange(start: Int, end: Int, textLength: Int): Boolean {
        return start in 0..end && end <= textLength
    }

}