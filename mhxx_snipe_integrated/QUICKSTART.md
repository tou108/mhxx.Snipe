# MHXX スナイプツール統合版 - クイックスタートガイド

JoyConDroid + Arduino自動化機能を統合したバージョンです。

## 🚀 30秒でわかる概要

このプロジェクトは以下の3つの機能を統合しています:

| 機能 | 説明 | 必要なもの |
|-----|------|---------|
| **OCR認識** | ゲーム画面から自動でステータスを読み込み | カメラ |
| **Switch接続** | AndroidからNintendo Switchを遠隔操作 | Switch + Bluetooth |
| **自動化スクリプト** | PythonやShellスクリプトを実行・管理 | Termux (任意) |

## ⚡ Termux ワンライナー実行コマンド

### 方法1: 完全自動セットアップ（推奨）

```bash
# Termux を開いて以下をコピペ実行
pkg update -y && pkg install -y git && \
git clone https://github.com/tou108/mhxx.Snipe.git ~/mhxx_snipe && \
cd ~/mhxx_snipe && \
chmod +x upload_to_github.sh && \
echo "✓ プロジェクトのセットアップが完了しました"
```

### 方法2: 既にプロジェクトがある場合（GitHub Personal Token必須）

```bash
# Personal Token: ghp_xxxxxxxxxxxx

cd ~/mhxx_snipe && \
git config --global user.name "Termux User" && \
git config --global user.email "termux@localhost" && \
git config --global credential.helper store && \
bash upload_to_github.sh ghp_YOUR_TOKEN_HERE
```

### 方法3: ステップバイステップ（手動）

```bash
# 1. 必要なツールをインストール
pkg update -y && pkg install -y git curl

# 2. リポジトリをクローン
git clone https://github.com/tou108/mhxx.Snipe.git ~/mhxx_snipe

# 3. プロジェクトディレクトリに移動
cd ~/mhxx_snipe

# 4. Git を設定
git config --global user.name "Your Name"
git config --global user.email "your@email.com"

# 5. 変更をプッシュ（初回のみ）
git add -A
git commit -m "Initial commit from Termux"
git push origin main
```

## 📋 事前準備チェックリスト

- [ ] Termux がインストール済み
- [ ] GitHub アカウントを持っている
- [ ] GitHub Personal Token を取得した
- [ ] Android 8.0 以上を使用している
- [ ] Bluetooth が有効になっている（Switch接続用）

## 🎯 主な使用シーン

### シーン1: PCからプロジェクトをTermuxにコピーしてアップロード

```bash
# PC 側で実行
adb push ~/projects/mhxx_snipe_integrated /sdcard/

# Termux 側で実行
cd /sdcard/mhxx_snipe_integrated
git init
git remote add origin https://github.com/tou108/mhxx.Snipe.git
bash upload_to_github.sh ghp_YOUR_TOKEN
```

### シーン2: Termux内でプロジェクトをビルド

```bash
cd ~/mhxx_snipe
chmod +x gradlew
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/*.apk
```

### シーン3: 継続的に変更をプッシュ

```bash
cd ~/mhxx_snipe

# 編集・テスト
# ... 何か編集 ...

# プッシュ
git add -A
git commit -m "fix: バグを修正"
git push origin main
```

## 🔧 トラブル対応

### Termux コマンドが見つからない

```bash
# パッケージをインストール
pkg install -y git curl wget

# または特定のツール
pkg search git         # 利用可能なパッケージを検索
pkg install git        # インストール
```

### ストレージアクセス権限エラー

```bash
# Termux ストレージアクセスを許可
termux-setup-storage

# その後、ストレージにアクセス
cd /storage/emulated/0/
```

### Git 認証エラー

```bash
# 認証情報を確認
git config --list

# 認証情報をリセット
git config --global --unset credential.helper
git config --global credential.helper store

# 新しく認証（プロンプトで Token を入力）
git push origin main
```

### メモリ不足エラー

```bash
# メモリ使用量を確認
free -h

# 不要なプロセスを終了
pkg list-installed       # インストール済みパッケージを確認
pkg uninstall <package>  # 不要なパッケージを削除
```

## 📱 アプリのビルド・インストール

### Android Studio で開く場合

1. **プロジェクトを開く**
   ```
   File → Open → mhxx_snipe_integrated を選択
   ```

2. **ビルド**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```

3. **インストール**
   ```
   Run → Run 'app'
   ```

### コマンドラインでビルド

```bash
cd ~/mhxx_snipe

# Debug APK をビルド
./gradlew assembleDebug

# インストール（デバイス接続時）
adb install app/build/outputs/apk/debug/*.apk

# リリース版をビルド（署名設定が必要）
./gradlew assembleRelease
```

## 🌐 GitHub への自動アップロード

### スクリプトを使用（推奨）

```bash
cd ~/mhxx_snipe

# Personal Token で直接実行
bash upload_to_github.sh ghp_xxxxxxxxxxxx

# または環境変数で指定
export GITHUB_TOKEN=ghp_xxxxxxxxxxxx
bash upload_to_github.sh $GITHUB_TOKEN
```

### 手動でアップロード

```bash
cd ~/mhxx_snipe

# ファイルをステージング
git add -A

# コミット
git commit -m "Update: 機能を追加"

# プッシュ
git push origin main
```

## 📝 ファイル構造

```
mhxx_snipe_integrated/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/mhxx/Snipe/
│   │   │   │   ├── MainActivity.kt              ← メインUI
│   │   │   │   ├── BluetoothHIDController.kt    ← Switch接続
│   │   │   │   └── ArduinoAutomationManager.kt  ← 自動化
│   │   │   ├── assets/
│   │   │   │   └── snipe_integrated.html        ← WebView UI
│   │   │   └── AndroidManifest.xml
│   │   └── ...
│   └── build.gradle
├── INTEGRATION_GUIDE.md                         ← 詳細ドキュメント
├── TERMUX_SETUP.md                              ← Termux ガイド
├── upload_to_github.sh                          ← アップロードスクリプト
└── README.md                                     ← このファイル
```

## 🔐 Personal Token の安全な取得

1. **GitHub にアクセス**
   https://github.com/settings/tokens

2. **「Generate new token (classic)」をクリック**

3. **トークン設定**
   - Token name: `MHXX Snipe Upload`
   - Expiration: `90 days` (推奨)
   - Scopes: `repo` にチェック ✓

4. **「Generate token」をクリック**

5. **トークンをコピー** (ページを離れると表示されません)

6. **Termux で使用**
   ```bash
   bash upload_to_github.sh ghp_YOUR_COPIED_TOKEN
   ```

## ✨ 主な機能

### 1️⃣ OCR認識タブ
- ゲーム画像からテキストを自動抽出
- Google ML Kit による日本語対応
- 認識結果をコピー可能

### 2️⃣ Switch接続タブ
- Bluetooth HID でNintendo Switchに接続
- MAC アドレスで自動識別
- リアルタイムコントローラー入力

### 3️⃣ Arduino自動化タブ
- Python / Shell スクリプトの管理
- プログラムのインポート・実行・停止・削除
- リアルタイム出力表示

## 🚨 よくある質問

**Q: Termux でビルドできますか？**
```bash
pkg install -y openjdk-17 gradle
cd ~/mhxx_snipe
gradle build
```

**Q: 複数デバイスから同じリポジトリを編集できますか？**
```bash
# はい、ただしコンフリクトに注意してください
git pull origin main   # 最新を取得
# 編集
git push origin main   # プッシュ
```

**Q: Termux を再インストールしたら？**
```bash
pkg install -y git
cd ~/mhxx_snipe
git pull origin main   # リポジトリを再取得
```

## 📚 関連ドキュメント

- **INTEGRATION_GUIDE.md** - 詳細な実装ガイド
- **TERMUX_SETUP.md** - Termux セットアップ手順
- **JoyConDroid** - https://github.com/rdapps/JoyConDroid

## 💡 カスタマイズ

各機能は独立しているため、カスタマイズが容易です:

- `MainActivity.kt` - UI ロジック
- `BluetoothHIDController.kt` - Bluetooth 実装
- `ArduinoAutomationManager.kt` - スクリプト実行
- `snipe_integrated.html` - UI デザイン

## 🎓 開発者向け

このプロジェクトはオープンソースです。改善提案やバグ報告は GitHub Issues で受け付けています。

```bash
# フォークしてカスタマイズ
git clone https://github.com/YOUR_USERNAME/mhxx.Snipe.git
cd mhxx.Snipe
# ... 編集 ...
git push origin main
# GitHub で Pull Request を作成
```

## 📄 ライセンス

このプロジェクトは複数のコンポーネントで構成されています:

- **JoyConDroid** - GNU General Public License v3.0
- **Google ML Kit** - Apache License 2.0
- **このプロジェクト** - GPL v3.0

## 📞 サポート

問題が発生した場合:

1. **ログを確認**
   ```bash
   adb logcat | grep MhxxSnipe
   ```

2. **既知の問題を確認**
   https://github.com/tou108/mhxx.Snipe/issues

3. **新しい Issue を作成**
   https://github.com/tou108/mhxx.Snipe/issues/new

---

**最後の更新**: 2024年6月4日
**対応 Termux**: 0.118+
**対応 Android**: 8.0+
**対応 Java**: OpenJDK 11+

**Happy Coding! 🎮**
