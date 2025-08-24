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
                // projectがnullでも色付けを適用する
                inst.updateHighlights(editor.project, editor.document)
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
     * 取り付けた DocumentListener をエディタ単位で保持します。
     * editorReleased で該当エディタのリスナーのみを確実に取り外すために使用します。
     */
    private val listenersMap = ConcurrentHashMap<com.intellij.openapi.editor.Editor, DocumentListener>()

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
        val project = editor.project // nullでもOK
        val document = editor.document
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                ApplicationManager.getApplication().invokeLater({
                    updateHighlights(project, document)
                }, { project?.isDisposed == true })
            }
        }
        // attach and remember; we'll remove when editor is released
        document.addDocumentListener(listener)
        listenersMap[editor] = listener
        // initial paint
        updateHighlights(project, document)
    }

    /**
     * エディタが閉じられたとき、ハイライトとリスナーをクリーンアップします。
     * @param event エディタ解放イベント
     */
    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val document = editor.document
        // このエディタに紐づけたリスナーだけを外す
        listenersMap.remove(editor)?.let { document.removeDocumentListener(it) }
        // 他のエディタが残っているかで挙動を分ける
        ApplicationManager.getApplication().invokeLater {
            val remaining = EditorFactory.getInstance().getEditors(document)
            if (remaining.isEmpty()) {
                // 最後のエディタが閉じられた場合のみ、全ハイライトをクリーンアップ
                clearHighlights(document)
            } else {
                // 残っているエディタのために再ハイライト（project=null で全エディタ対象）
                updateHighlights(null, document)
            }
        }
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
     * @param project 対象プロジェクト。null の場合は該当 Document を開いている「すべてのエディタ」を対象にします。
     * @param document 対象ドキュメント
     */
    internal fun updateHighlights(project: Project?, document: Document) {
        clearHighlights(document)
        // 該当documentの全Editor（projectの有無問わず）に色付け
        val editors = if (project != null) {
            EditorFactory.getInstance().getEditors(document, project)
        } else {
            EditorFactory.getInstance().getEditors(document)
        }
        if (editors.isEmpty()) return
        val text = document.text
        val settings = BracketColorSettings.getInstance()
        fun enabledFor(ch: Char): Boolean = when (ch) {
            '(', ')' -> settings.isRoundEnabled()
            '{', '}' -> settings.isCurlyEnabled()
            '[', ']' -> settings.isSquareEnabled()
            '<', '>' -> settings.isAngleEnabled()
            else -> true
        }
        
        // 全エディタ分の新しいハイライターを一括で保持する
        val combinedNewList = mutableListOf<RangeHighlighter>()
        for (editor in editors) {
            val markup = editor.markupModel
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
            val parsedOk = if (project != null) ApplicationManager.getApplication().runReadAction<Boolean> {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@runReadAction false
                val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(psiFile.language, project, psiFile.virtualFile)
                val lexer = highlighter?.highlightingLexer ?: return@runReadAction false
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
            } else false

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
            // このエディタで作成したハイライターを合算
            combinedNewList.addAll(newList)
        }
        // 旧ハイライトを破棄済みなので、新規をまとめて登録
        highlightersMap[document] = combinedNewList
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
     * - #if 0 / #if false の無効ブロックに加え、#define SYMBOL と #if SYMBOL（および #elif SYMBOL）を簡易評価します。
     * - #ifdef NAME / #ifndef NAME にも対応します（それぞれ #if defined(NAME)、#if !defined(NAME) と等価）。
     * - これにより、#if / #ifdef / #ifndef が真の場合の #else ブロックも「無効」として正しく検出します。
     * - ネスト、#elif、#else、#endif に対応。未知の複雑な条件式は安全側（未知）として評価し、除外しません。
     * - languageId が C/C++/C# 以外の場合は空を返します。
     *
     * @param text 解析対象のテキスト全体
     * @param languageId 言語 ID（C/C++/C# 以外は無視）
     * @return 無効領域を表す [start, end]（両端含む）オフセットの昇順リスト
     */
    private fun computeInactivePreprocessorRanges(text: String, languageId: String?): List<IntRange> {
        fun isCLike(langId: String?): Boolean {
            if (langId == null) return true // フォールバック時はヒューリスティックに許可
            val id = langId.lowercase()
            return id.contains("c#") || id.contains("csharp") || id == "c" || id.contains("cpp") || id.contains("c++") || id.contains("objective")
        }
        if (!isCLike(languageId)) return emptyList()

        data class Frame(var active: Boolean, var known: Boolean, var takenTrue: Boolean)

        val ranges = ArrayList<IntRange>()
        val frames = ArrayDeque<Frame>()
        var inactiveLayers = 0
        var inactiveStart = -1
        var idx = 0
        val len = text.length
        val defined = HashSet<String>()

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
        fun evalDefinedLike(s: String): Boolean? {
            val a = s.trim()
            // literals
            when (a.lowercase()) {
                "0", "false" -> return false
                "1", "true" -> return true
            }
            // defined(NAME) or defined NAME
            val lower = a.lowercase()
            if (lower.startsWith("defined")) {
                var rest = a.substring(7).trim()
                if (rest.startsWith("(")) {
                    rest = rest.substring(1).trim()
                    val end = rest.indexOf(')')
                    if (end >= 0) rest = rest.take(end)
                }
                val name = rest.takeWhile { it.isLetterOrDigit() || it == '_' }
                if (name.isNotEmpty()) return defined.contains(name)
                return null
            }
            // simple SYMBOL or !SYMBOL
            var neg = false
            var i = 0
            if (a.isNotEmpty() && a[i] == '!') { neg = true; i++ }
            val name = a.substring(i).takeWhile { it.isLetterOrDigit() || it == '_' }
            if (name.isNotEmpty()) {
                val v = defined.contains(name)
                return if (neg) !v else v
            }
            return null
        }
        fun startInactive(at: Int) {
            if (inactiveLayers == 0) inactiveStart = at
            inactiveLayers++
        }
        fun endInactive(uptoEndExclusiveLineStart: Int) {
            if (inactiveLayers == 1) {
                val end = uptoEndExclusiveLineStart - 1
                if (inactiveStart in 0..end) ranges.add(inactiveStart..end)
            }
            if (inactiveLayers > 0) inactiveLayers--
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
                "define" -> {
                    // #define SYMBOL
                    val name = arg.takeWhile { it.isLetterOrDigit() || it == '_' }
                    if (name.isNotEmpty()) defined.add(name)
                }
                "undef" -> {
                    val name = arg.takeWhile { it.isLetterOrDigit() || it == '_' }
                    if (name.isNotEmpty()) defined.remove(name)
                }
                "if" -> {
                    val cond = evalDefinedLike(arg)
                    val active = cond != false
                    val known = cond != null
                    val taken = cond == true
                    frames.addLast(Frame(active, known, taken))
                    if (!active) startInactive(afterLine)
                }
                "ifdef" -> {
                    // #ifdef NAME  ==  #if defined(NAME)
                    val name = arg.takeWhile { it.isLetterOrDigit() || it == '_' }
                    val cond = if (name.isNotEmpty()) defined.contains(name) else null
                    val active = cond != false
                    val known = cond != null
                    val taken = cond == true
                    frames.addLast(Frame(active, known, taken))
                    if (!active) startInactive(afterLine)
                }
                "ifndef" -> {
                    // #ifndef NAME  ==  #if !defined(NAME)
                    val name = arg.takeWhile { it.isLetterOrDigit() || it == '_' }
                    val cond = if (name.isNotEmpty()) !defined.contains(name) else null
                    val active = cond != false
                    val known = cond != null
                    val taken = cond == true
                    frames.addLast(Frame(active, known, taken))
                    if (!active) startInactive(afterLine)
                }
                "elif" -> {
                    if (frames.isEmpty()) {
                        // 不一致は無視
                    } else {
                        val fr = frames.last()
                        if (!fr.known) {
                            // 不明条件のブロックでは安全側で変化させない
                        } else if (fr.takenTrue) {
                            // 既に真の分岐が取られているので、この elif は無効
                            if (fr.active) {
                                fr.active = false
                                startInactive(afterLine)
                            }
                        } else {
                            val cond = evalDefinedLike(arg)
                            when (cond) {
                                true -> {
                                    if (!fr.active) endInactive(lineStart)
                                    fr.active = true
                                    fr.takenTrue = true
                                    fr.known = true
                                }
                                false -> {
                                    if (fr.active) {
                                        fr.active = false
                                        startInactive(afterLine)
                                    }
                                    fr.known = true
                                }
                                null -> {
                                    // 未知: 安全側でアクティブ扱い
                                    if (!fr.active) endInactive(lineStart)
                                    fr.active = true
                                    fr.known = false
                                }
                            }
                        }
                    }
                }
                "else" -> {
                    if (frames.isEmpty()) {
                        // 不一致は無視
                    } else {
                        val fr = frames.last()
                        if (!fr.known) {
                            // 未知条件が含まれる場合は安全側でアクティブ扱いのまま
                        } else if (fr.takenTrue) {
                            // 既に真の分岐が取られている -> else は無効
                            if (fr.active) {
                                fr.active = false
                                startInactive(afterLine)
                            }
                        } else {
                            // まだ真が取られていない -> else は有効
                            if (!fr.active) endInactive(lineStart)
                            fr.active = true
                            fr.takenTrue = true
                            fr.known = true
                        }
                    }
                }
                "endif" -> {
                    if (frames.isNotEmpty()) {
                        val fr = frames.removeLast()
                        if (fr.known && !fr.active) {
                            // 無効中で閉じる
                            endInactive(lineStart)
                        }
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
