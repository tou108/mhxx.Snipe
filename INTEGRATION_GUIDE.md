# MHXX お守りスナイプツール - 統合版

JoyConDroid の Bluetooth HID コントローラー機能と Arduino 自動化機能を統合したバージョンです。

## 機能一覧

### 1. OCR認識機能
- Google ML Kit による日本語テキスト認識
- ゲーム画面から直接お守りのステータスを認識
- テキストのコピー機能付き

### 2. Nintendo Switch Bluetooth HID コントローラー
- JoyConDroid の実装を参考に Kotlin で再実装
- MAC アドレスによる Switch 直接接続
- Bluetooth HID プロトコルでの標準操作
- リアルタイムコントローラー入力送信

### 3. Arduino 自動化機能
- Python / Shell スクリプトのインポート・管理・実行
- プログラム出力のリアルタイムコンソール表示
- マルチスレッド対応で複数プログラム同時管理
- JSON 形式のスクリプト定義もサポート

## ファイル構成

```
mhxx_snipe_integrated/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/mhxx/Snipe/
│   │   │   │   ├── MainActivity.kt                    # メインActivity（統合UI）
│   │   │   │   ├── BluetoothHIDController.kt          # Bluetooth HID実装
│   │   │   │   └── ArduinoAutomationManager.kt        # Arduino管理
│   │   │   ├── assets/
│   │   │   │   └── snipe_integrated.html              # UI（WebView）
│   │   │   └── AndroidManifest.xml
│   │   └── ...
│   ├── build.gradle                                   # 依存関係設定
│   └── ...
├── build.gradle
├── settings.gradle
└── README.md
```

## インストール・ビルド

### 前提条件
- Android Studio Dolphin 以上
- Android SDK 26 以上
- Kotlin 1.9.0 以上

### ビルド手順

1. **プロジェクトを開く**
   ```bash
   # AndroidStudioで開くか、コマンドラインから
   ./gradlew build
   ```

2. **APK生成**
   ```bash
   ./gradlew assembleDebug
   # APK: app/build/outputs/apk/debug/mhxx_snipe_integrated.debug.apk
   ```

3. **インストール**
   ```bash
   adb install app/build/outputs/apk/debug/mhxx_snipe_integrated.debug.apk
   ```

## 使用方法

### OCR認識

1. 「OCR認識」タブを選択
2. 「画像をクリックして選択」エリアをタップ
3. デバイスの画像ギャラリーから画像を選択
4. 「OCR認識を実行」をタップ
5. 認識結果がテキストエリアに表示されます

### Switch接続

1. 「Switch接続」タブを選択
2. Switch の MAC アドレスを入力
   - Switch 設定 → インターネット → 接続機器情報 から確認可能
3. 「接続」ボタンをタップ
4. 接続状態が「接続中」に変わります
5. テストボタンで入力を送信できます

**MAC アドレス確認方法:**
- Switch で `設定` → `インターネット` → `接続機器情報` を開く
- Wi-Fi の MAC アドレスが表示されます

### Arduino 自動化

1. 「Arduino自動化」タブを選択
2. 「📁 プログラムをインポート」をタップ
3. デバイスから Python / Shell スクリプトを選択
4. インポート後、プログラムが一覧に表示されます
5. 「実行」ボタンで実行、「停止」で停止、「削除」で削除

#### サポートするファイル形式
- `.py` - Python 3 スクリプト
- `.sh` - Shell スクリプト
- `.json` - JSON スクリプト定義
- `.txt` - テキストファイル（中身から判定）
- `.ino` - Arduino スケッチ

#### 出力確認
- プログラム実行時の標準出力とエラー出力がコンソールに表示されます
- コンソールは自動スクロールします

## API リファレンス

### MainActivity.kt - JavaScript ブリッジ

#### OCR関連
```javascript
// ML Kit で OCR 認識
Android.runMlKit(base64Image)
// → receiveMlKitResult(json) コールバック
```

#### Bluetooth コントローラー関連
```javascript
// Switch に接続
Android.connectBluetoothSwitch(macAddress)

// Switch から切断
Android.disconnectBluetoothSwitch()

// ボタン入力送信
Android.sendControllerInput(buttonData)
// → bluetoothStatus() コールバック
```

#### Arduino 自動化関連
```javascript
// インポート済みプログラム一覧 (JSON文字列)
const programs = Android.getImportedPrograms()

// プログラムをインポート
Android.importArduinoProgram()

// プログラムを実行
Android.executeArduinoProgram(programName, args)

// プログラムを停止
Android.stopArduinoProgram(programName)

// プログラムを削除
Android.deleteArduinoProgram(programName)

// プログラム内容を取得
const content = Android.getProgramContent(programName)

// プログラム内容を更新
Android.updateProgramContent(programName, content)
// → arduinoEvent() / arduinoOutput() コールバック
```

## Bluetooth HID 接続の詳細

### 接続フロー

1. **Bluetooth アダプター取得**
   - BluetoothManager から BluetoothAdapter を取得

2. **HID Device プロキシ接続**
   - `getProfileProxy()` で BluetoothHidDevice を取得

3. **HID アプリケーション登録**
   - SDP 設定（デバイス情報）
   - QoS 設定（通信品質）
   - HID Descriptor（デバイスタイプ定義）

4. **Switch との接続**
   - MAC アドレスで Remote Device を取得
   - HID Device を通じて接続要求

5. **入力送信**
   - ボタン情報をバイト配列で送信
   - `sendReport()` で Nintendo Switch に送信

### 必要な権限

```xml
<!-- Bluetooth権限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- ファイルアクセス -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<!-- HID デバイス機能 -->
<uses-feature android:name="android.hardware.bluetooth" android:required="true" />
```

## Arduino プログラムの例

### Python スクリプト例
```python
#!/usr/bin/env python3
import time
import sys

print("プログラム実行開始")
for i in range(5):
    print(f"カウント: {i}")
    time.sleep(1)

print("完了")
```

### Shell スクリプト例
```bash
#!/bin/bash
echo "シェルスクリプト実行中"
for i in {1..5}; do
    echo "イテレーション: $i"
    sleep 1
done
echo "終了"
```

### JSON スクリプト定義例
```json
{
  "name": "Test Script",
  "commands": [
    "echo 'Hello'",
    "sleep 2",
    "echo 'World'"
  ]
}
```

## トラブルシューティング

### Bluetooth 接続できない
- Switch の MAC アドレスが正しいか確認
- Switch 設定画面で HID デバイス対応を確認
- Bluetooth 権限が付与されているか確認（Android 12 以上）
- Switch を再起動して試す

### Arduino プログラムが実行されない
- ファイル形式が正しいか確認
- Python3 / bash がデバイスにインストールされているか確認
- プログラムの実行権限を確認
- コンソール出力でエラーメッセージを確認

### OCR 認識できない
- 画像が正しく選択されているか確認
- 日本語テキストが含まれているか確認
- ML Kit がダウンロード済みか確認
- インターネット接続を確認

## 開発者向け情報

### プロジェクト構成

- **MainActivity.kt**: メイン UI と JavaScript ブリッジ
- **BluetoothHIDController.kt**: Bluetooth HID プロトコル実装
- **ArduinoAutomationManager.kt**: スクリプト管理・実行

### カスタマイズ

各機能は独立したクラスで実装されているため、個別にカスタマイズ可能です：

1. **Bluetooth コントローラーの拡張**
   - `BluetoothHIDController.kt` の `buildHidDescriptor()` を編集
   - 異なるゲームコントローラーをサポート可能

2. **Arduino スクリプトエンジンの拡張**
   - `ArduinoAutomationManager.kt` の `buildCommand()` を編集
   - 新しいスクリプト形式をサポート可能

3. **UI のカスタマイズ**
   - `snipe_integrated.html` を編集
   - レイアウトやカラースキームを変更可能

## ライセンスと参考

- **JoyConDroid**: https://github.com/rdapps/JoyConDroid
  - Bluetooth HID実装の参考元
  - GNU General Public License v3.0

## サポート

問題が発生した場合は、以下の情報を確認してください：

1. Android バージョン（26 以上）
2. デバイスの Bluetooth 対応状況
3. エラーメッセージ（Logcat から取得）
4. 該当する操作手順

## 更新履歴

### Version 2.0 (統合版)
- JoyConDroid Bluetooth HID 機能を統合
- Arduino 自動化機能を追加
- WebView UI を統一
- マルチプログラム対応

### Version 1.0 (オリジナル)
- ML Kit OCR 認識機能
