/**
* ポケモン剣盾メインストーリー自動進行
* プログラムはA,Bの2部構成
* Aを使用した後、ワイルドエリアでシェルダーを手動捕獲、その後Bを使用。
* 
* B:エンジンシティ入り口～殿堂入りまで
* 
* 以下のsebango[]に希望する背番号を入力する
* 下記のポケモンを揃え、ワイルドエリアからエンジンシティへの階段を上ったところからスタート
* 
* プログラムAを使用後
* このプログラムAを使用後
* ①デスルーラ後、正面に見える湖へ近づき、釣りポイントで釣りをする
* ②シェルダー（20％）を捕獲、特性スキルリンク、性格補正がAに下降でないものか確認する（後でアイテム使用してもOK）
* ③ポケモンHOMEで別のデータへと輸送し、Lv100、AS252、攻撃に王冠使用、
* 　S下降補正かつ個体値15以下（シェルダーS実数値146以下）ならSも王冠
* 　（パルシェンのS実数値が201以上＞ダンデのドラパルトになるように）
* 　また、技を「つららばり」のみにする。
* ④自爆を覚える低レベルのポケモンを捕まえ（タンドンなど）、技の1番上を「自爆」にする
* ⑤Lv80以下、今後LvUPで技を覚えないダブルバトル用のポケモンを用意し、技の1番上を相手2体攻撃技にする
* 　（ワイルドエリアで捕まえたLv60以上のオノノクス+ワイドブレイカーを推奨）
* ⑥再びHOMEで輸送する
* ⑦自動化するデータを開き、手持ちをシェルダー1匹のみ、ボックス１左上に自爆ポケモン、左下にダブル用ポケモンを用意する
* ⑧エンジンシティへの入り口をくぐったところから、このプログラムBをスタート
* 
*/

// 詳しい動作条件はブログ記事参照

// 2021.11.12 苔むした日記帳 https://kokenikki.blogspot.com/2021/11/poke13.html

//設定項目0　DLCの有無
/*
 * DLCがない場合、メニュー右下にDLC購入のアイコンがあるため、一部操作を変更
 * DLCありの場合は1を、無しの場合は0を設定
 */
const int dlc = 1;

//設定項目①　背番号
/*
 * ジムチャレンジの開会式前に背番号を設定する
 * 1~3桁の数字を以下の「char sebango[]」に設定する
 */
char sebango[] = "25";

// 設定項目②　ムゲンダイナ捕獲に用いるボール
/*
 * チャンピオン　ダンデ戦の前にムゲンダイナの強制捕獲イベントあり
 * ムゲンダイナを捕獲するために用いるボールを以下から選択し、
 * 対応する数字を以下の「const int ball_number」に設定する
 * 
 * 0: モンスターボール
 * (1桁台はオシャボ、ストーリー入手順。スピードボールはロトムラリー報酬のため自動入手は非対応)
 * 1: フレンドボール
 * 2: ルアーボール
 * 3: レベルボール
 * 4: ヘビーボール
 * 5: ラブラブボール
 * 6: ムーンボール
 * 7: ドリームボール
 * (8:スピードボールはロトムラリー10回報酬のため現在非対応)
 * 9:任意ボール（自動化開始前にマスボなどを通信交換にて輸送した場合。下記参照）
 * (10番台はショップで購入可能なボール)
 * 10: プレミアボール
 * 11: スーパーボール
 * 12: ハイパーボール
 * 13: ヒールボール
 * 14: ネットボール
 * 15: ネストボール
 * 16: ダイブボール
 * 17: ダークボール
 * 18: タイマーボール
 * 19: ゴージャスボール
 * 20: クイックボール
 * 21: リピートボール
 * 
 * 以下はストーリー中に入手困難なため非対応。
 * [マスターボール、スピードボール、ウルトラボール、サファリボール、コンペボール]
 * これらで捕獲したい場合は通信交換などで手動入手し、
 * 　そのボールをリスト2番目に配置（1番上はモンスターボール）したうえで数字は9を指定すること。
 * 
 */

const int ball_number = 10;

#include <auto_command_util.h>


// 背番号入力用のキーボード配列。
char keyNum[4][4] = {"123", 
                     "456",
                     "789",
                     "X0X"};


// Switchスリープ用関数
void sleepNow()
{
  SwitchController().pressButton(Button::HOME);
  delay(1500);
  SwitchController().releaseButton(Button::HOME);
  pushButton(Button::A, 1000);
}

// キーボードが開かれたところで指定されたコードを1文字ずつ入力し、最後に決定する
void keyTypingNum(char code[]){
    // 初期位置を指定。左上角を0，0とする
    int nowPosition[2] = {0, 0};
    // 1文字ずつ、文字が何行目、何列目かを確認
    for (int n = 0; n < strlen(code); n++){
      int gyo = 0;
      int retsu = 0;
      for (int i = 0; i < 4; i++){
        for (int j = 0; j < 3; j++){
          if (code[n] == keyNum[i][j]){
            gyo = i;
            retsu = j;
            break;
          }
        }
        if (code[n] == keyNum[gyo][retsu]){
          break;
        }
      }
      // 何文字↓、→に動かすかを確認（負の場合は逆方向）
      int sita = gyo - nowPosition[0];
      int migi = retsu - nowPosition[1];
      if (sita < 0){
        pushHatButton(Hat::UP, 100, sita * -1);
      } else {
        pushHatButton(Hat::DOWN, 100, sita);
      }
      if (migi < 0){
        pushHatButton(Hat::LEFT, 100, migi * -1);
      } else {
        pushHatButton(Hat::RIGHT, 100, migi);
      }
      pushButton(Button::A, 100);
      // 入力後の位置を現在位置として設定
      nowPosition[0] = gyo;
      nowPosition[1] = retsu;
    }
    // +ボタンで入力完了
    pushButton(Button::PLUS, 100, 2);
}

// メニュー左下のタウンマップを開き、空をとぶ操作。頻繁に使うため共通の関数として設定
// ～Openでタウンマップを開いた後、十字キーやYボタンで位置選択、～Selectで選択して飛ぶ
void flyingTaxiOpen(){
    //タウンマップ開く
    pushButton(Button::X, 1000);
    // DLCが入っている場合は操作を短縮、入っていない場合は右下にDLC購入アイコンがあるため変更
    if(dlc == 1){
      pushHatButtonContinuous(Hat::DOWN_LEFT, 1000);
    }else{
      pushHatButtonContinuous(Hat::LEFT_UP, 1000);
      pushHatButton(Hat::DOWN, 300);
    }
    pushButton(Button::A, 3000);
}

void flyingTaxiSelect(){
    //タウンマップ上の地点を選択し、空を飛ぶ決定
    delay(200);
    pushButton(Button::A, 1100);
    pushButton(Button::A, 1500);
    pushButton(Button::B, 500, 6);
}

// 空を飛ぶについて、何度も使う操作のみ関数化
// 空を飛ぶで同じ町のポケセンまで飛ぶ
void flyingToSamePlace(){
    flyingTaxiOpen();
    flyingTaxiSelect();
}

// 目的地として選択された別の町まで空を飛ぶ
void flyingToDirection(){
    flyingTaxiOpen();
    pushButton(Button::Y, 300);
    flyingTaxiSelect();
    delay(5500);
}

// メニュー上段、左から2番目の「ポケモン」を開き、手持ちを表示する
void pokemonOpen(){
    // メニューを開き、手持ちポケモンを表示
    pushButton(Button::X, 1000);
    pushHatButtonContinuous(Hat::LEFT_UP, 1000);
    pushHatButton(Hat::RIGHT, 300);
    pushButton(Button::A, 2000);
}

void bagOpen(){
    // バッグを開く
    pushButton(Button::X, 500);
    pushHatButtonContinuous(Hat::LEFT_UP, 1500);
    pushHatButton(Hat::RIGHT, 400, 2);
    pushButton(Button::A, 2000);
}


// 1方向に走りながらA連打、Aは1秒間に5回連打のため、連打回数/5がおおよその移動時間
// 移動と戦闘や会話を連続して行う際に使用
// Rスティックは使用しない。LスティックのX座標（右が正）、Y座標（下が正）、A連打の回数を指定する
void runStraightWithA(int lx, int ly, int repeatA){
    SwitchController().setStickTiltRatio(lx, ly, 0, 0);
    delay(1000);
    pushButton(Button::A, 100, repeatA);
}

// 上記関数のB連打版
void runStraightWithB(int lx, int ly, int repeatB){
    SwitchController().setStickTiltRatio(lx, ly, 0, 0);
    delay(1000);
    pushButton(Button::B, 100, repeatB);
}

// 各ジム戦を終えた後、ボールガイに話しかけてガンテツボールを受け取る
void ballguyTalk(){
    // ジム戦終了後、入り口付近のボールガイに話しかける
    SwitchController().setStickTiltRatio(20, 100, 0, 0);
    pushButton(Button::A, 100, 15);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::B, 100, 45);
    // 外へ
    tiltJoystick(-30, 100, 0, 0, 4000);
}

// ショップにて、リストの上から〇番目のボールを買う
// 話しかけるところからスタート
// ballListは0, 1, 2いずれか
void buyPokeball(int ballList){
    // アイテムを買う
    pushButton(Button::A, 1000, 3);
    delay(1000);
    // 〇番目のボールを選択
    pushHatButton(Hat::DOWN, 300, ballList);
    pushButton(Button::A, 800, 4);
    // 買いを終了、買う売るの選択肢へ
    pushButton(Button::B, 1500);
    
}

// ショップにて、ボールポケット1番上のモンスターボールをすべて売る
// 話しかけて選択肢の出たところからスタート
void sellPokeball(){
    /// アイテムを売る
    pushHatButton(Hat::DOWN, 300);
    pushButton(Button::A, 3000);
    // 念のためカーソルを左に戻してから、ボールポケットへ
    pushHatButtonContinuous(Hat::LEFT, 2000);
    pushHatButton(Hat::RIGHT, 400);
    // 1番上のモンスターボールを選択し、すべて売る
    pushButton(Button::A, 500);
    pushHatButton(Hat::DOWN, 400);
    pushButton(Button::A, 500, 3);
    // 売り買いを終了
    pushButton(Button::B, 500, 8);
    
}

// ラテラルタウンのジムチャレンジ、乗り物をRスティック回転でまわずための関数
// 「1秒あたりの回転数(round/sec)*10」、「回転させる時間(ミリ秒)」、「回転方向（時計回りなら1/反時計なら-1）」をそれぞれ指定する
// ~(50, 3000, -1);の場合、反時計回りに5.0 round/secを3秒間（3秒で15回転）行う
// 回転数は100を割り切れる数でない場合はずれるため注意
// 開店時間のうち1周に満たない端数は切り捨てる
void guruGuru(int drps, int msec, int direc){
    // 残り時間
    int jikan = msec;
    // 1回転あたりにかかる時間(ms)/10(cs)
    int cspr = 1000/drps;
    // 残り時間が1回転当たりの時間を上回る限り、1回転ずつ時間を消費する
    while(jikan >= cspr*10){
      // 1回転を10分割して入力する
      SwitchController().setStickTiltRatio(0, 0, 0, -100);
      delay(cspr);
      SwitchController().setStickTiltRatio(0, 0, 59 * direc, -81);
      delay(cspr);
      SwitchController().setStickTiltRatio(0, 0, 95 * direc, -31);
      delay(cspr);
      SwitchController().setStickTiltRatio(0, 0, 95 * direc, 31);
      delay(cspr);
      SwitchController().setStickTiltRatio(0, 0, 59 * direc, 81);
      delay(cspr);
      SwitchController().setStickTiltRatio(0, 0, 0, 100);
      delay(cspr);
      SwitchController().setStickTiltRatio(0, 0, -59 * direc, 81);
      delay(cspr);
      SwitchController().setStickTiltRatio(0, 0, -95 * direc, 31);
      delay(cspr);
      SwitchController().setStickTiltRatio(0, 0, -95 * direc, -31);
      delay(cspr);
      SwitchController().setStickTiltRatio(0, 0, -59 * direc, -81);
      delay(cspr);
      jikan = jikan - cspr *10;
    }
}




// ここからストーリー用関数

// エンジンシティ到着直後からスタート、手持ちはシェルダーLv100のみ
void firstEngine(){
    // 前進し、ロトミの紹介まで、50秒くらい
    runStraightWithA(0, -100, 150);
    runStraightWithA(0, 0, 110);
    // ポケセンを出て右へ
    tiltJoystick(0, 100, 0, 0, 2000);
    tiltJoystick(100, 0, 0, 0, 3000);
    // ソニアと会話30秒くらい、その後に前進し続ける。ダンデ、ホップ、受付と会話して合計2分30秒程度
    pushButton(Button::A, 100, 100);
    runStraightWithA(0, -100, 620);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
}

// 背番号の決定、上記「char sebango[]」の番号を設定
void uniformNumber(){
    // 前のA連打で111と連打されているため、B長押しで消去
    SwitchController().pressButton(Button::B);
    delay(2000);
    SwitchController().releaseButton(Button::B);
    // 上の「char sebango[]」で設定した3桁までの数字を入力
    keyTypingNum(sebango);
    // 背番号入力後、B連打で会話終了、10秒
    pushButton(Button::B, 100, 50);
    // 外で呼び止められるまで下方向へ、10秒
    tiltJoystick(0, 100, 0, 0, 10000);
    
}

// 背番号を決めた後、ホテルへ行きエール団と戦闘、次の日の開会式、空を飛ぶを使えるまで
void hotelWithYelling(){
    // 背番号決めてスタジアム出た後、会話8秒
    pushButton(Button::B, 100, 40);
    //左下1.2秒、左8秒
    tiltJoystick(-100, 100, 0, 0, 1200);
    tiltJoystick(-100, 0, 0, 0, 8000);
    // 会話12秒、ホテル前
    pushButton(Button::B, 100, 60);
    // 前進してホテルへ、6秒
    tiltJoystick(0, -100, 0, 0, 6000);
    // ソニア会話40秒
    pushButton(Button::A, 100, 200);
    //左0.7秒、上2秒くらいでエール団会話、戦闘開始
    tiltJoystick(-100, 0, 0, 0, 700);
    runStraightWithA(0, -100, 150);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    // そのまま3戦、氷柱針を連打、マリー登場、会話終了まで3分くらい
    // ダブルバトルでジグザグマから舌で舐める麻痺をもらう可能性あり
    pushButton(Button::A, 100, 950);
    // 左で受付、次の日まで30秒
    tiltJoystick(-100, 0, 0, 0, 1500);
    pushButton(Button::A, 100, 150);
    // ホテルを出る
    tiltJoystick(30, 70, 0, 0, 1500);
    tiltJoystick(-30, 70, 0, 0, 5000);
    // 会話、スタジアム前からそのまま開会式、計1:40くらい
    pushButton(Button::A, 100, 50);
    runStraightWithA(0, -100, 250);
    runStraightWithA(0, 0, 150);
    // 外へ
    tiltJoystick(-40, 80, 0, 0, 8000);
    // そらとぶタクシーGET
    pushButton(Button::B, 100, 125);
}

// 空を飛べるようになったら3番道路へ
void goToRoute3(){
    // エンジンシティ内で空を飛ぶ、左のポケセンへ
    flyingTaxiOpen();
    pushHatButton(Hat::LEFT, 200);
    flyingTaxiSelect();
    // 左へ進んですぐ、ホップと戦闘、そのまま左へ進み3番道路草むら直前の壁にぶつかる、2分ちょい
    runStraightWithA(-100, 0, 650);
    // できるだけ草むらをよけつつ、トレーナーと戦闘
    SwitchController().setStickTiltRatio(20, 70, 0, 0);
    delay(1600);
    // VSミニスカート
    runStraightWithA(0, 100, 8);
    runStraightWithA(-70, 70, 220);
    // VS塾帰り×2、野生×2程度も想定
    // 塾帰り男と地形に引っかかる恐れがあるため、少し右に避ける
    runStraightWithA(-70, -40, 150);
    runStraightWithA(-70, -70, 200);
    pushButton(Button::B, 100, 5);
    runStraightWithA(-60, -90, 400);
    pushButton(Button::B, 100, 15);
    // ソニアと会話、その後左へ進む
    runStraightWithA(-100, 0, 8);
    runStraightWithA(-80, -60, 200);
    // VS配達員、野生×1
    runStraightWithA(-80, 45, 350);
    // 左へ、塾帰り、野生×2を想定
    runStraightWithA(-80, -30, 5);
    runStraightWithA(-100, 0, 650);
    // 上へ進み、鉱山へ
    runStraightWithA(30, -90, 150);
    SwitchController().setStickTiltRatio(-50, -80, 0, 0);
    delay(3000);
    SwitchController().setStickTiltRatio(100, 0, 0, 0);
    delay(500);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    delay(1000);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    delay(3000);
}

// ガラル鉱山
void galarMine(){
    // 野生と接触しないように左上へ
    SwitchController().setStickTiltRatio(-20, -80, 0, 0);
    delay(3000);
    runStraightWithA(-70, -70, 14);
    // 通路左の作業員×2は無視しつつ、線路沿いに進む
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    delay(2000);
    runStraightWithA(70, -80, 15);
    runStraightWithA(30, -80, 100);
    // 橋の手前、作業員女と戦闘、その後左上へ進み良い傷薬まで、野生と1回戦闘を想定
    runStraightWithA(-70, -80, 375);
    runStraightWithA(20, -80, 25);
    // 最奥のビートと戦闘、戦闘後も前進して4番道路の標識まで
    runStraightWithA(0, -100, 540);
    pushButton(Button::B, 100, 50);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    
}

// 4番道路、戦闘は無し、そのままターフタウンでソニアと会話
void goToRoute4(){
    // 丸い標識からヤローに会うまで前進、戦闘なし
    tiltJoystick(100, 0, 0, 0, 300);
    tiltJoystick(0, -100, 0, 0, 6800);
    tiltJoystick(70, -70, 0, 0, 4700);
    // 会話30秒、そのまま続けてターフタウンでホップと会話
    pushButton(Button::A, 100, 50);
    runStraightWithA(0, -100, 150);
    // イヌヌワンに呼ばれて左へ、ソニアと会話
    runStraightWithA(-100, 0, 125);
    runStraightWithA(-70, 70, 20);
    runStraightWithA(-40, -80, 275);
    runStraightWithB(0, 0, 10);
}

// ターフタウンのジムチャレンジ
void turfGym(){
    // ターフタウン内、空を飛ぶでポケセンまで戻る
    flyingToSamePlace();
    //ジム正面の道へ
    tiltJoystick(-100, 0, 0, 0, 1500);
    // 前進、ホップやジム受付と会話、20秒ちょい
    runStraightWithA(0, -100, 120);
    runStraightWithA(0, 0, 300);
    // ウールー移動その1、ジグザグ走行
    tiltJoystick(-80, -50, 0, 0, 1500);
    tiltJoystick(80, -50, 0, 0, 3000);
    tiltJoystick(-80, -50, 0, 0, 3000);
    tiltJoystick(80, -30, 0, 0, 2300);
    tiltJoystick(-80, -30, 0, 0, 2400);
    tiltJoystick(100, 20, 0, 0, 3500);
    tiltJoystick(-100, 0, 0, 0, 5000);
    tiltJoystick(100, 0, 0, 0, 10000);
    // ウールー移動その2、ウールーその１突破後、放置してトレーナーと戦闘
    // イヌヌワンにより、ウールーは左寄りの吠えられない位置へ移動
    // ウールーを動かさないように右側を進み、トレーナーと戦闘
    tiltJoystick(0, -100, 0, 0, 2800);
    tiltJoystick(60, -90, 0, 0, 4000);
    runStraightWithA(0, -100, 200);
    runStraightWithB(0, 0, 25);
    // 戦闘後、右側を通って戻り、左側にウールーを寄せながら手前から奥へ走行
    tiltJoystick(0, 100, 0, 0, 5000);
    tiltJoystick(80, 80, 0, 0, 3000);
    tiltJoystick(0, 100, 0, 0, 2000);
    tiltJoystick(-80, -60, 0, 0, 1500);
    tiltJoystick(-50, 30, 0, 0, 4000);
    tiltJoystick(50, -50, 0, 0, 1200);
    delay(1000);
    tiltJoystick(0, -60, 0, 0, 23000);
    // ウールー移動その3、右ルートはトレーナーがいる代わりにイヌヌワンがいない
    tiltJoystick(-100, 100, 0, 0, 5000);
    tiltJoystick(-100, 0, 0, 0, 1000);
    tiltJoystick(50, -50, 0, 0, 6500);
    tiltJoystick(0, -50, 0, 0, 10000);
    pushButton(Button::A, 100, 250);
    pushButton(Button::B, 100, 20);
    tiltJoystick(0, -50, 0, 0, 4000);
    tiltJoystick(-30, -40, 0, 0, 20000);
    tiltJoystick(-60, -80, 0, 0, 2500);
    // ウールー移動その4、ウールーを右に寄せながら左側を直進し、トレーナーに見つかる
    tiltJoystick(0, 100, 0, 0, 3000);
    tiltJoystick(-80, 80, 0, 0, 2000);
    tiltJoystick(50, 0, 0, 0, 1000);
    tiltJoystick(0, -100, 0, 0, 7000);
    tiltJoystick(0, -50, 0, 0, 2000);
    SwitchController().setStickTiltRatio(100, 0, 0, 0);
    pushButton(Button::A, 100, 5);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::A, 100, 300);
    pushButton(Button::B, 100, 20);
    // 戦闘後、ウールーを右端に寄せて進む
    tiltJoystick(-100, 0, 0, 0, 3000);
    tiltJoystick(0, 100, 0, 0, 12000);
    tiltJoystick(0, -50, 0, 0, 1000);
    tiltJoystick(40, -40, 0, 0, 5200);
    delay(500);
    tiltJoystick(0, -50, 0, 0, 2000);
    delay(1000);
    tiltJoystick(40, -40, 0, 0, 500);
    delay(1000);
    tiltJoystick(0, -50, 0, 0, 30000);
    // ゴール、そのままヤロー戦
    tiltJoystick(0, -100, 0, 0, 9000);
    tiltJoystick(-50, -20, 0, 0, 2000);
    runStraightWithA(0, -100, 600);
    runStraightWithA(0, 0, 450);
    pushButton(Button::B, 100, 20);
    // スタジアムを出る前にフレンドボールをもらう
    ballguyTalk();
    delay(2000);
}

// ジム終了後、ターフタウンを出て5番道路、バウタウンまで
void goToRoute5(){
    // ターフタウン内、空を飛ぶでポケセンまで戻る
    flyingToSamePlace();
    // 右方向、リポーターを無視して自転車入手まで
    tiltJoystick(100, 0, 0, 0, 15000);
    tiltJoystick(0, 100, 0, 0, 300);
    tiltJoystick(100, 0, 0, 0, 15000);
    // エール団戦約2分、ホップ戦開始までさらに20秒くらいホップ戦も2分くらい
    runStraightWithA(100, 0, 750);
    runStraightWithA(0, 0, 450);
    // 戦闘終了後、自転車に乗って移動、OLと戦闘30秒、そのままバウタウンに入り短パンローズと会話、会話終了後は柵にぶつかり待機
    pushButton(Button::PLUS, 300);
    tiltJoystick(100, -35, 0, 0, 13000);
    runStraightWithA(100, 0, 500);
    pushButton(Button::B, 100, 20);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    
}

// バウタウンのジムチャレンジ
void bauGym(){
    // バウタウン内、空を飛ぶでポケセンまで戻る
    flyingToSamePlace();
    // 自転車で岬にいるルリナと会話、通行人に注意
    pushButton(Button::PLUS, 300);
    tiltJoystick(100, 0, 0, 0, 500);
    tiltJoystick(30, -80, 0, 0, 5000);
    tiltJoystick(100, 0, 0, 0, 5000);
    tiltJoystick(0, 100, 0, 0, 2000);
    tiltJoystick(30, -30, 0, 0, 600);
    tiltJoystick(100, 0, 0, 0, 5000);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    pushButton(Button::A, 100, 20);
    runStraightWithA(0, 0, 75);
    // バウタウン内、空を飛ぶでポケセンまで戻る
    flyingToSamePlace();
    // スタジアムへ、ジム戦開始
    pushButton(Button::PLUS, 300);
    tiltJoystick(100, 0, 0, 0, 500);
    tiltJoystick(30, -80, 0, 0, 5000);
    tiltJoystick(100, 0, 0, 0, 2000);
    tiltJoystick(0, -100, 0, 0, 2000);
    tiltJoystick(50, -100, 0, 0, 1000);
    runStraightWithA(0, -100, 150);
    runStraightWithA(0, 0, 150);
    // ジム戦1人目
    tiltJoystick(0, -100, 0, 0, 1500);
    runStraightWithA(100, 0, 215);
    // 赤ボタン1
    tiltJoystick(-50, 0, 0, 0, 1000);
    tiltJoystick(0, -100, 0, 0, 1000);
    pushButton(Button::A, 4000);
    // 黄ボタン1
    tiltJoystick(-100, 0, 0, 0, 1000);
    tiltJoystick(80, -80, 0, 0, 1000);
    tiltJoystick(-5, -100, 0, 0, 1000);
    pushButton(Button::A, 4000);
    // 再び赤ボタン1
    tiltJoystick(0, 100, 0, 0, 3000);
    tiltJoystick(100, 0, 0, 0, 1000);
    tiltJoystick(-50, 0, 0, 0, 900);
    tiltJoystick(0, -100, 0, 0, 1500);
    pushButton(Button::A, 4000);
    // 黄ボタン2手前のトレーナー
    tiltJoystick(100, 0, 0, 0, 2000);
    tiltJoystick(0, -100, 0, 0, 2000);
    tiltJoystick(-100, 0, 0, 0, 1000);
    tiltJoystick(80, -80, 0, 0, 4500);
    runStraightWithA(-100, 0, 300);
    pushButton(Button::B, 100, 20);
    // 黄ボタン2
    tiltJoystick(5, -100, 0, 0, 1000);
    pushButton(Button::A, 4000);
    // 赤ボタン2
    tiltJoystick(0, 100, 0, 0, 3000);
    tiltJoystick(100, 0, 0, 0, 2000);
    tiltJoystick(80, 80, 0, 0, 1000);
    tiltJoystick(10, -100, 0, 0, 2000);
    pushButton(Button::A, 4000);
    // 左へ移動
    tiltJoystick(-100, 0, 0, 0, 3000);
    tiltJoystick(0, -100, 0, 0, 1200);
    tiltJoystick(100, 0, 0, 0, 3000);
    tiltJoystick(-80, -80, 0, 0, 3000);
    tiltJoystick(-100, 5, 0, 0, 5500);
    // トレーナーに話しかけ
    runStraightWithA(0, 100, 145);
    runStraightWithA(0, 0, 150);
    pushButton(Button::B, 100, 20);
    // 黄ボタン3
    tiltJoystick(80, 80, 0, 0, 6500);
    tiltJoystick(-5, -100, 0, 0, 1000);
    pushButton(Button::A, 4000);
    // 赤ボタン3
    tiltJoystick(-100, 0, 0, 0, 5000);
    tiltJoystick(40, -80, 0, 0, 6500);
    tiltJoystick(-100, 0, 0, 0, 5000);
    tiltJoystick(80, -40, 0, 0, 1000);
    tiltJoystick(-5, -100, 0, 0, 1200);
    pushButton(Button::A, 4000);
    // 青ボタン
    tiltJoystick(0, 100, 0, 0, 2500);
    tiltJoystick(80, -80, 0, 0, 3000);
    tiltJoystick(80, 80, 0, 0, 2000);
    tiltJoystick(-5, -100, 0, 0, 1000);
    pushButton(Button::A, 4000);
    // ゴール、そのままルリナ戦
    tiltJoystick(-100, 0, 0, 0, 2000);
    tiltJoystick(80, 80, 0, 0, 3000);
    tiltJoystick(80, -80, 0, 0, 3000);
    tiltJoystick(-100, 0, 0, 0, 400);
    runStraightWithA(0, -100, 600);
    runStraightWithA(0, 0, 520);
    pushButton(Button::B, 100, 20);
    // スタジアムを出る前にルアーボールをもらう
    ballguyTalk();
    // オリーブ会話
    pushButton(Button::B, 100, 75);
}

// レストランで会話、第二鉱山まで
void seaFood(){
    // バウタウン内、空を飛ぶでポケセンまで戻る
    flyingToSamePlace();
    // レストランへ、会話50秒
    tiltJoystick(100, 0, 0, 0, 3500);
    tiltJoystick(70, -70, 0, 0, 1000);
    tiltJoystick(40, -80, 0, 0, 10000);
    pushButton(Button::B, 100, 225);
    // 外へ出てホップと会話、その後第二鉱山へ
    tiltJoystick(-40, 80, 0, 0, 1000);
    tiltJoystick(-80, 50, 0, 0, 5000);
    pushButton(Button::B, 100, 55);
    pushButton(Button::PLUS, 300);
    tiltJoystick(100, 0, 0, 0, 3000);
    tiltJoystick(10, 100, 0, 0, 4000);
    tiltJoystick(100, -20, 0, 0, 4000);
    delay(1000);
}

// 第二鉱山へ入ったところから、自転車乗ったまま
void secondMine(){
    // 右下移動、野生1を想定
    tiltJoystick(60, 90, 0, 0, 2000);
    // ビート戦、130秒、そのまま作業員男とも戦闘、40秒くらい
    runStraightWithA(90, 70, 1000);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    delay(300);
    // 自転車に乗り左下、下、作業員女（戦闘しないこともあり）、エール団に遭遇するまで60秒弱、そのままエール団と戦闘
    pushButton(Button::PLUS, 300);
    SwitchController().setStickTiltRatio(-60, 80, 0, 0);
    pushButton(Button::A, 100, 8);
    runStraightWithA(0, 100, 295);
    //　引き続きエール団戦withホップ、+140秒くらい
    //　そのまま、徒歩で左下へ進んで角で待機、途中野生1戦を想定
    runStraightWithA(-60, 80, 800);
    // 右に移動して野生×1（ズルッグ守る遅延あり）、トレーナー×1、100秒くらい？
    runStraightWithA(100, 0, 400);
    pushButton(Button::B, 100, 10);
    SwitchController().setStickTiltRatio(30, 80, 0, 0);
    pushButton(Button::B, 100, 10);
    pushButton(Button::A, 100, 300);
    // 左下移動、カブ会話、自動的に外へ、60秒くらい
    runStraightWithA(-80, 80, 145);
    // 近くの草むらからダゲキが突撃してくることあり、
    // 最後の方はXボタンも連打することでメニューを開閉する
    runStraightWithB(0, 0, 140);
    pushButton(Button::A, 100, 18);
    for(int i = 0; i < 12; i++){
      pushButton(Button::X, 50);
      pushButton(Button::B, 50);
    }
    pushButton(Button::B, 100, 5);
    // 空を飛ぶで目的地エンジンシティの左側ポケセンへ
    flyingTaxiOpen();
    pushButton(Button::Y, 300);
    pushHatButton(Hat::LEFT, 200);
    flyingTaxiSelect();
    delay(5500);
}

// エンジンシティ左からホテル1泊、そのままジムチャレンジ
void engineGym(){
    // 左のポケセンから、ホテルまで移動
    pushButton(Button::PLUS, 300);
    tiltJoystick(100, 0, 0, 0, 3500);
    tiltJoystick(60, -80, 0, 0, 2500);
    tiltJoystick(-60, -80, 0, 0, 1000);
    // マリィ戦2分
    pushButton(Button::A, 100, 680);
    pushButton(Button::B, 100, 20);
    // ホテルを出てスタジアムへ
    tiltJoystick(0, 100, 0, 0, 2000);
    tiltJoystick(-40, 80, 0, 0, 3000);
    delay(2000);
    pushButton(Button::PLUS, 300);
    tiltJoystick(60, 80, 0, 0, 1500);
    tiltJoystick(100, 0, 0, 0, 3800);
    // ホップ会話～受付、チャレンジ開始60秒
    runStraightWithA(0, -100, 250);
    runStraightWithA(0, 0, 100);
    // ジムでは捕獲は行わず、左下ロコンと上ヒトモシを倒す（捕まえない）
    // 右下はトレーナーのヤトウモリがねこだましをするため安定しない
    // 左下端の板に引っかかった後、右上
    tiltJoystick(-100, 0, 0, 0, 3000);
    tiltJoystick(0, 100, 0, 0, 3000);
    tiltJoystick(80, -80, 0, 0, 2500);
    // ロコン30秒
    pushButton(Button::A, 100, 150);
    pushButton(Button::B, 100, 20);
    for(int i=0; i<2; i++){
        // 左上端の板に引っかかった後、トレーナーにぶつかり、真横のヒトモシまで移動
        tiltJoystick(-40, -90, 0, 0, 2000);
        tiltJoystick(0, -100, 0, 0, 1000);
        tiltJoystick(80, -80, 0, 0, 2000);
        tiltJoystick(5, 50, 0, 0, 1500);
        tiltJoystick(100, 0, 0, 0, 1000);
        // ヒトモシ30秒
        pushButton(Button::A, 100, 150);
        pushButton(Button::B, 100, 20);
        //　左下引っ掛かりロコン
        tiltJoystick(-100, 0, 0, 0, 2000);
        tiltJoystick(0, 100, 0, 0, 3500);
        tiltJoystick(80, -80, 0, 0, 2500);
        // ロコン30秒
        pushButton(Button::A, 100, 150);
        pushButton(Button::B, 100, 20);
    }
    // カブ戦～ジム戦終わりまで400秒くらい、マルヤクデ乱2？
    runStraightWithA(0, -100, 600);
    runStraightWithA(0, 0, 900);
    // 終了後、スタジアムを出て見送り、ワイルドエリアまで2分くらい
    runStraightWithA(0, 100, 300);
    runStraightWithA(0, 0, 200);
    
}

// ワイルドエリアにて自爆、デスルーラによりナックルシティへ
void kudakeru_shigoto(){
    // メニューを開き、ボックス開く
    pokemonOpen();
    pushButton(Button::R, 2000);
    // 手持ちの1匹目（シェルダー）と、ボックスの左上（自爆ポケモン）入れ替え
    pushButton(Button::Y, 400);
    pushButton(Button::A, 400);
    pushHatButton(Hat::LEFT, 300);
    pushButton(Button::A, 400);
    pushButton(Button::B, 500, 10);
    // 上（少し左）にいるダストダスorイノムーorバンバドロと戦闘開始
    // ダストダス、イノムーはこちらを行動不能にする技がないので、相手の攻撃or自爆で
    // 確実に戦闘が始まるように、蛇行する
    tiltJoystick(-45, -100, 0, 0, 4200);
    tiltJoystick(-100, -40, 0, 0, 500);
    tiltJoystick(100, -40, 0, 0, 1000);
    tiltJoystick(-100, -40, 0, 0, 1000);
    tiltJoystick(100, -40, 0, 0, 1000);
    tiltJoystick(-100, -40, 0, 0, 1000);
    tiltJoystick(100, -40, 0, 0, 1000);
    tiltJoystick(0, 100, 0, 0, 2300);
    tiltJoystick(-100, -40, 0, 0, 1000);
    tiltJoystick(100, -40, 0, 0, 1000);
    tiltJoystick(-100, -40, 0, 0, 1000);
    tiltJoystick(100, -40, 0, 0, 1000);
    tiltJoystick(0, 100, 0, 0, 2000);
    tiltJoystick(-100, -40, 0, 0, 2000);
    tiltJoystick(100, -40, 0, 0, 1000);
    tiltJoystick(-100, -40, 0, 0, 1000);
    tiltJoystick(100, -40, 0, 0, 1000);
    tiltJoystick(-100, -40, 0, 0, 1000);
    tiltJoystick(100, -40, 0, 0, 1000);
    tiltJoystick(0, -100, 0, 0, 1000);
    // 戦闘で「たたかう」表示まで待機、長めに20秒
    pushButton(Button::B, 400, 40);
    // コマンド1番上「たたかう」＞「自爆」
    pushHatButtonContinuous(Hat::UP, 1500);
    pushButton(Button::A, 400, 3);
    // 全滅メッセージまで待機
    delay(30000);
    // 全滅
    pushButton(Button::A, 3000);
    pushButton(Button::A, 12000);
    // ワイルドエリア入り口にて回復
    pushButton(Button::A, 4000);
    // 送り先として2番目の選択肢
    pushButton(Button::A, 2000);
    pushHatButton(Hat::DOWN, 300);
    pushButton(Button::A, 100, 50);
    // 真後ろへ進む
    tiltJoystick(0, 100, 0, 0, 2000);
    // バッジを見せて入り口開放
    pushButton(Button::A, 100, 170);
    // ナックルシティへ入る
    tiltJoystick(-30, -100, 0, 0, 2000);
    pushButton(Button::B, 100, 70);
}


// 初めてのナックルシティ、委員長との会話、エネルギー講座
void firstKnuckle(){
    // 正面へ進み、委員長たちの会話
    tiltJoystick(0, -100, 0, 0, 2500);
    pushButton(Button::B, 100, 240);
    // スタジアムへ進み、委員長のエネルギー講座
    tiltJoystick(0, -100, 0, 0, 10000);
    pushButton(Button::B, 100, 150);
    // ボールガイからレベルボールをもらう
    tiltJoystick(-30, -90, 0, 0, 1500);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    delay(500);
    pushButton(Button::A, 100, 15);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::B, 100, 45);
    // スタジアムを出る
    tiltJoystick(10, 100, 0, 0, 8000);
    delay(5000);
    
}

// 宝物庫へ進む前に、ワイルドエリア内、ハシノマ原っぱの水の石を回収する
// ワイルドエリア内は草むらを通ることもあるので、全体的に時間長めに設定
void obtainwaterStone(){
     // メニューを開き、ボックス開く
    pokemonOpen();
    pushButton(Button::R, 2000);
    // 手持ちの1匹目（自爆ポケモン）と、ボックスの左上（シェルダー）入れ替え
    pushButton(Button::Y, 400);
    pushButton(Button::A, 400);
    pushHatButton(Hat::LEFT, 300);
    pushButton(Button::A, 400);
    pushButton(Button::B, 500, 10);
    // 空を飛ぶでワイルドエリア内ナックル丘陵へ
    flyingTaxiOpen();
    pushHatButton(Hat::RIGHT_DOWN, 300);
    flyingTaxiSelect();
    delay(5500);
    // 自転車に乗り、巨人の鏡池を経由しつつハシノマ原っぱの預け屋へ
    // 左を向き、固定シンボルアーマーガア手前まで
    pushButton(Button::PLUS, 300);
    SwitchController().setStickTiltRatio(0, 0, -50, 0);
    delay(1820);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    delay(8700);
    // 右を向き、岩や池にぶつかりつつ、草むらのない壁まで
    SwitchController().setStickTiltRatio(0, -100, 100, 0);
    delay(600);
    runStraightWithA(0, -100, 145);
    // 角度を調整し、ストーンズ原野の岩のアーチまで
    // 草むらを通るため、戦闘回数を多めに想定
    SwitchController().setStickTiltRatio(0, 0, 50, 0);
    delay(1100);
    SwitchController().setStickTiltRatio(80, -80, 0, 0);
    delay(1500);
    runStraightWithA(0, -100, 1050);
    // 角度を調整し、左上へ進み、預け屋建物左側へ
    SwitchController().setStickTiltRatio(50, 0, 0, 0);
    delay(2000);
    SwitchController().setStickTiltRatio(-40, -100, 0, 0);
    delay(1000);
    SwitchController().setStickTiltRatio(0, 0, -50, 0);
    delay(1500);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    delay(800);
    SwitchController().setStickTiltRatio(0, 0, -50, 0);
    delay(1300);
    runStraightWithA(0, -100, 450);
    // ワットショップに話しかけた場合のためにBキャンセル
    pushButton(Button::B, 100, 50);
    // すぐ隣にいる預け屋に話しかけ、空を飛ぶを可能にする
    SwitchController().setStickTiltRatio(100, 0, 0, 0);
    delay(200);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    pushButton(Button::A, 100, 4);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::B, 100, 50);
    // 空を飛ぶでワイルドエリア内ハシノマ原っぱへ
    flyingToSamePlace();
    // まっすぐ進み、壁にぶつかるまで、30秒強＋野生戦闘
    runStraightWithA(0, -100, 750);
    // 左上へ進み、水の石があるところまで移動
    SwitchController().setStickTiltRatio(0, 0, -100, 0);
    delay(500);
    runStraightWithA(0, -100, 595);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    // 自転車から降りた後、真後ろへ進んで水の石をゲット
    delay(500);
    pushButton(Button::PLUS, 300);
    SwitchController().setStickTiltRatio(0, 100, 0, 0);
    pushButton(Button::A, 100, 10);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::B, 100, 15);
    // 空を飛ぶでナックルシティへ
    flyingToDirection();
}

// ナックルシティへ戻り、水の石を使用してパルシェンへ進化
void evolvedByStone(){
    // バッグを開く
    bagOpen();
    // 道具ポケットを開き、1番下の水の石を使用
    pushHatButton(Hat::RIGHT, 400, 4);
    pushHatButton(Hat::UP, 400);
    pushButton(Button::A, 100, 10);
    pushButton(Button::B, 100, 120);
}

// ダンデと会話、その後宝物庫でソニアと会話
void treasureHouse(){
    // ナックル中央ポケセンから左へ、ダンデと会話30秒弱
    tiltJoystick(-100, 0, 0, 0, 3000);
    pushButton(Button::A, 100, 120);
    // ナックルシティ内で空を飛ぶ、左側ポケセンへ
    flyingTaxiOpen();
    pushHatButton(Hat::LEFT, 200);
    flyingTaxiSelect();
    // 左へ進み、キバナ会話30秒、宝物庫へ
    tiltJoystick(-100, 0, 0, 0, 5000);
    pushButton(Button::A, 100, 160);
    tiltJoystick(-70, -70, 0, 0, 400);
    tiltJoystick(-100, 0, 0, 0, 5000);
    tiltJoystick(0, 100, 0, 0, 4000);
    tiltJoystick(100, 0, 0, 0, 6000);
    // 技マシン29甘える
    SwitchController().setStickTiltRatio(40, -40, 0, 0);
    pushButton(Button::A, 100, 15);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::B, 100, 30);
    // 扉をくぐり、ソニア会話35秒
    tiltJoystick(-100, 0, 0, 0, 1500);
    tiltJoystick(0, -100, 0, 0, 7000);
    pushButton(Button::A, 100, 150);
    pushButton(Button::B, 100, 40);
    // 宝物庫を出る
    tiltJoystick(0, 100, 0, 0, 7000);
    tiltJoystick(-100, 0, 0, 0, 3000);
    tiltJoystick(40, -90, 0, 0, 1000);
    tiltJoystick(90, -40, 0, 0, 2500);
    tiltJoystick(100, 0, 0, 0, 6000);
    pushButton(Button::B, 100, 50);
    tiltJoystick(0, 100, 0, 0, 3000);
    delay(3000);
}

// 宝物庫を出たところから左へ、6番道路
void goToRoute6(){
    // 自転車に乗り、左の6番道路へ、そのままエール団、お姉さん、野生と連続で戦闘
    pushButton(Button::PLUS, 300);
    SwitchController().setStickTiltRatio(-100, 10, 0, 0);
    delay(5000);
    pushButton(Button::A, 100, 2000);
    pushButton(Button::B, 100, 20);
    // 左のはしごを上り、バックパッカー戦闘
    runStraightWithA(70, -90, 300);
    // 戦闘後、右下へ進む
    SwitchController().setStickTiltRatio(70, 70, 0, 0);
    delay(500);
    pushHatButtonContinuous(Hat::UP, 1000);
    pushButton(Button::A, 100, 300);
    // 戦闘後、上へ進み、トレーナー2人、2分強
    runStraightWithA(0, -100, 150);
    runStraightWithA(-70, -70, 150);
    runStraightWithA(70, -70, 150);
    runStraightWithA(0, -100, 300);
    pushButton(Button::B, 100, 20);
    runStraightWithA(-40, -100, 250);
    pushButton(Button::B, 100, 20);
    // 左へ進み、ラテラルタウンへ、会話はなくムービースキップ
    tiltJoystick(-100, 0, 0, 0, 6000);
    tiltJoystick(-70, -70, 0, 0, 3000);
    delay(3000);
}

// ラテラルタウン到着後、空を飛ぶ、ホップ戦、ジムチャレンジ
// ソード版の格闘ジムを元に作成、シールドでは見切りなど遅延技がないかわりに、くだけるよろい、のろわれボディなどの遅延あり
void lateralGym(){
    // ラテラルタウン内で空を飛ぶ、
    flyingToSamePlace();
    // ホップのところへ
    tiltJoystick(-90, 60, 0, 0, 800);
    tiltJoystick(-100, 0, 0, 0, 3000);
    tiltJoystick(0, -100, 0, 0, 4000);
    // ホップ戦、2分半くらい
    pushButton(Button::A, 100, 775);
    //スタジアムへ、そのままジムチャレンジ開始
    tiltJoystick(30, -100, 0, 0, 2000);
    runStraightWithA(0, -100, 200);
    runStraightWithA(0, 0, 175);
    // ピンボール1
    tiltJoystick(0, -100, 0, 0, 7000);
    delay(500);
    guruGuru(20, 2000, 1);
    guruGuru(20, 2000, -1);
    delay(2000);
    guruGuru(20, 2000, -1);
    delay(1000);
    guruGuru(20, 1000, 1);
    delay(5000);
    // トレーナー1、ソードは2匹、シールドは3匹
    runStraightWithA(0, 100, 200);
    runStraightWithA(0, 0, 240);
    // ピンボール2
    tiltJoystick(0, 100, 0, 0, 7500);
    guruGuru(20, 10500, 1);
    guruGuru(20, 10000, -1);
    // トレーナー2、みきりによる遅延あり
    runStraightWithA(0, 100, 100);
    runStraightWithA(0, 0, 220);
    // ピンボール3
    tiltJoystick(0, 100, 0, 0, 9700);
    guruGuru(20, 13000, -1);
    guruGuru(20, 13000, 1);
    // トレーナー3、そのままリーダーのサイトウ戦、5分くらい？
    // サイトウのネギはみきり持ち
    runStraightWithA(0, -100, 650);
    runStraightWithA(0, 0, 900);
    // スタジアムを出る前にヘビーボールをもらう
    ballguyTalk();
    // ソニアと会話、爆発
    pushButton(Button::A, 100, 140);
    // 自転車に乗り、左の6番道路へ、そのままエール団、お姉さん、野生と連続で戦闘
    pushButton(Button::PLUS, 300);
    tiltJoystick(-100, 0, 0, 0, 3000);
    tiltJoystick(0, -100, 0, 0, 500);
    tiltJoystick(-70, -70, 0, 0, 4500);
    tiltJoystick(70, -70, 0, 0, 7000);
    // ビート戦～遺跡発掘、約4分
    runStraightWithA(0, -100, 590);
    runStraightWithA(0, 0, 600);
}

// ビート戦後にアラベスクの森へ
void arabesqueForest(){
    // ラテラルタウン内で空を飛ぶ、
    flyingToSamePlace();
    // アラベスクの森へ、戦闘なし
    tiltJoystick(-90, 60, 0, 0, 800);
    tiltJoystick(-100, 0, 0, 0, 3000);
    tiltJoystick(0, -100, 0, 0, 3000);
    tiltJoystick(30, -100, 0, 0, 2000);
    tiltJoystick(80, -80, 0, 0, 3000);
    pushButton(Button::PLUS, 300);
    tiltJoystick(80, -80, 0, 0, 4000);
    tiltJoystick(100, 0, 0, 0, 300);
    tiltJoystick(0, -100, 0, 0, 4000);
    delay(4000);
    tiltJoystick(80, -80, 0, 0, 4000);
    tiltJoystick(0, -100, 0, 0, 1000);
    tiltJoystick(80, -80, 0, 0, 1500);
    tiltJoystick(0, 100, 0, 0, 500);
    tiltJoystick(-80, 80, 0, 0, 800);
    tiltJoystick(80, 80, 0, 0, 1000);
    tiltJoystick(80, -80, 0, 0, 1000);
    tiltJoystick(0, -100, 0, 0, 1000);
    tiltJoystick(80, -80, 0, 0, 1000);
    tiltJoystick(-80, -80, 0, 0, 1500);
    tiltJoystick(0, -100, 0, 0, 5000);
    tiltJoystick(100, 0, 0, 0, 1000);
    tiltJoystick(20, -100, 0, 0, 7000);
    delay(3000);
}

// アラベスクジムチャレンジ
// 森を出た時に、自転車からは下りている
void arabesqueGym(){
    // アラベスクタウン内で空を飛ぶ
    flyingToSamePlace();
    // スタジアムへ
    pushButton(Button::PLUS, 300);
    tiltJoystick(-100, 0, 0, 0, 1000);
    tiltJoystick(-80, -80, 0, 0, 1000);
    tiltJoystick(90, -40, 0, 0, 1000);
    tiltJoystick(100, 0, 0, 0, 2500);
    tiltJoystick(0, -100, 0, 0, 5000);
    tiltJoystick(80, -80, 0, 0, 500);
    tiltJoystick(40, -80, 0, 0, 1500);
    // マリィと会話～ジム受付
    runStraightWithA(0, -100, 250);
    // 受付後、そのまま右へ進んでオーディション
    // クイズは2，3問目を外しながらA連打、
    // 3人目のギモーが先制ねこだまし、おだてるで遅延のため時間多め
    runStraightWithA(100, 0, 1450);
    pushButton(Button::B, 100, 40);
    // ポプラ戦、クイズは1，2問目を外すがS-2でも先制できるので問題なし
    runStraightWithA(0, -100, 400);
    runStraightWithA(0, 0, 920);
    // スタジアムを出る前にラブラブボールをもらう
    ballguyTalk();
    // 一緒にはいかず、空を飛ぶでナックルシティ中央ポケセンまで
    pushButton(Button::B, 100, 100);
    flyingToDirection();
}

// ナックルシティに戻り、ピンク堕ち
// ナックル中央ポケセンから
void secondKnuckle(){
    // ポケセンから右へ「ピンク！おめでとう！」
    tiltJoystick(100, 0, 0, 0, 2000);
    pushButton(Button::A, 100, 300);
    // 右へ、ソニア会話
    pushButton(Button::PLUS, 300);
    tiltJoystick(100, 0, 0, 0, 7000);
    tiltJoystick(0, -100, 0, 0, 500);
    tiltJoystick(70, -100, 0, 0, 5000);
    pushButton(Button::A, 100, 235);
    // さらに右上、ホップ会話
    tiltJoystick(80, -80, 0, 0, 4000);
    pushButton(Button::A, 100, 50);
    // 自転車に乗り右へ、街を出て7番道路、ホップ戦
    pushButton(Button::PLUS, 300);
    runStraightWithA(100, 0, 450);
    runStraightWithA(0, 0, 490);
}

// 7,8番道路、ホップ戦後から。全体的に野生戦多め
void goToRoute78(){
    // 右上へひたすら進む、野生2、タクシー1と戦闘を想定
    SwitchController().setStickTiltRatio(100, 0, 0, 0);
    delay(500);
    pushButton(Button::PLUS, 300);
    SwitchController().setStickTiltRatio(80, -80, 0, 0);
    delay(3000);
    SwitchController().setStickTiltRatio(100, 30, 0, 0);
    delay(800);
    runStraightWithA(50, -100, 650);
    // 8番道路入り口付近の木箱より左へ、野生1
    SwitchController().setStickTiltRatio(-100, 0, 0, 0);
    delay(3000);
    runStraightWithA(80, -80, 195);
    // はしご下りる
    runStraightWithA(-50, 90, 195);
    // 右へ進んでドクター
    runStraightWithA(100, 0, 295);
    // 階段上る
    SwitchController().setStickTiltRatio(-80, -80, 0, 0);
    delay(1500);
    // 草むらで野生1、その後左上へ
    SwitchController().setStickTiltRatio(50, -90, 0, 0);
    pushButton(Button::A, 100, 180);
    runStraightWithA(-50, -90, 350);
    // はしご上る、野生は無し
    SwitchController().setStickTiltRatio(50, -90, 0, 0);
    delay(10000);
    // はしご下りる、野生1
    runStraightWithA(0, 100, 190);
    // はしご上り下り連続、右端のくぼみへ、はしご上る前、下りた後に野生1ずつ
    runStraightWithA(90, -70, 450);
    // 左上、段差のそばまで、野生2
    runStraightWithA(-60, -90, 450);
    // 段差飛び降り～左下
    SwitchController().setStickTiltRatio(-80, 80, 0, 0);
    delay(500);
    SwitchController().setStickTiltRatio(-100, 0, 0, 0);
    delay(1500);
    SwitchController().setStickTiltRatio(0, 100, 0, 0);
    delay(1500);
    // 左へ進み、元気の塊まで、野生2
    runStraightWithA(-100, 0, 450);
    // 右上、野生2、バックパッカーに見つかる恐れあるので長め
    SwitchController().setStickTiltRatio(90, -90, 0, 0);
    delay(500);
    runStraightWithA(50, -90, 700);
    // 右、草むらを出る、野生1+待機中によって来る
    runStraightWithA(90, 40, 200);
    // 上、ダンサーと戦闘
    runStraightWithA(0, -100, 400);
    // 右下、はしご下りつつ進む、タイレーツ戦闘おそれ
    // タイレーツはこらえるによる遅延あり
    runStraightWithA(70, 90, 450);
    // 上、はしご手前まで、タイレーツ戦闘おそれ
    runStraightWithA(0, -100, 400);
    // 上、はしご上り、そのまま降雪エリアへ
    SwitchController().setStickTiltRatio(-80, -80, 0, 0);
    delay(1000);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    delay(12000);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    delay(7000);
    // 降雪エリア、上側を通りトレーナーを避けつつ進む
    pushButton(Button::PLUS, 300);
    runStraightWithA(50, -90, 320);
    // キルクスタウンへ
    SwitchController().setStickTiltRatio(-100, 0, 0, 0);
    delay(1000);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    delay(3000);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    // キルクスタウン内で空を飛ぶ
    flyingToSamePlace();
    
}

// キルクスジムチャレンジ
void circusGym(){
    // ポケセンからスタジアムまで移動
    tiltJoystick(100, 0, 0, 0, 1500);
    tiltJoystick(0, -100, 0, 0, 7000);
    tiltJoystick(100, 0, 0, 0, 1200);
    tiltJoystick(0, -100, 0, 0, 7000);
    tiltJoystick(100, 0, 0, 0, 3000);
    tiltJoystick(40, -80, 0, 0, 3000);
    // スタジアムに入り、ホップ会話～受付
    runStraightWithA(0, -100, 200);
    runStraightWithA(0, 0, 170);
    // 落とし穴1
    // 右側の岩にぶつかる
    tiltJoystick(0, -100, 0, 0, 3000);
    tiltJoystick(80, -80, 0, 0, 5000);
    // トレーナー戦闘、戦闘後、左の壁にぶつかる
    tiltJoystick(-100, 0, 0, 0, 2500);
    runStraightWithA(0, -100, 100);
    runStraightWithA(0, 0, 140);
    pushButton(Button::B, 100, 20);
    // ゴールまで
    tiltJoystick(-80, -80, 0, 0, 300);
    tiltJoystick(-100, 0, 0, 0, 6000);
    tiltJoystick(0, -100, 0, 0, 1500);
    tiltJoystick(80, -80, 0, 0, 6000);
    delay(8000);
    // 落とし穴2
    // 左の壁沿いに進む
    tiltJoystick(0, -100, 0, 0, 6700);
    tiltJoystick(-90, 40, 0, 0, 1500);
    tiltJoystick(-100, 0, 0, 0, 4000);
    tiltJoystick(-80, -80, 0, 0, 1500);
    tiltJoystick(0, -100, 0, 0, 1000);
    tiltJoystick(80, -80, 0, 0, 3000);
    tiltJoystick(0, -100, 0, 0, 500);
    // トレーナー1、シールド版はパルシェンのため確2？
    pushButton(Button::A, 100, 400);
    pushButton(Button::B, 100, 20);
    tiltJoystick(40, -90, 0, 0, 1500);
    // トレーナー2、左から近づき話しかける
    runStraightWithA(100, -2, 270);
    pushButton(Button::B, 100, 25);
    // トレーナーを避けつつ右へ
    tiltJoystick(0, 100, 0, 0, 800);
    tiltJoystick(100, 0, 0, 0, 1000);
    tiltJoystick(0, -100, 0, 0, 600);
    tiltJoystick(100, 0, 0, 0, 2000);
    tiltJoystick(0, -100, 0, 0, 2000);
    tiltJoystick(-100, 0, 0, 0, 2500);
    tiltJoystick(-80, -80, 0, 0, 5000);
    delay(10000);
    // 落とし穴3
    // 左方向へ
    tiltJoystick(0, -100, 0, 0, 4000);
    tiltJoystick(-80, -80, 0, 0, 7000);
    tiltJoystick(80, -80, 0, 0, 1000);
    // 正面のトレーナーに話しかける、戦闘後は左角へ
    // シールドではクレベース、確2？
    runStraightWithA(0, -100, 50);
    SwitchController().setStickTiltRatio(100, 0, 0, 0);
    pushButton(Button::A, 100, 30);
    runStraightWithA(-80, -80, 320);
    pushButton(Button::B, 100, 25);
    // 右方向へ進む
    tiltJoystick(0, 100, 0, 0, 700);
    tiltJoystick(100, 0, 0, 0, 2000);
    tiltJoystick(0, -100, 0, 0, 1100);
    tiltJoystick(60, -80, 0, 0, 3300);
    tiltJoystick(-80, -80, 0, 0, 6000);
    tiltJoystick(80, -80, 0, 0, 4000);
    delay(10000);
    // マクワorメロン戦、特にメロンのラプラスは確3？4？
    runStraightWithA(0, -100, 450);
    runStraightWithA(0, 0, 1350);
    // スタジアムを出る前にムーンボールをもらう
    ballguyTalk();
    // ソニア会話
    pushButton(Button::A, 100, 100);
    // キルクスタウン内で空を飛ぶ
    flyingToSamePlace();
    // ポケセンすぐ裏の家にて、技マシンをもらう
    // 凍える風（岩石封じ）の技マシン、後でこご風を覚えさえるため
    tiltJoystick(100, 0, 0, 0, 1200);
    tiltJoystick(-50, -90, 0, 0, 4500);
    tiltJoystick(-90, 50, 0, 0, 1000);
    SwitchController().setStickTiltRatio(80, -80, 0, 0);
    pushButton(Button::A, 100, 25);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::B, 100, 40);
    // 家を出る
    tiltJoystick(-80, 80, 0, 0, 2000);
    delay(3000);
    // 上方向のレストランへ向かう
    tiltJoystick(100, 0, 0, 0, 800);
    tiltJoystick(0, -100, 0, 0, 6000);
    tiltJoystick(-100, 0, 0, 0, 2000);
    tiltJoystick(30, -90, 0, 0, 2000);
    // レストランにて会話60秒くらい
    pushButton(Button::A, 100, 250);
    // 店を出たらそのままホップ戦3分くらい
    runStraightWithA(-80, 80, 350);
    runStraightWithA(0, 0, 700);
    
}

// 広角レンズを入手し、ボックス内ダブルバトル用ポケモンに持たせる
void obtainLens(){
    // ホップ戦の後に下へ進み、ホテルイオニア右へ入る
    tiltJoystick(0, 100, 0, 0, 7500);
    tiltJoystick(90, -30, 0, 0, 3000);
    tiltJoystick(0, -100, 0, 0, 2000);
    tiltJoystick(80, -80, 0, 0, 1000);
    // ホテル左奥エレベーターへ
    tiltJoystick(0, -100, 0, 0, 4000);
    runStraightWithA(80, -80, 5);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    delay(4000);
    // エレベーターのすぐ左の部屋へ、探偵イベント
    tiltJoystick(-100, 20, 0, 0, 1800);
    tiltJoystick(-40, -90, 0, 0, 2000);
    tiltJoystick(0, -100, 0, 0, 500);
    pushButton(Button::A, 100, 55);
    tiltJoystick(100, 0, 0, 0, 500);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    pushButton(Button::A, 100, 30);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::B, 100, 70);
    // 3人に聞き込み
    tiltJoystick(100, 0, 0, 0, 1000);
    tiltJoystick(80, 80, 0, 0, 300);
    pushButton(Button::A, 100, 3);
    pushButton(Button::B, 100, 22);
    tiltJoystick(80, -80, 0, 0, 300);
    pushButton(Button::A, 100, 3);
    pushButton(Button::B, 100, 22);
    tiltJoystick(0, 100, 0, 0, 1300);
    tiltJoystick(80, -80, 0, 0, 800);
    pushButton(Button::A, 100, 3);
    pushButton(Button::B, 100, 72);
    // ホシガリス話しかけ、犯人、広角レンズもらう
    tiltJoystick(-100, 0, 0, 0, 500);
    SwitchController().setStickTiltRatio(0, -100, 0, 0);
    pushButton(Button::A, 100, 50);
    SwitchController().setStickTiltRatio(-100, 0, 0, 0);
    pushButton(Button::A, 100, 150);
    runStraightWithB(0, 0, 130);
    // ホテルを出る
    tiltJoystick(-40, 90, 0, 0, 2500);
    delay(3000);
    tiltJoystick(100, 20, 0, 0, 1500);
    SwitchController().setStickTiltRatio(80, -80, 0, 0);
    pushButton(Button::A, 100, 10);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    delay(3000);
    tiltJoystick(-80, 90, 0, 0, 1300);
    tiltJoystick(0, 100, 0, 0, 3000);
    delay(2000);
    // 広角レンズをボックス左上（自爆ポケモン）にもたせる
    // メニューを開き、ボックス開く
    pokemonOpen();
    pushButton(Button::R, 2000);
    // ボックス左下のポケモンに道具をもたせる
    pushButton(Button::Y, 400);
    pushButton(Button::X, 400);
    pushHatButton(Hat::DOWN, 200, 4);
    pushButton(Button::A, 2500);
    // 念のためカーソルを左に戻してから、道具ポケットへ、一番下の広角レンズを選択
    pushHatButtonContinuous(Hat::LEFT, 2000);
    pushHatButton(Hat::RIGHT, 400, 4);
    pushHatButtonContinuous(Hat::DOWN, 2000);
    pushButton(Button::A, 500, 3);
    pushButton(Button::B, 500, 16);
    
}

// 広角レンズ入手後、キルクス右下から9番道路へ
void goToRoute9(){
    // ホテルを出たところからキルクス出口へ
    tiltJoystick(0, 100, 0, 0, 1800);
    tiltJoystick(100, 0, 0, 0, 2800);
    tiltJoystick(0, 100, 0, 0, 2000);
    delay(500);
    // 自転車に乗って真下へ、草むら近くの壁まで
    pushButton(Button::PLUS, 300);
    tiltJoystick(0, 100, 0, 0, 9000);
    // 少し右へずれてからさらに下へ、ダンサー、エール団と戦闘、2分半くらい？
    // エール団との戦闘後は自転車から下りた状態で止まる
    tiltJoystick(80, 80, 0, 0, 800);
    runStraightWithA(0, 100, 845);
    // 徒歩で右端へ位置調整した後、自転車で左下へ進み、わずかに見える陸地？を経由して流氷にぶつかるまで
    // 野生と3戦程度を想定
    tiltJoystick(100, 0, 0, 0, 1500);
    pushButton(Button::PLUS, 300);
    runStraightWithA(-90, 70, 595);
    // 下へ進み、流氷の通路を抜け、オトスパスなど2戦程度
    runStraightWithA(25, 90, 450);
    // 少し右へずれて、右下へ進み、岩の通路を抜け、オトスパスなど3戦程度
    runStraightWithA(80, 80, 700);
    // 岩に引っかかったところから下へ進み、地上へ上がりつつスパイクタウン脇まで進む
    // 野生3くらい？
    runStraightWithA(-20, 90, 700);
    // 右へ進み、スパイクタウンシャッター前でモブと会話、10秒くらい
    runStraightWithA(100, 0, 45);
    runStraightWithB(0, 0, 50);
}

// スパイクタウンシャッター前に着いた後、こだわりハチマキ入手のため寄り道
void obtainBand(){
    // スパイクタウンに空を飛ぶができるようになったところで、2番道路博士の家へ空を飛ぶ
    // マリィ戦でレパルダスが先制いちゃもんを使うため、悪あがきを使う恐れ
    // 悪あがきで倒せるように、こだわりハチマキを回収する
    flyingTaxiOpen();
    pushButton(Button::R, 400);
    pushHatButton(Hat::RIGHT, 500, 2);
    flyingTaxiSelect();
    delay(5500);
    // 徒歩で下方向
    tiltJoystick(0, 100, 0, 0, 2500);
    delay(200);
    // 自転車で右下へ、野生2戦程度しつつ川を進んでこだわりハチマキまで
    pushButton(Button::PLUS, 300);
    runStraightWithA(90, 70, 395);
    // 自転車を降りる
    pushButton(Button::PLUS, 300);
    // 下方向を向いてアイテムゲット
    SwitchController().setStickTiltRatio(-60, 80, 0, 0);
    pushButton(Button::A, 100, 25);
    runStraightWithB(0, 0, 25);
    //　こだわりハチマキをパルシェンに持たせる
    // メニューを開き、パルシェンを選択してもちもの
    pokemonOpen();
    pushButton(Button::A, 500);
    pushHatButton(Hat::DOWN, 300, 2);
    pushButton(Button::A, 500);
    pushButton(Button::A, 2000);
    // 念のためカーソルを左に戻してから、道具ポケットへ、一番下のこだわりハチマキを選択
    pushHatButtonContinuous(Hat::LEFT, 2000);
    pushHatButton(Hat::RIGHT, 400, 4);
    pushHatButtonContinuous(Hat::DOWN, 2000);
    pushButton(Button::A, 500, 3);
    pushButton(Button::B, 500, 14);
    // スパイクタウンに戻る前に、適当なポケセンで回復
    // スパイクジムチャレンジ開始時に回復がなく、氷柱針PPがギリギリの可能性があるため
    flyingTaxiOpen();
    pushButton(Button::R, 300, 2);
    flyingTaxiSelect();
    delay(5500);
    // ポケセンに入りジョーイさん話しかけ、回復
    runStraightWithA(0, -100, 45);
    runStraightWithB(0, 100, 60);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    delay(1000);
    // 空を飛ぶでスパイクタウンはずれまで戻る
    flyingTaxiOpen();
    pushButton(Button::Y, 300);
    SwitchController().pressHatButton(Hat::LEFT);
    delay(80);
    SwitchController().releaseHatButton();
    delay(200);
    flyingTaxiSelect();
    delay(5500);
    // 下の草むらに向かって移動
    tiltJoystick(-60, 90, 0, 0, 1000);
    // 下方向、裏道へ、野生1戦
    runStraightWithA(0, 100, 45);
    // そのまま右下へ、さらに2戦
    runStraightWithA(80, 80, 500);
    // 右上、野生1戦くらい+マリィ戦、計3分くらい
    // 戦闘後はそのままスパイクタウンに入り、ポケセン右の壁にぶつかる
    runStraightWithA(50, -90, 900);
    pushButton(Button::B, 100, 20);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
}

// スパイクジムチャレンジ、ダブルバトルを含むため手持ち2匹必須
// 2匹目のポケモンはあらかじめボックス左下に配置（キルクスで広角レンズ所持）
// 2匹目の条件として、言うことを聞くLv80以下の簡単にはやられないポケモン
// Sはパルシェン以下、Lv45キュウコンを超える109以上
// 技はPP10以上の技1つのみ、範囲技、命中100技を推奨
// ハチマキを持たせるため、物理技の方がよい
// 今回はLv60オノノクス（ワイルドエリア産）にワイドブレイカーを覚えさせる
void spikeGym(){
    // メニューを開き、ボックス開く
    pokemonOpen();
    pushButton(Button::R, 2000);
    // ボックス左下のポケモン（オノノクス）を手持ちにくわえる
    pushButton(Button::Y, 400);
    pushHatButton(Hat::DOWN, 200, 4);
    pushButton(Button::A, 400);
    pushHatButton(Hat::LEFT, 300);
    pushButton(Button::A, 400);
    // ボックスを閉じる
    pushButton(Button::B, 500, 10);
    // ジムチャレンジ開始
    tiltJoystick(0, 100, 0, 0, 1800);
    // 右へ進み続け、受付～1人目戦闘～突き当りバリヤードまで
    runStraightWithA(100, 0, 380);
    pushButton(Button::B, 100, 10);
    // 左へ戻ろうとすると2人目戦闘
    runStraightWithA(-100, 0, 100);
    // 右へ進み続け、3人目戦闘～突き当りバリヤードまで
    runStraightWithA(100, 0, 520);
    pushButton(Button::B, 100, 10);
    // 左へ戻ろうとすると4人目戦闘
    runStraightWithA(-100, 0, 100);
    // 右へ進み続け、突き当り障害物まで
    runStraightWithA(100, 0, 300);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    // ここでダブルバトルのための準備
    // メニューを開き、手持ちポケモンを開く
    pokemonOpen();
    // パルシェンに凍える風を覚えさせる
    pushButton(Button::A, 400);
    pushHatButton(Hat::DOWN, 200, 3);
    pushButton(Button::A, 400, 2);
    delay(1500);
    // 念のためカーソルを左に戻してから、技マシンポケットへ、番号順に並び替え
    pushHatButtonContinuous(Hat::LEFT, 2000);
    pushHatButton(Hat::RIGHT, 400, 5);
    pushButton(Button::X, 400);
    pushButton(Button::A, 700, 2);
    delay(500);
    // 上から2番目、MT27を使用
    pushHatButton(Hat::DOWN, 300);
    pushButton(Button::A, 400, 2);
    delay(500);
    pushButton(Button::A, 2000);
    pushButton(Button::B, 500, 2);
    delay(1500);
    // 技を入れ替え、凍える風を1番上に
    pushButton(Button::A, 500);
    pushButton(Button::A, 2000);
    pushHatButton(Hat::RIGHT, 400, 2);
    pushButton(Button::A, 400, 2);
    pushHatButton(Hat::DOWN, 400);
    pushButton(Button::A, 400);
    pushButton(Button::B, 500, 2);
    delay(1500);
    // パルシェンとオノノクスの持ち物を入れ替え
    // パルシェンに広角レンズ（こごえるかぜ必中）、オノノクスにこだわりハチマキ
    pushButton(Button::X, 400, 2);
    pushHatButton(Hat::DOWN, 300);
    pushButton(Button::X, 400);
    // メニューを閉じる
    pushButton(Button::B, 500, 10);
    // ジム進行を再開、右上の通路へ
    tiltJoystick(-80, -70, 0, 0, 3000);
    tiltJoystick(100, 0, 0, 0, 2500);
    // ダブルバトル、終了後しばらく待機
    pushButton(Button::A, 100, 350);
    // 右下へ進み、マリィと会話、その後右に進みネズ戦を開始、まだ攻撃はしない
    tiltJoystick(90, 50, 0, 0, 4000);
    runStraightWithB(100, 0, 445);
    runStraightWithB(0, 0, 100);
    // 攻撃開始、カーソルを2番目の技である氷柱針へ
    // ジムチャレンジ終了、街の外でダンデと会話まで
    pushButton(Button::A, 400);
    pushHatButton(Hat::DOWN, 400);
    pushButton(Button::A, 100, 1200);
    
}

// ルートナイントンネル
void goToTunnel(){
    // ジム戦後、左へ向かいトンネルへ
    tiltJoystick(-90, 30, 0, 0, 8500);
    // トンネル内の人混みを抜ける
    tiltJoystick(-80, -80, 0, 0, 9000);
    tiltJoystick(0, 100, 0, 0, 2500);
    tiltJoystick(-100, 0, 0, 0, 3000);
    tiltJoystick(80, -80, 0, 0, 4000);
    tiltJoystick(-100, 0, 0, 0, 5000);
    // トンネルを抜けたらホップと会話
    runStraightWithA(-80, 80, 75);
    // 会話終了後は草むらから離れて待機
    runStraightWithA(-60, 100, 60);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    // 空を飛ぶでナックルシティまで移動
    flyingToDirection();
}

// ナックルシティにて最後のジムチャレンジ
void knuckleGym(){
    // 空を飛ぶの後、右方向へ進むとすぐに会話、1分
    runStraightWithA(100, 0, 145);
    // 会話終了後そのままスタジアムへ進み、トレーナー3+キバナ戦、すべてダブルバトル9分くらい？
    runStraightWithA(0, -100, 2250);
    runStraightWithA(0, 0, 600);
    // 手持ちをパルシェン1匹にする
    // メニューを開き、手持ちポケモンを開く
    pokemonOpen();
    // パルシェンとオノノクスの持ち物を入れ替え
    // パルシェンにこだわりハチマキ、オノノクスに広角レンズ
    pushButton(Button::X, 400, 2);
    pushHatButton(Hat::DOWN, 300);
    pushButton(Button::X, 400);
    pushButton(Button::B, 400);
    // ボックス開く
    pushButton(Button::R, 2000);
    // 手持ち2匹目のポケモン（オノノクス）をボックスに戻す
    pushButton(Button::Y, 400);
    pushHatButton(Hat::LEFT, 300);
    pushHatButton(Hat::DOWN, 300);
    pushButton(Button::A, 400);
    pushHatButton(Hat::DOWN, 200, 3);
    pushHatButton(Hat::RIGHT, 300);
    pushButton(Button::A, 400);
    pushHatButton(Hat::LEFT, 300);
    pushButton(Button::A, 400);
    pushButton(Button::B, 500, 10);
    // スタジアムを出て、ソニア会話
    tiltJoystick(0, 100, 0, 0, 7000);
    pushButton(Button::A, 100, 200);
    pushButton(Button::B, 100, 50);
    
}

// ポケセンにて、凍える風を忘れさせる
void forgetMove(){
    // ナックルシティ内で空を飛ぶ、右側ポケセンへ
    flyingTaxiOpen();
    pushHatButton(Hat::RIGHT, 200);
    flyingTaxiSelect();
    // ポケセンに入り、左のおじさんにて技忘れ
    tiltJoystick(0, -100, 0, 0, 1000);
    delay(4000);
    tiltJoystick(-80, -80, 0, 0, 2000);
    pushButton(Button::A, 1000, 2);
    pushHatButton(Hat::DOWN, 200, 3);
    // 技忘れが初めての時のみ会話追加、どちらでも対応できるように調整
    pushButton(Button::A, 1000, 2);
    delay(1500);
    pushButton(Button::A, 1000);
    pushButton(Button::B, 1000);
    pushButton(Button::A, 300);
    pushButton(Button::B, 500);
    delay(1500);
    pushHatButton(Hat::LEFT, 300);
    pushButton(Button::A, 500, 2);
    delay(1500);
    pushButton(Button::A, 1000, 5);
    pushButton(Button::B, 500, 8);
    // ポケセンを出る
    tiltJoystick(80, 80, 0, 0, 2000);
    delay(2000);
}

// ナックルシティ右側のポケセンを出たところから、駅へ向かい、10番道路を進む
void goToRoute10(){
    // ナックルシティ右側ポケセンから駅へ向かう
    tiltJoystick(-100, 0, 0, 0, 6000);
    tiltJoystick(20, -80, 0, 0, 5500);
    // ホップと会話、10番道路の駅まで移動
    runStraightWithB(80, 60, 170);
    // 会話終了後、野生ポケモンが動き回る前に右上へ進む
    runStraightWithA(80, -40, 70);
    // 上へ進む、野生1、トレーナー１
    runStraightWithA(-30, -100, 155);
    // トレーナーと戦闘後は立ち止まる
    runStraightWithA(0, 0, 170);
    pushButton(Button::B, 100, 10);
    // トレーナー戦後、野生が追いかけてこないところで自転車に乗り、上（右寄り）へ進む
    pushButton(Button::PLUS, 300);
    runStraightWithA(10, -100, 700);
    // 左上へ進む、トレーナー3人と戦闘+野生3を想定
    runStraightWithA(-90, -50, 1500);
    pushButton(Button::B, 100, 20);
    // 上へ進む、ダブルバトルは無視しつつシュートシティへ、ホップ会話まで
    tiltJoystick(100, 0, 0, 0, 400);
    runStraightWithA(0, -100, 120);
    runStraightWithA(0, 0, 130);
}

// ムゲンダイナ捕獲のためのボールの準備
// 指定された「ball_number」により
void prepareBall(){
    // 番号ごとに対応するポケセンへと空を飛び、ボールの売り買いをする
    // 0の場合は何もしない
    // 1~12の場合、シュートシティ中央のポケセンへ飛び、左の店員へ
    // 13~21の場合、対応する都市のポケセンへ飛び、右の店員へ
    if(ball_number >= 1){
      if(ball_number <= 12){
        // 12以下であればシュートシティポケセンの店員左
        flyingToSamePlace();
        tiltJoystick(-30, -90, 0, 0, 1000);
        delay(4000);
        // 左の店員まで移動
        tiltJoystick(30, -90, 0, 0, 2000);
        tiltJoystick(100, 0, 0, 0, 300);
        // 0以外では共通して、モンスターボールを売る
        // 1~9の場合、何も買わずにモンスターボールを売る
        // 10の場合、モンスターボールを11個買い、プレミアボールを1個もらう
        // 11,12の場合、スーパーorハイパーボールを1個買う
        if(ball_number <= 9){
          // 1~9の場合、ボールガイからもらったガンテツボールor輸送したマスボなどを捕獲に使用
          // ショップではモンボを売るのみ
          pushButton(Button::A, 1000, 2);
          sellPokeball();
        }else if(ball_number == 10){
          // 10、プレミアボールをもらうため、モンスターボールを11個買う
          pushButton(Button::A, 1000, 3);
          delay(1000);
          pushButton(Button::A, 800);
          pushHatButton(Hat::RIGHT, 300);
          // プレミアボールをもらう
          pushButton(Button::A, 800, 4);
          // 買いを終了
          pushButton(Button::B, 1500);
          // モンスターボールを売る
          sellPokeball();
        }else{
          // 11,12、2or3番目のボールを買う
          buyPokeball(ball_number - 10);
          sellPokeball();
        }
        // ポケセンを出る
        tiltJoystick(-30, 90, 0, 0, 2000);
        delay(4000);
      }else if(ball_number <= 21){
        // 13~15、エンジンシティ
        // 16~18、ナックルシティ
        // 19~21、シュートシティ
        // 対応する都市へ空を飛ぶ
        int list_number = 0;
        if(ball_number <= 15){
          flyingTaxiOpen();
          pushButton(Button::R, 300, 2);
          flyingTaxiSelect();
          delay(5500);
          tiltJoystick(0, -100, 0, 0, 1000);
          delay(4000);
          list_number = ball_number - 13;
        }else if(ball_number <= 18){
          flyingTaxiOpen();
          pushButton(Button::R, 300, 5);
          flyingTaxiSelect();
          delay(5500);
          tiltJoystick(0, -100, 0, 0, 1000);
          delay(4000);
          list_number = ball_number - 16;
        }else{
          flyingToSamePlace();
          tiltJoystick(-30, -90, 0, 0, 1000);
          delay(4000);
          list_number = ball_number - 19;
        }
        // それぞれ右の店員から1～3番目のボールを買う
        tiltJoystick(80, -60, 0, 0, 2000);
        buyPokeball(list_number);
        sellPokeball();
        // ポケセンを出る
        tiltJoystick(-80, 60, 0, 0, 2000);
        delay(4000);
      }
      
    }
        
}

// シュートシティシティ右へ空を飛び、リーグ予選を行う
void leagueQualifying(){
    // シュートシティへ空を飛ぶ、右側ポケセンへ
    flyingTaxiOpen();
    pushButton(Button::Y, 300);
    pushHatButtonContinuous(Hat::UP_RIGHT, 200);
    flyingTaxiSelect();
    delay(5500);
    // スタジアムに入る
    tiltJoystick(100, 0, 0, 0, 3000);
    tiltJoystick(0, -100, 0, 0, 6000);
    delay(4000);
    // ボールガイに話しかけ、ドリームボールを受け取る
    SwitchController().setStickTiltRatio(-80, -60, 0, 0);
    pushButton(Button::A, 100, 15);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::B, 100, 45);
    // 受付への位置調整のために1度外へ
    tiltJoystick(80, 60, 0, 0, 2500);
    delay(2000);
    // 建物に入りなおし、受付～マリィ戦開始、まだ攻撃はしない
    runStraightWithA(0, -100, 100);
    pushButton(Button::B, 100, 250);
    runStraightWithB(0, 0, 50);
    // 初手レパルダスの先制いちゃもんで悪あがきの恐れがあるのでダイマックスで防ぐ
    pushButton(Button::A, 500);
    pushHatButton(Hat::LEFT, 300);
    // ホップ戦手前まで連打
    pushButton(Button::A, 100, 1050);
    // 続いてホップ戦～ホテルインタビュー～次の日
    tiltJoystick(-100, 0, 0, 0, 1000);
    runStraightWithA(0, -100, 1000);
    runStraightWithA(0, 0, 700);
    // マクロコスモス追いかけ開始045
    runStraightWithA(0, 100, 150);
    runStraightWithA(0, 0, 200);
    // シュートシティ内で空を飛ぶ
    flyingToSamePlace();
    // 追いかけ1回目、ポケセンから左
    tiltJoystick(100, 20, 0, 0, 3800);
    runStraightWithA(60, -90, 375);
    runStraightWithB(0, 0, 40);
    // シュートシティ内で空を飛ぶ
    flyingToSamePlace();
    // 追いかけ2回目、ポケセンから下
    tiltJoystick(30, 90, 0, 0, 6000);
    tiltJoystick(-80, 80, 0, 0, 300);
    pushButton(Button::A, 100, 15);
    runStraightWithA(-80, 40, 400);
    // 続けて電話ボックスで3回目330
    runStraightWithA(-40, -80, 300);
    // そのままローズタワーへ着いたら前に移動しつつける
    // エレベーター～オリーブ～スタジアムで本戦受付まで、15分くらい？
    // ホップとの共闘にて不確定要素多いため時間長め
    runStraightWithA(0, -100, 4900);
    SwitchController().setStickTiltRatio(0, 0, 0, 0);
    pushButton(Button::B, 100, 45);
    
}

// ローズタワー攻略後、リーグ本選を開始
void leagueMain(){
    // リーグ本戦を開始850、ジムリーダー3人と戦闘、ブラックナイト、まどろみの森入り口まで20分くらい？
    tiltJoystick(-40, 90, 0, 0, 800);
    tiltJoystick(-100, 0, 0, 0, 1600);
    runStraightWithA(0, -100, 6000);
    
}

// まどろみの森を攻略し、ローズ戦まで
// ムゲンダイナ戦の直前で、リーグ前に調整したボールを並び替え、投げるボールとする
// ムゲンダイナを厳選したい場合はここまでで自動化を停止、手動で厳選する
void getRottenSword(){
    // まどろみの森入り口を進む、野生4を想定
    pushButton(Button::PLUS, 300);
    runStraightWithA(-100, -20, 600);
    // 上へ進む、野生1を想定
    runStraightWithA(0, -100, 300);
    // 左上へ進み続け、メンタルハーブのそばまで、野生5を想定
    runStraightWithA(-90, -50, 750);
    // 右上へ進み続け、けむり玉のそばまで、野生4を想定
    runStraightWithA(60, -90, 600);
    // 右下へ少し進み、草むらを出る、野生1を想定
    runStraightWithA(-80, 80, 150);
    // 上へ進み続ける、野生1を想定、ザシアン・ザマゼンタ遭遇、朽ちた剣盾入手
    runStraightWithA(0, -100, 850);
    // ホップに話しかけるとナックルシティまで移動
    SwitchController().setStickTiltRatio(30, 90, 0, 0);
    pushButton(Button::A, 100, 50);
    // キバナと会話
    runStraightWithA(0, 0, 175);
    // キバナを避けつつ上のスタジアムへ
    tiltJoystick(-80, -80, 0, 0, 500);
    tiltJoystick(20, -90, 0, 0, 12000);
    // オリーブ会話
    pushButton(Button::A, 100, 125);
    // 右のエレベーターに乗る
    runStraightWithA(90, -50, 10);
    // ローズ戦、全体的に氷柱針回数多め、ダイオウドウ確2
    tiltJoystick(-80, -80, 0, 0, 12000);
    runStraightWithA(0, -100, 600);
    runStraightWithA(0, 0, 750);
    // ここでムゲンダイナ用のボールを整理
    // 0,9を指定している場合は、並び順通りのボールを投げる（ここでは何もしない）
    // 1~7を指定している場合は、指定したボールをお気に入り登録してからお気に入り順に並び替え
    // 10以降は、種類順で並び替えることで1番上（ガンテツボールよりも上）にする
    if(ball_number >= 10){
      bagOpen();
      // 念のためカーソルを左に戻してから、ボールポケットへ
      pushHatButtonContinuous(Hat::LEFT, 2000);
      pushHatButton(Hat::RIGHT, 400);
      // ボールを種類順で並び替え、指定したボールを上にする
      pushButton(Button::X, 500);
      pushButton(Button::A, 500, 2);
      pushButton(Button::B, 100, 120);
      
    }else if(ball_number >=2 && ball_number <=7){
      bagOpen();
      // 念のためカーソルを左に戻してから、ボールポケットへ
      pushHatButtonContinuous(Hat::LEFT, 2000);
      pushHatButton(Hat::RIGHT, 400);
      // 〇番目のボールをお気に入り★登録する
      pushHatButton(Hat::DOWN, 300, ball_number - 1);
      pushButton(Button::Y, 800);
      // ボールをお気に入り順で並び替え、指定したボールを上にする
      pushButton(Button::X, 500);
      pushHatButton(Hat::DOWN, 300, 2);
      pushButton(Button::A, 500, 2);
      pushButton(Button::B, 100, 120);
    }
    
}

// ムゲンダイナと戦闘、捕獲
// 捕獲には直前に並び替えたボールを使用する
// ムゲンダイナを厳選したい場合はこの直前で自動化を停止、手動で厳選する
void getEternatus(){
    // ローズ戦終了から左下に進み、屋上へ
    runStraightWithA(-10, 100, 50);
    runStraightWithA(80, -80, 100);
    // ザシザマ出現、捕獲まで
    runStraightWithA(0, 0, 2550);
    // ホテルにて、ムゲンダイナをボックスに預ける
    // メニューを開き、ボックス開く
    pokemonOpen();
    pushButton(Button::R, 2000);
    // 手持ちの2匹目（ムゲンダイナ）をボックスに置く
    pushButton(Button::Y, 400);
    pushHatButton(Hat::LEFT, 300);
    pushHatButton(Hat::DOWN, 300);
    pushButton(Button::A, 400);
    pushHatButton(Hat::DOWN, 200, 4);
    pushHatButton(Hat::RIGHT, 300);
    pushButton(Button::A, 400, 3);
    // ボックスを閉じる
    pushButton(Button::B, 500, 12);
}

// チャンピオン、ダンデとの最後の戦い
void theLastBattle(){
    // ホテルを出る
    tiltJoystick(-10, 100, 0, 0, 15000);
    // シュートシティ内で空を飛ぶ、右側ポケセンへ
    flyingTaxiOpen();
    pushHatButtonContinuous(Hat::UP_RIGHT, 200);
    flyingTaxiSelect();
    delay(5500);
    // スタジアムに入る
    tiltJoystick(100, -10, 0, 0, 3000);
    // 受付～ダンデと戦い、ギルガルドはキンシあり、乱数2発？950
    runStraightWithA(0, -100, 1500);
    // 戦闘後はそのままエンディング
    runStraightWithA(0, 0, 1650);
}


void setup(){
    
    pushButton(Button::B, 300, 13);
    firstEngine();
    uniformNumber();
    hotelWithYelling();
    goToRoute3();
    galarMine();
    goToRoute4();
    turfGym();
    goToRoute5();
    bauGym();
    seaFood();
    secondMine();
    engineGym();
    kudakeru_shigoto();
    firstKnuckle();
    obtainwaterStone();
    evolvedByStone();
    treasureHouse();
    goToRoute6();
    lateralGym();
    arabesqueForest();
    arabesqueGym();
    secondKnuckle();
    goToRoute78();
    circusGym();
    obtainLens();
    goToRoute9();
    obtainBand();
    spikeGym();
    goToTunnel();
    knuckleGym();
    forgetMove();
    goToRoute10();
    prepareBall();
    leagueQualifying();
    leagueMain();
    getRottenSword();
    getEternatus();
    theLastBattle();

    sleepNow();
}

void loop(){
    

}
