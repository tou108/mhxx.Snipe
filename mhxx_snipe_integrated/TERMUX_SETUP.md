# MHXX スナイプツール統合版 - Termux セットアップガイド

このドキュメントは、Android 上の Termux でプロジェクトをセットアップしGitHub にアップロードする方法を説明します。

## 事前準備

### 1. Termux のインストール
Google Play Store から「Termux」をインストールしてください。
https://play.google.com/store/apps/details?id=com.termux

### 2. 必要なパッケージのインストール

Termux を開いて以下のコマンドを実行:

```bash
# パッケージマネージャーを更新
pkg update -y

# 必要なツールをインストール
pkg install -y git curl wget openssh openjdk-17

# ストレージアクセス権限を許可
termux-setup-storage
```

### 3. GitHub Personal Token の取得

1. ブラウザで以下 URL にアクセス:
   https://github.com/settings/tokens

2. 「Generate new token (classic)」をクリック

3. トークン設定:
   - Token name: `MHXX Snipe Upload`
   - Expiration: 90 days (推奨)
   - Scopes: `repo` にチェック

4. 「Generate token」をクリック

5. 生成されたトークンをコピー（ページを離れると表示されません）

## アップロード手順

### ステップ 1: プロジェクトをTermux にコピー

**方法A: adb を使用（PCから）**
```bash
# PC から Termux へコピー
adb push /path/to/mhxx_snipe_integrated /storage/emulated/0/Documents/

# Termux 内で移動
cd /storage/emulated/0/Documents/mhxx_snipe_integrated
```

**方法B: SCP を使用**
```bash
# PC からSSH経由でコピー（Termux SSHサーバー起動時）
scp -r /path/to/mhxx_snipe_integrated user@device:~/
```

**方法C: 手動コピー**
1. PC でプロジェクトを ZIP で圧縮
2. Android ファイルマネージャーで Termux ストレージへ移動
3. ZIP を解凍

### ステップ 2: Git の初期設定

```bash
# ホームディレクトリに移動
cd $HOME

# Git ユーザー設定
git config --global user.name "Your Name"
git config --global user.email "your.email@example.com"

# Credentialヘルパーを設定
git config --global credential.helper store
```

### ステップ 3: GitHub にプッシュ

**方法1: 自動スクリプトを使用（推奨）**

```bash
# プロジェクトディレクトリに移動
cd ~/mhxx_snipe_integrated

# スクリプトに実行権限を付与
chmod +x upload_to_github.sh

# スクリプト実行（Personal Token を指定）
bash upload_to_github.sh ghp_YOUR_PERSONAL_TOKEN_HERE
```

**方法2: 手動でコマンド実行**

```bash
cd ~/mhxx_snipe_integrated

# リモートリポジトリの設定
git remote add origin https://github.com/tou108/mhxx.Snipe.git

# ファイルをステージング
git add -A

# コミット
git commit -m "feat: JoyConDroid統合 + Arduino自動化機能を実装"

# プッシュ（プロンプトで GitHub の認証情報を入力）
git push -u origin main
```

## トラブルシューティング

### 問題: "Permission denied" エラー

**原因**: スクリプトに実行権限がない

**解決策**:
```bash
chmod +x upload_to_github.sh
bash upload_to_github.sh <token>
```

### 問題: "git: command not found"

**原因**: Git がインストールされていない

**解決策**:
```bash
pkg install -y git
```

### 問題: "Authentication failed"

**原因**: Personal Token が無効または期限切れ

**解決策**:
1. GitHub で新しい Personal Token を生成
2. トークンをコピー
3. スクリプトを再実行

### 問題: "fatal: could not read Username for github.com"

**原因**: Git 認証情報が設定されていない

**解決策**:
```bash
# 認証情報を保存
git config --global credential.helper store

# プッシュ時に認証情報を入力
# ユーザー名: <GitHub ユーザー名>
# パスワード: <Personal Token>
```

### 問題: ネットワークエラー

**原因**: Termux からのネットワーク接続が遮断されている

**解決策**:
```bash
# ネットワーク接続を確認
ping google.com

# VPN を使用している場合は設定を確認
# WiFi 接続を確認して再接続
```

## コマンド リファレンス

### 基本的な Git コマンド

```bash
# 現在の変更を確認
git status

# ファイルをステージング
git add .               # すべてのファイル
git add path/to/file   # 特定ファイルのみ

# コミット
git commit -m "メッセージ"

# リモートにプッシュ
git push origin main

# ログを確認
git log --oneline

# 最後のコミットを修正
git commit --amend

# 変更を破棄（注意！）
git restore <file>
```

### GitHub との連携

```bash
# リモートリポジトリの確認
git remote -v

# リモートリポジトリの設定
git remote add origin <URL>
git remote set-url origin <NEW_URL>

# 最新の変更を取得
git pull origin main

# ブランチを作成
git checkout -b feature/new-feature
```

## アップロード後の確認

### 1. GitHub で確認
```bash
https://github.com/tou108/mhxx.Snipe
```

### 2. Termux で確認
```bash
# ログを確認
git log --oneline

# 直近のコミットを詳細表示
git show HEAD
```

### 3. 更新を確認

アップロード後、GitHub リポジトリを見て以下を確認:

- ✓ コミットが表示されている
- ✓ ファイルが正しく追加されている
- ✓ HTML / Kotlin ファイルが含まれている

## 継続的な開発

### ローカル変更を更新する場合

```bash
# 編集 (Android Studio または Termux エディタ)
nano app/src/main/java/com/mhxx/Snipe/MainActivity.kt

# 変更をコミット
git add .
git commit -m "fix: バグ修正"

# プッシュ
git push origin main
```

### 新しいブランチで開発する場合

```bash
# 新規ブランチを作成
git checkout -b feature/new-feature

# 編集・コミット
git add .
git commit -m "feat: 新機能を追加"

# リモートに新しいブランチをプッシュ
git push -u origin feature/new-feature

# GitHub で Pull Request を作成
```

## セキュリティに関する注意

⚠️ **重要**: 以下の情報を絶対に公開しないでください:

- Personal Token（GitHub）
- SSH秘密鍵
- API キー
- パスワード

### Personal Token を安全に使用する

```bash
# トークンをファイルに保存しない
# コマンドラインに直接記述しない

# 代わりに環境変数を使用
export GITHUB_TOKEN=ghp_YOUR_TOKEN
bash upload_to_github.sh $GITHUB_TOKEN

# セッション終了後、環境変数をクリア
unset GITHUB_TOKEN
```

## 高度な操作

### コミット履歴をきれいにする

```bash
# 最後の3つのコミットをインタラクティブにリベース
git rebase -i HEAD~3

# または特定のコミットまで
git rebase -i <commit_hash>
```

### マージ

```bash
# 別ブランチをマージ
git checkout main
git pull origin main
git merge feature/new-feature

# プッシュ
git push origin main
```

## サポートとトラブル対応

### ログを確認する

```bash
# Git コマンドの詳細ログを有効化
export GIT_TRACE=1
export GIT_CURL_VERBOSE=1

# その後、コマンドを実行
git push origin main
```

### Termux のリソースを確認

```bash
# ストレージ使用量
df -h

# メモリ使用量
free -h

# プロセス確認
ps aux
```

## その他のリソース

- **Termux 公式ドキュメント**: https://termux.com/
- **Git 公式ドキュメント**: https://git-scm.com/doc
- **GitHub ドキュメント**: https://docs.github.com/

## よくある質問（FAQ）

**Q: Termux でビルドできますか？**
A: はい、`pkg install -y gradle` でビルドツールをインストール可能です。ただし、低スペック環境では推奨されません。

**Q: 複数デバイスから同じリポジトリにアップロードできますか？**
A: はい、ただしコンフリクト解決が必要な場合があります。

**Q: Personal Token を忘れた場合は？**
A: GitHub で古いトークンを無効化して、新しいトークンを生成してください。

---

**最終更新**: 2024年6月4日
**対応 Termux バージョン**: 0.118+
**対応 Android バージョン**: 8.0+
