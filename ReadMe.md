# BracketColorizer — ビルドとインストール手順

このドキュメントは、プラグインのビルド方法と各 IDE へのインストール手順をまとめたものです。

## 前提条件
- JDK: 21
- ビルド: 付属の Gradle Wrapper を使用（ローカルに Gradle 不要）
- インターネット接続（依存取得用）
- 対応 IDE: IntelliJ プラットフォーム系（Rider など）
- サポート言語: 言語非依存（テキストスキャン）＋ 各言語プラグインが有効な場合はレキサ対応（Kotlin/Java/C#/C/C++/Python/Rust/JavaScript/TypeScript など）

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

## 署名・配布
- ローカルや社内配布の「Install from Disk」は署名不要
- JetBrains Marketplace へ公開する場合は署名・メタデータが必要（別途設定）

## 開発メモ
- コードスタイルや設定を共有したい場合は .editorconfig を活用
- 機密情報はリポジトリに含めない（必要なら `gradle.properties.example` を用意）
- CI では Gradle Wrapper を使用し、キャッシュやロケール固定を設定すると安定します


## 言語サポートの仕組み（FAQ）

- 個別対応は必要ですか？
  - いいえ。本プラグインは言語非依存の実装です。各ファイルの言語に対して SyntaxHighlighter（各言語プラグインが提供）を利用できる場合は、それを使ってコメント/文字列を除外しつつ括弧を色付けします。利用できない場合は、フォールバックとしてテキストの単純走査で色付けします。

- Kotlin だけ個別対応が必要ですか？
  - いいえ。Kotlinに限らず、各言語プラグインが有効なら高精度（コメント/文字列除外）になります。今回の変更では、開発用サンドボックスで Kotlin ファイルを確実に試せるよう Gradle 側で Kotlin プラグインを同梱しているだけで、機能実装としての個別対応は行っていません。

- もともとの要件（C#/C/C++/Java/Python/Rust/JavaScript/TypeScript）は個別対応不要ですか？
  - 不要です。各 IDE/言語プラグインが SyntaxHighlighter を提供している環境（例: Rider で C#、CLion で C/C++、IntelliJ IDEA で Java/JS/TS、PyCharm または Python プラグインで Python、Rust プラグインで Rust など）では、そのレキサを自動で利用します。提供がない環境でもフォールバックのテキスト走査で動作します（この場合はコメント/文字列内の括弧も色付けされることがあります）。

- 精度の違いは？
  - 言語プラグインあり: コメント/文字列・ドキュメント領域を除外し、< > は演算子との簡易判定を行うため、より自然な見た目になります。
  - 言語プラグインなし: テキスト走査のみのため、コメント/文字列内の括弧も色付けされる場合があります。

- 必要な前提は？
  - 通常はご利用の IDE にインストール済みの言語プラグインに依存します。開発サンドボックスの検証目的でのみ Kotlin プラグインを Gradle で同梱しています。
