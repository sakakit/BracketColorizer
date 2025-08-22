package com.sakakit.bracketcolorizer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.tree.IElementType
import java.util.concurrent.ConcurrentHashMap

/**
 * 外部からハイライト更新を要求するためのユーティリティ。
 * 拡張実装の companion にはロガー/定数のみ許可されるため、
 * メソッドやミュータブル状態はこちらのオブジェクトで保持します。
 */
object BracketColorRefresher {
    @Volatile
    /**
     * 現在アクティブな BracketColorEditorListener の参照。
     * 設定適用など外部から再ハイライトを要求する際に使用します。
     * 複数プロジェクト/エディタ環境を考慮し、null の可能性があります。
     */
    var instance: BracketColorEditorListener? = null

    /** 設定変更適用時などに、開いている全エディタのハイライトを更新します。 */
    @JvmStatic
    fun refreshAllOpenEditors() {
        val inst = instance ?: return
        val runnable = Runnable {
            EditorFactory.getInstance().allEditors.forEach { editor ->
                val project = editor.project ?: return@forEach
                inst.updateHighlights(project, editor.document)
            }
        }
        // Ensure it runs even while a modal dialog (Settings) is open
        ApplicationManager.getApplication()
            .invokeLater(runnable, com.intellij.openapi.application.ModalityState.any())
    }
}

/**
 * エディタの生成/破棄およびドキュメント変更をフックし、
 * ブラケットへのレンジハイライトを更新するリスナー。
 *
 * Git 操作などによる外部再読込で色付けが消える問題に対処します。
 * また、保存/コミット直前のイベントでも自動再適用して色付けの消失を防ぎます。
 *
 * Annotator を使わない手動ハイライト版。SyntaxHighlighter を用いて
 * コメント/文字列/ドキュメントを除外し、ネストレベルに応じた色を適用します。
 * 設定の括弧タイプ有効フラグ（(), [], {}, <>）を尊重して色付け対象を切り替えます。
 */
class BracketColorEditorListener : EditorFactoryListener, DumbAware {
    /**
     * ドキュメントごとに適用中の RangeHighlighter 一覧を保持します。
     * エディタのクローズや再読込時に確実に dispose するために参照します。
     */
    private val highlightersMap = ConcurrentHashMap<Document, MutableList<RangeHighlighter>>()
    /**
     * 取り付けた DocumentListener をドキュメント単位で保持します。
     * editorReleased でリスナーを確実に取り外すために使用します。
     */
    private val listenersMap = ConcurrentHashMap<Document, MutableList<DocumentListener>>()

    init {
            BracketColorRefresher.instance = this
        // Git 操作などによる外部再読込で色付けが消える問題への対処
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun fileContentReloaded(file: VirtualFile, document: Document) {
                    ApplicationManager.getApplication().invokeLater {
                        // 開いている全エディタに対して再ハイライト
                        EditorFactory.getInstance().getEditors(document).forEach { editor ->
                            val project = editor.project ?: return@forEach
                            updateHighlights(project, document)
                        }
                    }
                }

                override fun beforeDocumentSaving(document: Document) {
                    // 保存直前にハイライトがクリアされる場合に備えて再適用を予約
                    ApplicationManager.getApplication().invokeLater {
                        EditorFactory.getInstance().getEditors(document).forEach { editor ->
                            val project = editor.project ?: return@forEach
                            updateHighlights(project, document)
                        }
                    }
                }

                override fun beforeAllDocumentsSaving() {
                    // すべての保存直前：コミットに伴う保存でも発火
                    ApplicationManager.getApplication().invokeLater {
                        // すべての開いているエディタに対して再適用
                        EditorFactory.getInstance().allEditors.forEach { editor ->
                            val project = editor.project ?: return@forEach
                            updateHighlights(project, editor.document)
                        }
                    }
                }
            })
    }

    /**
     * エディタが生成されたとき、ドキュメント変更リスナーを取り付けて初期ハイライトを行います。
     * @param event エディタ生成イベント
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
     * @param event エディタ解放イベント
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
    internal fun updateHighlights(project: Project, document: Document) {
        clearHighlights(document)
        val editor = EditorFactory.getInstance().getEditors(document, project).firstOrNull() ?: return
        val markup = editor.markupModel
        val text = document.text
        val settings = BracketColorSettings.getInstance()
        fun enabledFor(ch: Char): Boolean = when (ch) {
            '(', ')' -> settings.isRoundEnabled()
            '{', '}' -> settings.isCurlyEnabled()
            '[', ']' -> settings.isSquareEnabled()
            '<', '>' -> settings.isAngleEnabled()
            else -> true
        }

        // 新しいハイライトを先に構築し、最後に一括で差し替える
        val newList = mutableListOf<RangeHighlighter>()

        fun addRange(startOffset: Int, endOffset: Int, levelIdx: Int) {
            val key = BracketKeys.LEVEL_KEYS[levelIdx]
            // 既存の弱警告などより上のレイヤで描画して灰色上書きを回避
            val rh = markup.addRangeHighlighter(
                key,
                startOffset,
                endOffset,
                HighlighterLayer.SELECTION - 1,  // 以前: HighlighterLayer.ADDITIONAL_SYNTAX
                HighlighterTargetArea.EXACT_RANGE
            )
            newList.add(rh)
        }

        // PSI/レクサ取得は ReadAction 内で行い、未準備時は simpleScan にフォールバック
        val parsedOk = ApplicationManager.getApplication().runReadAction<Boolean> {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@runReadAction false
            val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(psiFile.language, project, psiFile.virtualFile)
            val lexer = highlighter?.highlightingLexer ?: return@runReadAction false

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
                // 直前直後の非空白を参照して < を型引数開始とみなすか判定
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
                '>' -> {
                    // スタックに '<' が積まれている（ジェネリクスの入れ子を処理中）の場合は
                    // 連続する '>>' であっても演算子扱いせずに閉じ括弧として扱う
                    if (stack.isNotEmpty() && stack.last() == '<') {
                        true
                    } else {
                        !isOperatorAngle(absIdx, '>') && stack.isNotEmpty() && stack.last() == '<'
                    }
                }
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
                            if (enabledFor(ch)) addRange(abs, abs + 1, levelIdx)
                        } else if (ch in closeToOpen.keys) {
                            // 閉じ括弧は不一致でもできる限り色を付ける（スタック修復を試みる）
                            if (ch == '>' && !shouldTreatAsClose(ch, abs)) {
                                // '>' が演算子と判断されたらスキップ
                                // ただし generic 由来でない '>' はここに来ない想定
                            } else {
                                val desiredOpen = closeToOpen[ch]
                                var levelIdxForClose: Int? = null
                                // スタックから desiredOpen を探しつつポップ
                                if (stack.isNotEmpty()) {
                                    if (stack.last() == desiredOpen) {
                                        levelIdxForClose = colorIndexStack.removeLast()
                                        stack.removeLast()
                                    } else {
                                        // 不一致：上から遡って一致を探す（壊れたスタックの修復）
                                        var found = false
                                        while (stack.isNotEmpty()) {
                                            val poppedOpen = stack.removeLast()
                                            val poppedLevel = colorIndexStack.removeLast()
                                            if (poppedOpen == desiredOpen) {
                                                levelIdxForClose = poppedLevel
                                                found = true
                                                break
                                            }
                                        }
                                        if (!found) {
                                            // 一致が見つからない場合でも最低限の色を付ける
                                            levelIdxForClose = 0
                                        }
                                    }
                                } else {
                                    // スタックが空：単独の閉じ括弧にも色を付ける
                                    levelIdxForClose = 0
                                }
                                if (enabledFor(ch)) addRange(abs, abs + 1, levelIdxForClose!!)
                            }
                        } else {
                            // skip non-bracket; do nothing
                        }
                    }
                }
                lexer.advance()
            }
            true
        }

        if (!parsedOk) {
            // フォールバック（PSI/レクサ未準備や Dumb モードなど）
            simpleScan(text) { offset, levelIdx ->
                val ch = text[offset]
                if (enabledFor(ch)) addRange(offset, offset + 1, levelIdx)
            }
        }

        // ここで旧ハイライトを破棄してから新リストを登録（消えっぱなしを防止）
        clearHighlights(document)
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
                // スタック上に '<' があれば、'>>' の2つ目であってもジェネリクスの閉じとして扱う
                if (stack.isNotEmpty() && stack.last() == '<') {
                    var levelIdxForClose: Int? = null
                    while (stack.isNotEmpty()) {
                        val poppedOpen = stack.removeLast()
                        val poppedLevel = colorIndexStack.removeLast()
                        if (poppedOpen == '<') {
                            levelIdxForClose = poppedLevel
                            break
                        }
                    }
                    onBracket(i, levelIdxForClose ?: 0)
                } else if (!isOperatorAngle(i, '>')) {
                    // '<' が積まれていない場合のみ、演算子判定に従って閉じ扱い
                    onBracket(i, 0)
                }
            } else if (ch == '(' || ch == '{' || ch == '[') {
                val levelIdx = stack.size % BracketColorSettings.LEVEL_COUNT
                stack.addLast(ch)
                colorIndexStack.addLast(levelIdx)
                onBracket(i, levelIdx)
            } else if (ch == ')' || ch == '}' || ch == ']') {
                // 丸/波/角括弧の閉じ側も不一致修復して必ず色付け
                val desiredOpen = when (ch) {
                    ')' -> '('
                    '}' -> '{'
                    ']' -> '['
                    else -> null
                }
                if (desiredOpen != null) {
                    var levelIdxForClose: Int? = null
                    if (stack.isNotEmpty()) {
                        if (stack.last() == desiredOpen) {
                            levelIdxForClose = colorIndexStack.removeLast()
                            stack.removeLast()
                        } else {
                            var found = false
                            while (stack.isNotEmpty()) {
                                val poppedOpen = stack.removeLast()
                                val poppedLevel = colorIndexStack.removeLast()
                                if (poppedOpen == desiredOpen) {
                                    levelIdxForClose = poppedLevel
                                    found = true
                                    break
                                }
                            }
                            if (!found) levelIdxForClose = 0
                        }
                    } else {
                        levelIdxForClose = 0
                    }
                    onBracket(i, levelIdxForClose!!)
                }
            }
        }
    }
}
