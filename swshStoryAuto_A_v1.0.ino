/**
* ポケモン剣盾メインストーリー自動進行
* プログラムはA,Bの2部構成
* Aを使用した後、ワイルドエリアでシェルダーを手動捕獲、その後Bを使用。
* 
* A:主人公名入力後～ワイルドエリア～エンジンシティ手前まで
* 約45分、極稀にメッソンが瀕死になり進行失敗するため、その場合はリセットor手動進行
* 
* 言語、外観、名前を決定し終えたところからスタート
* こだわらない場合は言語選択画面から開始してもOK、その場合は主人公は[「]君となります
* 
* このプログラムAを使用後
* ①デスルーラ後、正面に見える湖へ近づき、釣りポイントで釣りをする
* ②シェルダー（20％）を捕獲、特性スキルリンク、性格補正がAに下降でないものか確認する（後でアイテム使用してもOK）
* ③ポケモンHOMEで別のデータへと輸送し、Lv100、AS252、攻撃に王冠使用、S下降補正かつ個体値15以下ならSも王冠
* 　（パルシェンのS実数地が201以上＞ダンデのドラパルトになるように）
* ④自爆を覚える低レベルのポケモンを捕まえ（タンドンなど）、技の1番上を「自爆」にする
* ⑤Lv90以下、今後LvUPで技を覚えないポケモンを用意し、技の1番上を相手2体攻撃技にする
* 　（ワイルドエリアで捕まえたLv60以上のオノノクス+ワイドブレイカーを推奨）
* ⑥再びHOMEで輸送する
* ⑦自動化するデータを開き、手持ちをシェルダー1匹のみ、ボックス１左上に自爆ポケモン、左下にダブル用ポケモンを用意する
* ⑧エンジンシティへの入り口をくぐったところから、プログラムBをスタート
* 
*/
// 詳しい動作条件はブログ記事参照

// 2021.05.26 苔むした日記帳 https://kokenikki.blogspot.com/2021/05/swshstory01.html

#include <auto_command_util.h>



// Switchスリープ用関数
void sleepNow()
{
  SwitchController().pressButton(Button::HOME);
  delay(1500);
  SwitchController().releaseButton(Button::HOME);
  pushButton(Button::A, 1000);
}

// 言語選択～主人公に適当な名前をつけるまで。
// 任意の名前などにする場合は要調整
void firstSetting(){
    // 言語選択のカーソルを合わせたところからスタート
    pushButton(Button::A, 900, 4);
    pushButton(Button::PLUS, 400, 2);
    pushButton(Button::A, 900, 3);
}


// 主人公設定直後～動けるまで
void roseOpening(){
    // 主人公設定、「はい」を選んだ直後からスタート、動けるまで約3分B連打
    pushButton(Button::B, 200, 635);
}

// オープニング終了後～ポケモンもらえるまで
void startMove(){
    // かばん取りに行く
    tiltJoystick(0, 100, 0, 0, 700);
    delay(200);
    tiltJoystick(100, 0, 0, 0, 3500);
    pushButton(Button::A, 200);
    tiltJoystick(0, -100, 0, 0, 200);
    pushButton(Button::A, 400, 34);
}

// 設定変更
void settingChange(){
    // メニュー「設定」を変更,話速い
    pushButton(Button::X, 900);
    pushHatButtonContinuous(Hat::RIGHT, 1000);
    pushButton(Button::A, 1000);
    pushHatButton(Hat::RIGHT, 300);
    // 日本語、その他言語共通の設定にするため下から順に変更（ひらがな漢字選択はしない）
    pushHatButton(Hat::UP, 300);
    // ムービースキップ、オートセーブOFF、ジャイロOFF、ニックネームOFF、ボックス送りオート、勝ち抜き、戦闘アニメOFF
    pushHatButton(Hat::LEFT, 300);
    pushHatButton(Hat::UP, 300, 3);
    pushHatButton(Hat::RIGHT, 300);
    pushHatButton(Hat::UP, 300, 3);
    pushHatButton(Hat::RIGHT, 300);
    pushHatButton(Hat::UP, 300);
    pushHatButton(Hat::RIGHT, 300);
    pushHatButton(Hat::UP, 300);
    pushHatButton(Hat::RIGHT, 300);
    pushHatButton(Hat::UP, 300);
    pushHatButton(Hat::RIGHT, 300);
    pushHatButton(Hat::UP, 300);
    pushHatButton(Hat::RIGHT, 300);
    // 決定、メニュー閉じる
    pushButton(Button::A, 200, 15);
    pushButton(Button::B, 200, 10);
}

// ポケモンもらえるところまで
void giveMePokemon(){
    // 家を出る～ホップ家右塀に衝突
    tiltJoystick(-100, 30, 0, 0, 4800);
    tiltJoystick(0, 100, 0, 0, 24500);
    pushButton(Button::B, 400, 58);
    tiltJoystick(100, 0, 0, 0, 17000);
    // ホップ家の中へ
    tiltJoystick(-100, 0, 0, 0, 950);
    tiltJoystick(0, -100, 0, 0, 1800);
    pushButton(Button::B, 400, 48);
    // 家を出る～ダンデに会い、ポケモンもらえるまで
    tiltJoystick(100, 30, 0, 0, 1500);
    tiltJoystick(0, 100, 0, 0, 300);
    delay(2000);
    tiltJoystick(0, 100, 0, 0, 300);
    tiltJoystick(-100, 0, 0, 0, 5000);
    tiltJoystick(0, -100, 0, 0, 5000);
    pushButton(Button::B, 400, 14);
    tiltJoystick(-10, -100, 0, 0, 18000);
    pushButton(Button::B, 400, 215);

}

// メッソンを選ぶ
void chooseSobble(){
    // メッソンに衝突
    tiltJoystick(0, -100, 0, 0, 700);
    SwitchController().setStickTiltRatio(-100, 0, 0, 0);
    pushButton(Button::A, 100, 20);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    // メッソン選択～バトル、ウールーへはたく×4、約2:20
    pushButton(Button::A, 100, 700);
    // ヒバニー出るまでB連打、急所等も考慮して多めに
    pushButton(Button::B, 100, 90);
    // たたかう＞水鉄砲、その後約50ｓ
    pushButton(Button::A, 500);
    pushHatButton(Hat::DOWN, 300, 2);
    pushButton(Button::A, 100, 275);
}

void wooluuRescue(){
    // 塀の外へ、ウールー助けに
    tiltJoystick(-80, 100, 0, 0, 2000);
    pushButton(Button::A, 500, 40);
    tiltJoystick(-30, 100, 0, 0, 2000);
    tiltJoystick(-30, -100, 0, 0, 200);
    tiltJoystick(-30, 100, 0, 0, 2000);
    // 左スティックを左(少し下)に固定、野生戦開始
    SwitchController().setStickTiltRatio(-100, 30, 0, 0);
    pushButton(Button::B, 500, 60);
    // 逃げる×3
    pushHatButton(Hat::UP, 300);
    pushButton(Button::A, 500);
    pushButton(Button::B, 500, 48);
    pushHatButton(Hat::UP, 300);
    pushButton(Button::A, 500);
    pushButton(Button::B, 500, 46);
    pushHatButton(Hat::UP, 300);
    pushButton(Button::A, 500);
    pushButton(Button::B, 500, 16);
    // 左スティックを上に固定、ザシザマ戦開始（30s）
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    pushButton(Button::A, 100, 150);
    // 左スティック固定解除、ニュートラル、引き続きA連打(2min)、家の前へ
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::A, 100, 605);

}

void changeMovePosition(){
    // メッソンの技の1番上を水鉄砲に変更
    pushButton(Button::X, 1000);
    pushHatButtonContinuous(Hat::LEFT, 1500);
    pushButton(Button::A, 1500);
    pushButton(Button::A, 500);
    pushButton(Button::A, 2000);
    pushHatButton(Hat::RIGHT, 400, 2);
    pushButton(Button::A, 400, 2);
    pushHatButton(Hat::DOWN, 400, 2);
    pushButton(Button::A, 400);
    pushButton(Button::B, 500, 8);
    
}

void talkWithMom(){
    // 自宅、ママに話しかけ
    tiltJoystick(-80, -100, 0, 0, 4800);
    delay(1200);
    tiltJoystick(100, -60, 0, 0, 1500);
    pushButton(Button::B, 500, 30);
    tiltJoystick(-100, 30, 0, 0, 2000);
    tiltJoystick(0, 100, 0, 0, 7500);
}

void goToRoute1(){
    tiltJoystick(100, -30, 0, 0, 6000);
    tiltJoystick(0, -100, 0, 0, 10000);
    pushButton(Button::B, 500, 13);
    tiltJoystick(0, -100, 0, 0, 3000);
    // 左スティックを右に倒し、野生戦開始
    // 野生との戦闘になったらボールを投げる
    SwitchController().setStickTiltRatio(100, 0, 0, 0);
    pushButton(Button::B, 100, 38);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::X, 500);
    pushButton(Button::A, 500);
    for(int i=0; i<5; i++){
        SwitchController().setStickTiltRatio(100, -40, 0, 0);
        pushButton(Button::B, 100, 38);
        SwitchController().setStickTiltRatio(0, 0, 0, 0);
        pushButton(Button::X, 500);
        pushButton(Button::A, 500);
     }
    pushButton(Button::B, 100, 10);
    // 左スティックを上に倒す
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    pushButton(Button::B, 100, 25);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    // 左スティックを左に倒し、捕獲
    tiltJoystick(-100, 0, 0, 0, 2000);
    tiltJoystick(90, 20, 0, 0, 800);
    for(int i=0; i<14; i++){
        SwitchController().setStickTiltRatio(-100, 0, 0, 0);
        pushButton(Button::B, 100, 38);
        SwitchController().setStickTiltRatio(0, 0, 0, 0);
        pushButton(Button::X, 500);
        pushButton(Button::A, 500);
     }
    pushButton(Button::B, 100, 20);
    // プラッシータウン駅に衝突～研究所へ
    tiltJoystick(0, -100, 0, 0, 8000);
    tiltJoystick(0, 100, 0, 0, 800);
    tiltJoystick(100, 0, 0, 0, 8500);
    pushButton(Button::A, 400, 170);
    // 研究所を出る
    tiltJoystick(0, 100, 0, 0, 3000);
    pushButton(Button::B, 400, 40);
    
}

void BrassyWalk(){
    // ポケセンへ
    tiltJoystick(-100, 30, 0, 0, 4500);
    tiltJoystick(0, -100, 0, 0, 5000);
    pushButton(Button::B, 400, 44);
    pushHatButton(Hat::DOWN, 400);
    pushButton(Button::A, 400, 24);
    tiltJoystick(0, 100, 0, 0, 1200);
    delay(1800);
    tiltJoystick(-100, 100, 0, 0, 1700);
    // 2番道路へ、捕獲チュートリアルスキップ
    tiltJoystick(0, -100, 0, 0, 4000);
    pushButton(Button::A, 400, 28);
    tiltJoystick(100, -100, 0, 0, 5500);
    tiltJoystick(0, 100, 0, 0, 800);
    tiltJoystick(100, 0, 0, 0, 9000);
}

void goToRoute2(){
    // ボールをもらった後、博士の家まで
    // 左スティックを右に固定、野生戦開始
    SwitchController().setStickTiltRatio(100, 0, 0, 0);
    // 野生との戦闘になったら逃げる
    for(int i=0; i<14; i++){
        pushHatButton(Hat::UP, 500);
        pushButton(Button::A, 500);
        pushButton(Button::B, 500, 4);
     }
    // 左スティックを上に固定、野生は倒す
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    pushButton(Button::A, 100, 250);
    // 左スティックを右に固定、短パン小僧、野生を撃破,壁に衝突
    SwitchController().setStickTiltRatio(100, 0, 0, 0);
    for(int i=0; i<45; i++){
        pushButton(Button::A, 100, 5);
        pushButton(Button::B, 100, 5);
     }
    // アイテム（ボール3個）に衝突
    tiltJoystick(-100, -100, 0, 0, 3000);
    // バッグを開き、傷薬をメッソンに使う
    pushButton(Button::X, 500);
    pushHatButtonContinuous(Hat::LEFT_UP, 1500);
    pushHatButton(Hat::RIGHT, 400, 2);
    pushButton(Button::A, 2000);
    pushButton(Button::A, 500, 3);
    pushButton(Button::B, 500, 9);
    // 左スティックを左に固定、アイテムを取り、壁に衝突
    SwitchController().setStickTiltRatio(-100, 0, 0, 0);
    pushButton(Button::A, 100, 30);
    // 左スティックを右上に固定、ミニスカートを撃破,壁に衝突
    SwitchController().setStickTiltRatio(100, -100, 0, 0);
    pushButton(Button::A, 100, 420);
    // バッグを開き、傷薬をメッソンに使う
    pushButton(Button::X, 500);
    pushButton(Button::A, 2000);
    pushButton(Button::A, 500, 3);
    pushButton(Button::B, 500, 9);
    // 少し移動
    tiltJoystick(-100, 100, 0, 0, 1000);
    // 左スティックを上に固定、短パン小僧撃破、そのまま博士の家へ
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    pushButton(Button::A, 100, 540);
    // スティックを戻し、引き続き会話
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::A, 100, 230);
    // ホップと対戦へ
    tiltJoystick(100, 30, 0, 0, 1700);
    tiltJoystick(0, 100, 0, 0, 5000);
    pushButton(Button::A, 500, 10);
    tiltJoystick(-100, 0, 0, 0, 650);
    tiltJoystick(0, -100, 0, 0, 200);
    // 戦闘開始～推薦状、DMバンド、約4min
    pushButton(Button::A, 100, 1280);
    tiltJoystick(100, 60, 0, 0, 2500);
    delay(3000);
}

// 博士の家を出た直後からスタート
void goToBrStation(){
    SwitchController().setStickTiltRatio(0, 100, 0, 0);
    // 口笛の説明～壁にぶつかるまで30sec
    pushButton(Button::B, 100, 150);
    // 左下移動開始、シンボルにぶつかりにくいように角度調整
    SwitchController().setStickTiltRatio(-80, 100, 0, 0);
    // 野生との戦闘になったら逃げる
    // 戦闘なければ約30sec,戦闘1回ごとに15sec?4戦で90sec
    for(int i=0; i<30; i++){
        pushHatButton(Hat::UP, 500);
        pushButton(Button::A, 500);
        pushButton(Button::B, 500, 4);
     }
    // 左上方向に進み街の壁に衝突 
    tiltJoystick(0, -100, 0, 0, 400);
    tiltJoystick(-100, 0, 0, 0, 5000);
    tiltJoystick(-100, -100, 0, 0, 4000);
    // 下方向に進みホップに呼ばれる 
    SwitchController().setStickTiltRatio(0, 100, 0, 0);
    pushButton(Button::B, 100, 150);
    // 引っかかったところから左上へ、駅に入るまで
    SwitchController().setStickTiltRatio(-60, -100, 0, 0);
    pushButton(Button::B, 100, 50);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    pushButton(Button::A, 100, 200);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::A, 100, 100);
}

void wildAreaFirstTime(){
    // ワイルドエリア駅を出る
    tiltJoystick(0, 100, 0, 0, 100);
    tiltJoystick(100, 0, 0, 0, 1700);
    tiltJoystick(-30, 100, 0, 0, 1000);
    delay(10000);
    tiltJoystick(-20, -100, 0, 0, 5000);
    // ホップソニアと会話、ポケモンボックスもらう
    pushButton(Button::A, 100, 300);
    
}

void poppoTotsugeki(){
    // メニューを開き、ボックス開く
    pushButton(Button::X, 1000);
    pushHatButtonContinuous(Hat::LEFT_UP, 1000);
    pushHatButton(Hat::RIGHT, 300);
    pushButton(Button::A, 2000);
    pushButton(Button::R, 2000);
    // ボックス開け初回のみ説明文表示
    pushButton(Button::A, 1000);
    // 手持ちの1番目（メッソン）と2番目（捕まえたポケモンA）を入れ替える
    pushButton(Button::Y, 400);
    pushHatButton(Hat::LEFT, 300);
    pushButton(Button::A, 400);
    pushHatButton(Hat::DOWN, 300);
    pushButton(Button::A, 400);
    // 手持ち2番目以降をボックス1に預ける
    pushButton(Button::Y, 400);
    pushButton(Button::A, 400);
    pushHatButton(Hat::DOWN, 300, 4);
    pushButton(Button::A, 400);
    pushHatButtonContinuous(Hat::DOWN, 1200);
    pushHatButton(Hat::RIGHT, 300);
    pushButton(Button::A, 500, 2);
    pushButton(Button::B, 500, 10);
    // ワイルドエリア入ってすぐのイワークにぶつかる
    tiltJoystick(0, -100, 0, 0, 1000);
    tiltJoystick(0, 0, -100, 0, 400);
    tiltJoystick(0, -100, 0, 0, 6000);
    pushButton(Button::B, 100, 10);
    tiltJoystick(0, -100, 0, 0, 3000);
    // ずれた時のために、口笛吹きながらイワーク付近をうろうろ
    pushButton(Button::LCLICK, 500);
    tiltJoystick(-100, -100, 0, 0, 200);
    tiltJoystick(100, -100, 0, 0, 500);
    tiltJoystick(100, 100, 0, 0, 500);
    tiltJoystick(-100, 100, 0, 0, 500);
    tiltJoystick(-100, -100, 0, 0, 500);
    // イワークに倒されるまで技を連打、倒されたらエンジンシティ入り口までデスルーラ
    pushButton(Button::A, 100, 450);
    // メニューを開き、ボックス開く
    pushButton(Button::X, 1000);
    pushHatButtonContinuous(Hat::LEFT_UP, 1000);
    pushHatButton(Hat::RIGHT, 300);
    pushButton(Button::A, 2000);
    pushButton(Button::R, 2000);
    // 手持ちの1匹目（ポケA）と、ボックスの左上（メッソン）入れ替え
    pushButton(Button::Y, 400);
    pushButton(Button::A, 400);
    pushHatButton(Hat::LEFT, 300);
    pushButton(Button::A, 400);
    pushButton(Button::B, 500, 10);
}


void setup(){
    
    pushButton(Button::B, 300, 13);
    // 言語、主人公の容姿や名前を選択、決定した直後からスタート
    // 一応、言語選択画面から始めた場合は名前が[「]などで始まります
    firstSetting();
    // ストーリー進行
    roseOpening();
    startMove();
    settingChange();
    giveMePokemon();
    chooseSobble();
    wooluuRescue();
    changeMovePosition();    
    talkWithMom();
    goToRoute1();
    BrassyWalk();
    goToRoute2();
    goToBrStation();
    wildAreaFirstTime();
    poppoTotsugeki();
    // イワークによって瀕死になり、エンジンシティ前にデスルーラして終了
    // この後、手動でシェルダー捕獲、Lv100など準備する
    // エンジンシティに入ったところからプログラムBを開始する
    sleepNow();
}

void loop(){
    

}
