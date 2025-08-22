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
// ... existing code ...
            val openToClose = mapOf('(' to ')', '{' to '}', '[' to ']', '<' to '>')
            val closeToOpen = openToClose.entries.associate { it.value to it.key }
            val stack = ArrayDeque<Char>()
            val colorIndexStack = ArrayDeque<Int>()

            // ここから不足していたヘルパー関数を追加
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
                    // スタックに '<' がある場合は、演算子 '>>' の2つ目でも閉じ扱い
                    if (stack.isNotEmpty() && stack.last() == '<') {
                        true
                    } else {
                        !isOperatorAngle(absIdx, '>') && stack.isNotEmpty() && stack.last() == '<'
                    }
                }
                ')', '}', ']' -> stack.isNotEmpty() && openToClose[stack.last()] == ch
                else -> false
            }
            // ここまで追記
            lexer.start(text)
            // C/C++/C# の #if 0 / #if false 無効領域を検出（Lexer 経路）
            val inactiveRanges = computeInactivePreprocessorRanges(text, psiFile.language.id)
            var inactiveIdx = 0
            fun tokenIsInactive(start: Int, end: Int): Boolean {
                while (inactiveIdx < inactiveRanges.size && inactiveRanges[inactiveIdx].last < start) {
                    inactiveIdx++
                }
                if (inactiveIdx >= inactiveRanges.size) return false
                val r = inactiveRanges[inactiveIdx]
                // [start, end) と [r.first, r.last+1) の交差で判定
                return start < (r.last + 1) && end > r.first
            }
            while (lexer.tokenType != null) {
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                val tokenText = text.substring(start, end)
                val isCommentOrString = isCommentOrStringToken(highlighter, lexer.tokenType!!)
                val isInactive = tokenIsInactive(start, end)
                if (!isCommentOrString && !isInactive) {
                    for (i in tokenText.indices) {
                        val ch = tokenText[i]
                        val abs = start + i
                        if (ch in openToClose.keys && shouldTreatAsOpen(ch, abs)) {
                            val levelIdx = stack.size % BracketColorSettings.LEVEL_COUNT
                            stack.addLast(ch)
                            colorIndexStack.addLast(levelIdx)
                            if (enabledFor(ch)) addRange(abs, abs + 1, levelIdx)
                        } else if (ch in closeToOpen.keys) {
                            if (ch == '>' && !shouldTreatAsClose(ch, abs)) {
                                // skip
                            } else {
                                val desiredOpen = closeToOpen[ch]
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
                                if (enabledFor(ch)) addRange(abs, abs + 1, levelIdxForClose!!)
                            }
                        }
                    }
                }
                lexer.advance()
            }
            true
        }

        if (!parsedOk) {
            // フォールバック（PSI/レクサ未準備や Dumb モードなど）
            // C/C++/C# の無効プリプロセッサ領域を検出し、オフセットを除外
            val inactiveRanges = computeInactivePreprocessorRanges(text, null)
            var rIdx = 0
            fun isInactiveOffset(off: Int): Boolean {
                while (rIdx < inactiveRanges.size && inactiveRanges[rIdx].last < off) rIdx++
                if (rIdx >= inactiveRanges.size) return false
                val r = inactiveRanges[rIdx]
                return off >= r.first && off <= r.last
            }
            simpleScan(text) { offset, levelIdx ->
                if (!isInactiveOffset(offset)) {
                    val ch = text[offset]
                    if (enabledFor(ch)) addRange(offset, offset + 1, levelIdx)
                }
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

    /**
     * C/C++/C# 向けの簡易プリプロセッサ解析。
     * - #if 0 / #if false（および #elif 0 / #elif false）で明確に無効となる領域のみを抽出します。
     * - #else / #endif による切り替え・終了に対応。ネストも対応。
     * - 未知の条件式は評価せず（未知）として扱い、除外しません（安全側）。
     * - languageId が C/C++/C# 以外の場合は空を返します。
     *
     * 返値は [start, end]（両端含む）オフセットの昇順リスト。
     */
    private fun computeInactivePreprocessorRanges(text: String, languageId: String?): List<IntRange> {
        fun isCLike(langId: String?): Boolean {
            if (langId == null) return true // フォールバック時はヒューリスティックに許可
            val id = langId.lowercase()
            return id.contains("c#") || id.contains("csharp") || id == "c" || id.contains("cpp") || id.contains("c++") || id.contains("objective")
        }
        if (!isCLike(languageId)) return emptyList()

        val ranges = ArrayList<IntRange>()
        var inactiveDepth = 0
        var inactiveStart = -1
        var idx = 0
        val len = text.length

        fun skipSpacesFrom(i: Int): Int {
            var j = i
            while (j < len && (text[j] == ' ' || text[j] == '\t')) j++
            return j
        }
        fun lineEndFrom(i: Int): Int {
            var j = i
            while (j < len && text[j] != '\n' && text[j] != '\r') j++
            return j
        }
        fun nextLineStartFrom(i: Int): Int {
            var j = i
            while (j < len && text[j] != '\n' && text[j] != '\r') j++
            if (j < len && text[j] == '\r' && j + 1 < len && text[j + 1] == '\n') j++
            if (j < len) j++
            return j
        }
        fun parseDirectiveAt(lineStart: Int): Pair<String, String>? {
            var j = skipSpacesFrom(lineStart)
            if (j >= len || text[j] != '#') return null
            j++
            j = skipSpacesFrom(j)
            val kwStart = j
            while (j < len && text[j].isLetter()) j++
            if (j == kwStart) return null
            val keyword = text.substring(kwStart, j).lowercase()
            val restStart = skipSpacesFrom(j)
            val lineEnd = lineEndFrom(restStart)
            val arg = text.substring(restStart, lineEnd).trim()
            return keyword to arg
        }
        fun evalFalse(arg: String): Boolean {
            val a = arg.lowercase()
            return a == "0" || a == "false"
        }
        fun evalTrue(arg: String): Boolean {
            val a = arg.lowercase()
            return a == "1" || a == "true"
        }

        while (idx < len) {
            val lineStart = idx
            val directive = parseDirectiveAt(lineStart)
            if (directive == null) {
                idx = nextLineStartFrom(idx)
                continue
            }
            val (kw, arg) = directive
            val afterLine = nextLineStartFrom(lineStart)
            when (kw) {
                "if" -> {
                    val condFalse = evalFalse(arg)
                    if (inactiveDepth > 0) {
                        // すでに無効領域内：ネスト深さのみ増やす
                        inactiveDepth++
                    } else if (condFalse) {
                        inactiveDepth = 1
                        // 無効領域はディレクティブ行の後から開始
                        inactiveStart = afterLine
                    }
                    // cond が true/未知 の場合は何もしない（安全側）
                }
                "elif" -> {
                    if (inactiveDepth == 1) {
                        // トップレベルの無効ブロック中でのみ切替を行う
                        val condTrue = evalTrue(arg)
                        if (condTrue) {
                            // ここで有効化：直前までを無効として確定
                            val end = lineStart - 1
                            if (inactiveStart >= 0 && end >= inactiveStart) {
                                ranges.add(inactiveStart..end)
                            }
                            inactiveDepth = 0
                            inactiveStart = -1
                        } else {
                            // 引き続き無効（未知も無効のまま維持）
                        }
                    } else if (inactiveDepth > 1) {
                        // ネスト内の elif は無視（外側が無効のため引き続き無効）
                    } else {
                        // 有効領域での elif は安全側で無視（ここで無効化はしない）
                    }
                }
                "else" -> {
                    if (inactiveDepth == 1) {
                        // それまでの分岐がすべて false だったため、else で有効化
                        val end = lineStart - 1
                        if (inactiveStart >= 0 && end >= inactiveStart) {
                            ranges.add(inactiveStart..end)
                        }
                        inactiveDepth = 0
                        inactiveStart = -1
                    } else if (inactiveDepth > 1) {
                        // ネスト内：引き続き無効
                    } else {
                        // 有効側の else は（#if true の else 無効など）安全側で無視
                    }
                }
                "endif" -> {
                    if (inactiveDepth > 0) {
                        inactiveDepth--
                        if (inactiveDepth == 0) {
                            // ブロック終端で無効領域を確定
                            val end = lineStart - 1
                            if (inactiveStart >= 0 && end >= inactiveStart) {
                                ranges.add(inactiveStart..end)
                            }
                            inactiveStart = -1
                        }
                    } else {
                        // 不一致は無視
                    }
                }
                else -> {
                    // その他ディレクティブは無視
                }
            }
            idx = afterLine
        }
        return ranges
    }
}
