# BracketColorizer — プロジェクト概要・ビルドとインストール手順

このドキュメントは、プラグインの概要、ビルド方法、各 IDE へのインストール手順をまとめたものです。

## このプラグインについて
BracketColorizer は、JetBrains IntelliJ プラットフォーム系 IDE 向けのエディタ支援プラグインです。ソースコード内の括弧をネストの深さに応じて色分けし、対応関係を視覚的に把握しやすくします。
- 対応括弧: (), [], {}, <>（< > は文脈に応じて簡易判定）
- 仕組み: 言語プラグインが提供する SyntaxHighlighter を利用できる場合はコメント/文字列などを除外し、それ以外はテキスト走査で色付けします（言語非依存）。
- カスタマイズ: 色のセットを設定から調整可能。括弧タイプごと（(), [], {}, <>）の色付け有効/無効も設定できます。ネストレベル数は固定（9）。
- 目的: ネストが深いコードでも括弧の対応を素早く追えるようにし、読みやすさと保守性を向上させます。
- 堅牢性: IDE での保存/コミットや外部再読込（例: Git 操作）後でも、自動的に色付けを再適用して表示を維持します。
- 反映: 設定で Apply を押すと、設定ダイアログを開いたままでも即座に色付けへ反映され、ファイルを開き直す必要はありません。

## AI生成に関する告知
本プロジェクト（ソースコードおよびこの ReadMe を含む）は、AI 支援により作成されました。初期作成および一部の更新には、JetBrains の自律型プログラマー「Junie」を用いています。

## 前提条件
- JDK: 21
- ビルド: 付属の Gradle Wrapper を使用（ローカルに Gradle 不要）
- インターネット接続（依存取得用）
- 対応 IDE: IntelliJ プラットフォーム系（Rider など）
- サポート言語: 言語非依存（テキストスキャン）。対応言語のプラグインが有効な場合はレキサ対応します（例: Kotlin/Java/Python/Rust/JavaScript/TypeScript 等）。C#/C/C++ は対応プラグイン/製品（例: Rider/CLion）が必要です。


## 開発実行（サンドボックス）
- サンドボックスで起動して挙動を確認
```shell script
.\gradlew.bat runIde
```


## ビルド
- 配布用 ZIP を生成します
```shell script
.\gradlew.bat clean buildPlugin
```

- 出力物: `build/distributions/<plugin-name>-<version>.zip`

必要に応じて検証:
```shell script
.\gradlew.bat verifyPlugin
```


## インストール方法

### 方法A: ディスクからインストール（推奨）
1. IDE を開く（Rider / IntelliJ など）
2. Settings/Preferences > Plugins
3. 歯車アイコン > Install Plugin from Disk...
4. `build/distributions/` の ZIP を選択
5. 再起動

### 方法B: 手動配置
ZIP のまま、または展開したディレクトリをユーザーの plugins フォルダへ配置し、IDE を再起動。

- Windows: `%APPDATA%\JetBrains\Rider<バージョン>\plugins`
- macOS: `~/Library/Application Support/JetBrains/Rider<バージョン>/plugins`
- Linux: `~/.config/JetBrains/Rider<バージョン>/plugins`

IntelliJ IDEA の場合も同様に各 IDE の plugins フォルダを使用します。

### アンインストール/更新
- Settings/Preferences > Plugins から無効化/アンインストール
- 手動配置した場合は plugins フォルダから該当 ZIP/フォルダを削除
- その後 IDE を再起動

## トラブルシューティング

- 症状: ビルド時に以下で失敗する
    - Searchable options index builder failed / IllegalArgumentException: Locale must be default
- 対応
    - 一時回避（タスクをスキップ）
```shell script
    .\gradlew.bat buildPlugin -x buildSearchableOptions --no-configuration-cache
```

その他:
- キャッシュ影響を避ける場合
```shell script
  .\gradlew.bat --stop
  .\gradlew.bat clean
```

## 言語サポートの仕組み

- 個別対応は必要ですか？
  - いいえ。本プラグインは言語非依存の実装です。各ファイルの言語に対して SyntaxHighlighter（各言語プラグインが提供）を利用できる場合は、それを使ってコメント/文字列を除外しつつ括弧を色付けします。利用できない場合は、フォールバックとしてテキストの単純走査で色付けします。

- 精度の違いは？
  - 言語プラグインあり: コメント/文字列・ドキュメント領域を除外し、< > は演算子との簡易判定を行うため、より自然な見た目になります。
  - 言語プラグインなし: テキスト走査のみのため、コメント/文字列内の括弧も色付けされる場合があります。

- 必要な前提は？
  - 通常はご利用の IDE にインストール済みの言語プラグインに依存します。開発サンドボックスの検証目的でのみ Kotlin プラグインを Gradle で同梱しています。

## 既知の制限（プリプロセッサ無効領域の判定）

- C/C++/C# の #if/#ifdef/#ifndef/#elif/#else/#endif を簡易的に解釈しています。
- 対応しているのは次のような単純な条件のみです: `#define`/`#undef` によるシンボルの有無、`defined(NAME)`/`defined NAME`、リテラル `0`/`1`/`true`/`false`、および単純な `SYMBOL` / `!SYMBOL`。`#ifdef NAME` は `#if defined(NAME)`、`#ifndef NAME` は `#if !defined(NAME)` として扱います。
- 複雑な条件式（数値演算・ビット演算・比較・マクロ展開など）は評価しません。そのため、実際には無効なブロック内の括弧が色付けされてしまう場合があります。
- 設計上、不明な条件は有効扱い（フェイルセーフ）としています。誤って有効なコードの色付けを消さないための仕様です。

---

# BracketColorizer — Project Overview, Build and Installation Guide (English)

This document provides an overview of the plugin, how to build it, and instructions for installing it into JetBrains IDEs.

## About this plugin
BracketColorizer is an editor-assistance plugin for JetBrains IntelliJ Platform IDEs. It colorizes brackets in source code based on nesting depth to make matching pairs easier to visually track.
- Supported brackets: (), [], {}, <> (angle brackets are heuristically distinguished depending on context)
- How it works: When a language plugin provides a SyntaxHighlighter, the plugin excludes comments/strings and colors brackets accordingly; otherwise it falls back to simple text scanning (language-agnostic).
- Customization: You can adjust the color set in settings. You can also enable/disable coloring per bracket type ((), [], {}, <>). The number of nesting levels is fixed (9).
- Purpose: Help you quickly follow bracket pairs even in deeply nested code, improving readability and maintainability.
- Robustness: Coloring is preserved across IDE saves/commits and external file reloads (e.g., Git operations) via automatic re-application.
- Live apply: Pressing Apply in Settings updates coloring immediately even while the Settings dialog is open; reopening files is not necessary.

## Notice about AI generation
This project (including source code and this ReadMe) was created with AI assistance. For the initial creation and some updates, we used JetBrains' autonomous programmer "Junie." Content has been reviewed and edited by a human as needed.

## Prerequisites
- JDK: 21
- Build: Use the bundled Gradle Wrapper (no local Gradle required)
- Internet connection (to fetch dependencies)
- Supported IDEs: IntelliJ Platform family (e.g., Rider, IntelliJ IDEA)
- Supported languages: Language-agnostic by default (text scan). If the corresponding language plugin is enabled, token-aware coloring is used (e.g., Kotlin/Java/Python/Rust/JavaScript/TypeScript, etc.). C#/C/C++ require the appropriate product/plugin (e.g., Rider/CLion).

## Run in development (sandbox)
- Launch IDE in a sandbox to verify behavior
```shell script
.\gradlew.bat runIde
```

## Build
- Produce a distributable ZIP
```shell script
.\gradlew.bat clean buildPlugin
```

- Artifact: `build/distributions/<plugin-name>-<version>.zip`

Optionally verify:
```shell script
.\gradlew.bat verifyPlugin
```

## Installation

### Method A: Install from disk (recommended)
1. Open the IDE (Rider / IntelliJ, etc.)
2. Settings/Preferences > Plugins
3. Gear icon > Install Plugin from Disk...
4. Select the ZIP under `build/distributions/`
5. Restart the IDE

### Method B: Manual placement
Place the ZIP (or the extracted directory) into the user's plugins folder and restart the IDE.
- Windows: `%APPDATA%\JetBrains\Rider<version>\plugins`
- macOS: `~/Library/Application Support/JetBrains/Rider<version>/plugins`
- Linux: `~/.config/JetBrains/Rider<version>/plugins`

For IntelliJ IDEA, use the IDE's corresponding plugins folder similarly.

### Uninstall/Update
- Disable/uninstall from Settings/Preferences > Plugins
- If manually placed, remove the ZIP/folder from the plugins directory
- Restart the IDE afterward

## Troubleshooting

- Symptom: Build fails with
  - Searchable options index builder failed / IllegalArgumentException: Locale must be default
- Workaround: Temporarily skip the task
```shell script
.\gradlew.bat buildPlugin -x buildSearchableOptions --no-configuration-cache
```

Other tips:
- To avoid cache effects
```shell script
.\gradlew.bat --stop
.\gradlew.bat clean
```

## How language support works

- Do I need per-language support?  
  - No. The plugin is language-agnostic. If a SyntaxHighlighter is available for a file's language (provided by its language plugin), it is used to exclude comments/strings while coloring brackets. If not available, the plugin falls back to simple text scanning.

- Any difference in accuracy?
  - With a language plugin: Comments/strings/doc areas are excluded, and angle brackets are heuristically distinguished from operators, resulting in a more natural look.
  - Without a language plugin: Only text scanning is used, so brackets in comments/strings may also be colored.

- Any prerequisites?
  - Normally it depends on the language plugins already installed in your IDE. For development sandbox verification only, the Kotlin plugin is bundled via Gradle.

## Known limitations (inactive preprocessor regions)

- C/C++/C# preprocessor conditionals (#if/#elif/#else/#endif) are interpreted using a simple heuristic.
- Supported cases include: tracking of `#define`/`#undef`, `defined(NAME)`/`defined NAME`, literals `0`/`1`/`true`/`false`, and simple `SYMBOL` / `!SYMBOL` checks.
- Complex expressions (arithmetic/bitwise/comparison operations), macro expansions, and similar constructs are not evaluated. As a result, blocks that are actually inactive may still get colored.
- By design, unknown conditions are treated as active (fail-safe) to avoid accidentally removing coloring from valid code.

