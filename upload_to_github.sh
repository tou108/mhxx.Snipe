#!/bin/bash
# MHXX スナイプツール統合版 - Termux アップロードスクリプト
# 使用方法: bash upload_to_github.sh <GitHub_Personal_Token>

set -e

# 色分け出力用
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 設定
REPO_URL="https://github.com/tou108/mhxx.Snipe"
PROJECT_DIR="mhxx_snipe_integrated"
COMMIT_MESSAGE="feat: JoyConDroid統合 + Arduino自動化機能を実装"

# 関数定義
print_header() {
    echo -e "${BLUE}================================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}================================================${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

# Termux環境チェック
check_termux_env() {
    print_header "Termux 環境チェック"
    
    # 必要なコマンドをチェック
    local missing_tools=()
    
    for tool in git curl wget; do
        if ! command -v $tool &> /dev/null; then
            missing_tools+=("$tool")
        else
            print_success "$tool がインストール済み"
        fi
    done
    
    if [ ${#missing_tools[@]} -gt 0 ]; then
        print_error "以下のツールがインストールされていません: ${missing_tools[*]}"
        print_info "以下を実行してインストールしてください:"
        echo "pkg install -y git curl wget"
        return 1
    fi
    
    return 0
}

# GitHub認証設定
setup_github_auth() {
    print_header "GitHub 認証設定"
    
    if [ -z "$1" ]; then
        print_error "GitHub Personal Token が指定されていません"
        echo ""
        echo "使用方法:"
        echo "  bash upload_to_github.sh <Personal_Token>"
        echo ""
        echo "Personal Token の取得方法:"
        echo "  1. https://github.com/settings/tokens にアクセス"
        echo "  2. 'Generate new token (classic)' をクリック"
        echo "  3. スコープで 'repo' にチェック"
        echo "  4. 生成されたトークンをコピー"
        echo ""
        return 1
    fi
    
    local token="$1"
    
    # Git認証設定
    git config --global user.name "Termux User" 2>/dev/null || true
    git config --global user.email "termux@localhost" 2>/dev/null || true
    
    # Credentialヘルパーを使用（Termux対応）
    git config --global credential.helper store 2>/dev/null || true
    
    # HTTPS URLを認証付きに変更
    REPO_URL="https://oauth2:${token}@github.com/tou108/mhxx.Snipe.git"
    
    print_success "GitHub認証を設定しました"
    return 0
}

# ファイル準備
prepare_files() {
    print_header "ファイル準備"
    
    if [ ! -d "$PROJECT_DIR" ]; then
        print_error "ディレクトリ $PROJECT_DIR が見つかりません"
        print_info "プロジェクトディレクトリを確認してください"
        return 1
    fi
    
    cd "$PROJECT_DIR"
    print_success "プロジェクトディレクトリに移動: $(pwd)"
    
    # Git リポジトリ初期化（未初期化の場合）
    if [ ! -d ".git" ]; then
        print_info "Git リポジトリを初期化します"
        git init
        git remote add origin "$REPO_URL" || print_info "リモート origin は既に存在します"
    else
        print_success "Git リポジトリが存在します"
        # リモートURLを更新
        git remote set-url origin "$REPO_URL" || git remote add origin "$REPO_URL"
    fi
    
    return 0
}

# ファイルの状態を確認
check_git_status() {
    print_header "Git ステータス確認"
    
    git status --short | head -20 || true
    echo ""
    
    local uncommitted=$(git status --porcelain | wc -l)
    print_info "変更ファイル数: $uncommitted"
    
    return 0
}

# 追加・コミット・プッシュ
upload_to_github() {
    print_header "GitHub へのアップロード"
    
    # すべてのファイルをステージング
    print_info "ファイルをステージング中..."
    git add -A
    print_success "ステージング完了"
    
    # 差分を確認
    print_info "追加される変更を確認:"
    git diff --cached --stat || true
    
    # コミット
    print_info "コミット中..."
    if git commit -m "$COMMIT_MESSAGE" 2>/dev/null; then
        print_success "コミット完了"
    else
        print_error "コミット失敗 - 変更がないか、その他のエラーが発生しました"
        git status
        return 1
    fi
    
    # プッシュ
    print_info "GitHub へプッシュ中..."
    if git push -u origin main 2>/dev/null || git push -u origin master 2>/dev/null; then
        print_success "プッシュ完了"
    else
        print_error "プッシュ失敗 - 権限またはネットワーク接続を確認してください"
        git log --oneline -5
        return 1
    fi
    
    return 0
}

# ログ出力
show_summary() {
    print_header "アップロード完了"
    
    echo ""
    echo -e "${GREEN}✓ 統合プロジェクトが GitHub にアップロードされました${NC}"
    echo ""
    echo "リポジトリURL:"
    echo "  https://github.com/tou108/mhxx.Snipe"
    echo ""
    echo "含まれる機能:"
    echo "  • ML Kit による日本語 OCR 認識"
    echo "  • Nintendo Switch Bluetooth HID 接続"
    echo "  • Arduino 自動化スクリプト実行"
    echo ""
    echo "次のステップ:"
    echo "  1. Android Studio でプロジェクトを開く"
    echo "  2. './gradlew build' でビルド"
    echo "  3. APK を生成して Android デバイスにインストール"
    echo ""
}

# メイン処理
main() {
    echo ""
    print_header "MHXX スナイプツール統合版 - GitHub アップロードスクリプト"
    echo ""
    
    # 環境チェック
    if ! check_termux_env; then
        print_error "環境チェック失敗"
        return 1
    fi
    
    # 認証設定
    if ! setup_github_auth "$1"; then
        return 1
    fi
    
    # ファイル準備
    if ! prepare_files; then
        return 1
    fi
    
    # Git ステータス確認
    check_git_status
    
    # 確認プロンプト
    echo ""
    read -p "GitHub にアップロードしてもいいですか？ (y/N): " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        print_info "アップロードがキャンセルされました"
        return 0
    fi
    
    # アップロード実行
    if ! upload_to_github; then
        return 1
    fi
    
    # 結果表示
    show_summary
    
    return 0
}

# スクリプト実行
main "$@"
exit $?
