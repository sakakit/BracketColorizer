package com.sakakit.brackets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import java.util.concurrent.ConcurrentHashMap

class BracketColorEditorListener : EditorFactoryListener, DumbAware {
    private val highlightersMap = ConcurrentHashMap<com.intellij.openapi.editor.Document, MutableList<RangeHighlighter>>()
    private val listenersMap = ConcurrentHashMap<com.intellij.openapi.editor.Document, MutableList<DocumentListener>>()

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val document = editor.document
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                ApplicationManager.getApplication().invokeLater({
                    updateHighlights(project, document)
                }, { project.isDisposed })
            }
        }
        // attach and remember; we'll remove when editor is released
        document.addDocumentListener(listener)
        listenersMap.computeIfAbsent(document) { mutableListOf() }.add(listener)
        // initial paint
        updateHighlights(project, document)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val document = event.editor.document
        clearHighlights(document)
        listenersMap.remove(document)?.forEach { document.removeDocumentListener(it) }
    }

    private fun clearHighlights(document: com.intellij.openapi.editor.Document) {
        highlightersMap.remove(document)?.forEach { it.dispose() }
    }

    private fun updateHighlights(project: Project, document: com.intellij.openapi.editor.Document) {
        clearHighlights(document)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        val text = document.text
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(psiFile.language, project, psiFile.virtualFile)
        val lexer = highlighter?.highlightingLexer
        val editor = EditorFactory.getInstance().getEditors(document, project).firstOrNull() ?: return
        val markup = editor.markupModel

        val newList = mutableListOf<RangeHighlighter>()
        fun addRange(startOffset: Int, endOffset: Int, levelIdx: Int) {
            val key = BracketKeys.LEVEL_KEYS[levelIdx]
            val scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
            var attrs = scheme.getAttributes(key)
            if (attrs == null || attrs.foregroundColor == null) {
                // Fallback to settings-derived color to ensure visibility even if scheme hasn't applied yet
                val color = BracketColorSettings.getInstance().getColors()[levelIdx]
                attrs = com.intellij.openapi.editor.markup.TextAttributes(color, null, null, null, 0)
            }
            val rh = markup.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                attrs,
                com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
            )
            newList.add(rh)
        }

        if (lexer == null) {
            simpleScan(text) { offset, levelIdx -> addRange(offset, offset + 1, levelIdx) }
        } else {
            val openToClose = mapOf('(' to ')', '{' to '}', '[' to ']', '<' to '>')
            val closeToOpen = openToClose.entries.associate { it.value to it.key }
            val stack = ArrayDeque<Char>()
            val colorIndexStack = ArrayDeque<Int>()

            fun isOperatorAngle(textIdx: Int, ch: Char): Boolean {
                val prev = if (textIdx > 0) text[textIdx - 1] else '\u0000'
                val next = if (textIdx + 1 < text.length) text[textIdx + 1] else '\u0000'
                return when (ch) {
                    '<' -> (next == '=' || next == '<') || (prev == '<' || prev == '=')
                    '>' -> (prev == '-' || prev == '=' || prev == '>') || (next == '=' || next == '>')
                    else -> false
                }
            }

            fun isProbableGenericOpen(textIdx: Int): Boolean {
                if (isOperatorAngle(textIdx, '<')) return false
                // Check neighbors ignoring spaces
                fun prevNonSpace(i: Int): Char? { var j=i-1; while (j>=0 && text[j].isWhitespace()) j--; return if (j>=0) text[j] else null }
                fun nextNonSpace(i: Int): Char? { var j=i+1; while (j<text.length && text[j].isWhitespace()) j++; return if (j<text.length) text[j] else null }
                val p = prevNonSpace(textIdx)
                val n = nextNonSpace(textIdx)
                val isPrevTypeish = p != null && (p.isLetterOrDigit() || p == '_' || p == ')' || p == ']' || p == '>')
                val isNextTypeish = n != null && (n.isLetterOrDigit() || n == '_' || n == '?' || n == '(')
                return isNextTypeish && (isPrevTypeish || p == null)
            }

            fun shouldTreatAsOpen(ch: Char, absIdx: Int): Boolean {
                return when (ch) {
                    '<' -> isProbableGenericOpen(absIdx)
                    '(', '{', '[' -> true
                    else -> false
                }
            }

            fun shouldTreatAsClose(ch: Char, absIdx: Int): Boolean {
                return when (ch) {
                    '>' -> !isOperatorAngle(absIdx, '>') && stack.isNotEmpty() && stack.last() == '<'
                    ')', '}', ']' -> stack.isNotEmpty() && openToClose[stack.last()] == ch
                    else -> false
                }
            }

            lexer.start(text)
            while (lexer.tokenType != null) {
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                val tokenText = text.substring(start, end)
                val isCommentOrString = isCommentOrStringToken(highlighter, lexer.tokenType!!)
                if (!isCommentOrString) {
                    for (i in tokenText.indices) {
                        val ch = tokenText[i]
                        val abs = start + i
                        if (ch in openToClose.keys && shouldTreatAsOpen(ch, abs)) {
                            val levelIdx = stack.size % BracketColorSettings.LEVEL_COUNT
                            stack.addLast(ch)
                            colorIndexStack.addLast(levelIdx)
                            addRange(abs, abs + 1, levelIdx)
                        } else if (ch in closeToOpen.keys && shouldTreatAsClose(ch, abs)) {
                            val levelIdx = colorIndexStack.removeLast()
                            stack.removeLast()
                            addRange(abs, abs + 1, levelIdx)
                        } else {
                            // skip non-bracket or unmatched closing; do nothing
                        }
                    }
                }
                lexer.advance()
            }
        }
        highlightersMap[document] = newList
    }

    private fun isCommentOrStringToken(highlighter: com.intellij.openapi.fileTypes.SyntaxHighlighter, tokenType: com.intellij.psi.tree.IElementType): Boolean {
        val keys = highlighter.getTokenHighlights(tokenType)
        return keys.any { key ->
            val name = key.externalName
            name.contains("COMMENT", true) || name.contains("STRING", true) || name.contains("DOC", true)
        }
    }

    private fun simpleScan(text: String, onBracket: (offset: Int, levelIdx: Int) -> Unit) {
        val openToClose = mapOf('(' to ')', '{' to '}', '[' to ']', '<' to '>')
        val stack = ArrayDeque<Char>()
        val colorIndexStack = ArrayDeque<Int>()
        fun isOperatorAngle(idx: Int, ch: Char): Boolean {
            val prev = if (idx > 0) text[idx - 1] else '\u0000'
            val next = if (idx + 1 < text.length) text[idx + 1] else '\u0000'
            return when (ch) {
                '<' -> (next == '=' || next == '<') || (prev == '<' || prev == '=')
                '>' -> (prev == '-' || prev == '=' || prev == '>') || (next == '=' || next == '>')
                else -> false
            }
        }
        fun isProbableGenericOpen(idx: Int): Boolean {
            if (isOperatorAngle(idx, '<')) return false
            fun prevNonSpace(i: Int): Char? { var j=i-1; while (j>=0 && text[j].isWhitespace()) j--; return if (j>=0) text[j] else null }
            fun nextNonSpace(i: Int): Char? { var j=i+1; while (j<text.length && text[j].isWhitespace()) j++; return if (j<text.length) text[j] else null }
            val p = prevNonSpace(idx)
            val n = nextNonSpace(idx)
            val isPrevTypeish = p != null && (p.isLetterOrDigit() || p == '_' || p == ')' || p == ']' || p == '>')
            val isNextTypeish = n != null && (n.isLetterOrDigit() || n == '_' || n == '?' || n == '(')
            return isNextTypeish && (isPrevTypeish || p == null)
        }
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '<') {
                if (isProbableGenericOpen(i)) {
                    val levelIdx = stack.size % BracketColorSettings.LEVEL_COUNT
                    stack.addLast(ch)
                    colorIndexStack.addLast(levelIdx)
                    onBracket(i, levelIdx)
                }
            } else if (ch == '>') {
                if (!isOperatorAngle(i, '>') && stack.isNotEmpty() && stack.last() == '<') {
                    val levelIdx = colorIndexStack.removeLast()
                    stack.removeLast()
                    onBracket(i, levelIdx)
                }
            } else if (ch == '(' || ch == '{' || ch == '[') {
                val levelIdx = stack.size % BracketColorSettings.LEVEL_COUNT
                stack.addLast(ch)
                colorIndexStack.addLast(levelIdx)
                onBracket(i, levelIdx)
            } else if (ch == ')' || ch == '}' || ch == ']') {
                if (stack.isNotEmpty() && openToClose[stack.last()] == ch) {
                    val levelIdx = colorIndexStack.removeLast()
                    stack.removeLast()
                    onBracket(i, levelIdx)
                }
            }
        }
    }
}
