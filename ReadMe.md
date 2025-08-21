# BracketColorizer — ビルドとインストール手順

このドキュメントは、プラグインのビルド方法と各 IDE へのインストール手順をまとめたものです。

## 前提条件
- JDK: 21
- ビルド: 付属の Gradle Wrapper を使用（ローカルに Gradle 不要）
- インターネット接続（依存取得用）
- 対応 IDE: IntelliJ プラットフォーム系（Rider など）

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
