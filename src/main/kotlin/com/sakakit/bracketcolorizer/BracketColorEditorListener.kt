package com.sakakit.bracketcolorizer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.tree.IElementType
import java.util.concurrent.ConcurrentHashMap

/**
 * エディタの生成/破棄およびドキュメント変更をフックし、
 * ブラケットへのレンジハイライトを更新するリスナー。
 *
 * Annotator を使わない手動ハイライト版。SyntaxHighlighter を用いて
 * コメント/文字列/ドキュメントを除外し、ネストレベルに応じた色を適用します。
 */
class BracketColorEditorListener : EditorFactoryListener, DumbAware {
    private val highlightersMap = ConcurrentHashMap<Document, MutableList<RangeHighlighter>>()
    private val listenersMap = ConcurrentHashMap<Document, MutableList<DocumentListener>>()

    /**
     * エディタが生成されたとき、ドキュメント変更リスナーを取り付けて初期ハイライトを行います。
     */
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

    /**
     * エディタが閉じられたとき、ハイライトとリスナーをクリーンアップします。
     */
    override fun editorReleased(event: EditorFactoryEvent) {
        val document = event.editor.document
        clearHighlights(document)
        listenersMap.remove(document)?.forEach { document.removeDocumentListener(it) }
    }

    /**
     * 指定ドキュメントに対して現在適用されているレンジハイライトをすべて除去します。
     * @param document 対象ドキュメント
     */
    private fun clearHighlights(document: Document) {
        highlightersMap.remove(document)?.forEach { it.dispose() }
    }

    /**
     * ドキュメント全体を再解析してブラケットのレンジハイライトを張り直します。
     * SyntaxHighlighter/Lexer が利用可能な場合はそれを用い、なければテキストを単純走査します。
     * @param project 対象プロジェクト
     * @param document 対象ドキュメント
     */
    private fun updateHighlights(project: Project, document: Document) {
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
            // Use TextAttributesKey so that changes in the color scheme are reflected immediately
            val rh = markup.addRangeHighlighter(
                key,
                startOffset,
                endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                HighlighterTargetArea.EXACT_RANGE
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

    /**
     * トークンがコメント/文字列/ドキュメントかどうかを判定します。
     * @param highlighter 対象言語の SyntaxHighlighter
     * @param tokenType 判定するトークンタイプ
     * @return コメント・文字列・ドキュメントなら true
     */
    private fun isCommentOrStringToken(highlighter: SyntaxHighlighter, tokenType: IElementType): Boolean {
        val keys = highlighter.getTokenHighlights(tokenType)
        return keys.any { key ->
            val name = key.externalName
            name.contains("COMMENT", true) || name.contains("STRING", true) || name.contains("DOC", true)
        }
    }

    /**
     * 簡易スキャナでテキスト全体を走査し、ブラケット検出時にコールバックします（Lexer 不使用）。
     * - 角括弧は演算子との簡易判定を行います。
     * @param text 対象テキスト
     * @param onBracket ブラケット検出時に呼ばれるコールバック（オフセットとレベル）
     */
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
