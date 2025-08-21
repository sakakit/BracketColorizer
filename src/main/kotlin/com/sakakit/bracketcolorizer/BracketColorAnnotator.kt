package com.sakakit.bracketcolorizer

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/**
 * PSI を走査してブラケット文字に色付け用のアノテーションを付与する Annotator。
 *
 * - Lexer/SyntaxHighlighter を利用し、コメント/文字列/ドキュメントは除外します。
 * - 対応の取れるブラケットの開閉をスタックで追跡し、ネストレベルに応じて色を選択します。
 * - 角括弧（< >）は演算子とジェネリクスの区別を簡易的に行います。
 */
class BracketColorAnnotator : Annotator, DumbAware {
    /**
     * ファイル単位で PSI を走査し、ブラケット文字に色属性を与えるアノテーションを作成します。
     * @param element 対象 PSI（ファイル）
     * @param holder アノテーションの発行先
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        val file = element
        val project = file.project
        val text = file.viewProvider.document?.text ?: file.text ?: return
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, project, file.virtualFile)
        val lexer = highlighter?.highlightingLexer
        if (lexer == null) {
            simpleScan(text) { offset, levelIdx -> add(holder, offset, BracketKeys.LEVEL_KEYS[levelIdx]) }
            return
        }
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
            fun prevNonSpace(i: Int): Char? { var j=i-1; while (j>=0 && text[j].isWhitespace()) j--; return if (j>=0) text[j] else null }
            fun nextNonSpace(i: Int): Char? { var j=i+1; while (j<text.length && text[j].isWhitespace()) j++; return if (j<text.length) text[j] else null }
            val p = prevNonSpace(textIdx)
            val n = nextNonSpace(textIdx)
            val isPrevTypeish = p != null && (p.isLetterOrDigit() || p == '_' || p == ')' || p == ']' || p == '>')
            val isNextTypeish = n != null && (n.isLetterOrDigit() || n == '_' || n == '?' || n == '(')
            return isNextTypeish && (isPrevTypeish || p == null)
        }
        fun shouldTreatAsOpen(ch: Char, absIdx: Int): Boolean = when (ch) {
            '<' -> isProbableGenericOpen(absIdx)
            '(', '{', '[' -> true
            else -> false
        }
        fun shouldTreatAsClose(ch: Char, absIdx: Int): Boolean = when (ch) {
            '>' -> !isOperatorAngle(absIdx, '>') && stack.isNotEmpty() && stack.last() == '<'
            ')', '}', ']' -> stack.isNotEmpty() && openToClose[stack.last()] == ch
            else -> false
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
                        add(holder, abs, BracketKeys.LEVEL_KEYS[levelIdx])
                    } else if (ch in closeToOpen.keys && shouldTreatAsClose(ch, abs)) {
                        val levelIdx = colorIndexStack.removeLast()
                        stack.removeLast()
                        add(holder, abs, BracketKeys.LEVEL_KEYS[levelIdx])
                    } else {
                        // skip non-bracket or unmatched closing
                    }
                }
            }
            lexer.advance()
        }
    }

    /**
     * トークンがコメント/文字列/ドキュメントかどうかを判定します。
     * @param highlighter 対象言語の SyntaxHighlighter
     * @param tokenType 判定するトークンタイプ
     * @return コメント・文字列・ドキュメントなら true
     */
    private fun isCommentOrStringToken(highlighter: SyntaxHighlighter, tokenType: IElementType): Boolean {
        val keys: Array<TextAttributesKey> = highlighter.getTokenHighlights(tokenType)
        return keys.any { keyName ->
            val name = keyName.externalName
            name.contains("COMMENT", ignoreCase = true) || name.contains("STRING", ignoreCase = true) || name.contains("DOC", ignoreCase = true)
        }
    }

    /**
     * 指定位置の 1 文字に TextAttributesKey を適用するアノテーションを追加します。
     * @param holder 発行先ホルダー
     * @param offset 対象オフセット
     * @param key 適用する属性キー
     */
    private fun add(holder: AnnotationHolder, offset: Int, key: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(TextRange(offset, offset + 1))
            .textAttributes(key)
            .create()
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
