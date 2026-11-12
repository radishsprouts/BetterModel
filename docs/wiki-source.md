# BetterModel wiki 用資料

- プラグイン名: BetterModel（Folia 対応フォーク radishsprouts/BetterModel の v3 系）
- 最終更新日: 2026-11-11
- 資料が合っているコミット: 46cdf1e0
- この資料を使っている wiki のページ: なし（2026-11-11 時点で、本番の一般サーバー・資源サーバーの `plugins\` に BetterModel は入っていない。入るまでは wiki に載せない）

> wiki(角鯖の公式サイトのプレイヤー向け Wiki)を書く時に、ソースを全部読み直さずに済ませるための資料。README・仕様書ではない。プレイヤーから見える事実だけを書く。

## どんな機能か

- Blockbench で作ったモデルを、ゲームの中の MOB・プレイヤーなどに着せて、動かす。
- **統合版(Bedrock)のプレイヤーにも、Java 版と同じモデルが見える。** 統合版のプレイヤーは Geyser 経由で入る。BetterModel が統合版用のモデルとテクスチャを自分のフォルダへ書き出し、Geyser 側の拡張(KadoDisplayEntity)がそれを読んで統合版のクライアントに見せる。統合版のプレイヤーが自分で何かを入れる必要は無い。
- **Java 版のプレイヤーは、サーバーに入るとリソースパックの適用を尋ねられる。** 受け入れるとモデルが正しく見える。受け入れないと、モデルが紫と黒の格子(テクスチャ無し)で見える。尋ねる文は「このサーバーのモデルを表示するためのリソースパックです。適用してください。」(運営が変えられる)。適用を必須にする設定もあり、必須の時は適用しないとサーバーに入れない。パックは、サーバーが BetterModel 専用の Cloudflare Worker へ自分でアップロードして配る(既定)。サーバーが自分の HTTP で配る方式(self-host)と、運営が別の場所に置いたパックを案内する方式(url)にも切り替えられる。統合版のプレイヤーには出ない(統合版は別の仕組みで見える)。パックの中身が変わった時(サーバーの再読み込みでモデルを足した時など)だけ、接続中のプレイヤーに新しい適用の確認が出る。変わらなければ出ない。
- 遠くのプレイヤーへ送る動きの量を、距離に応じて減らす(下の「数値」)。
- **モデルを付けた MOB の名前は、モデルの上に出る。名前を後から変えると、モデルの上の名前も変わる。** 名札で付け替えた時も、他のプラグインが名前を変えた時も同じ(例: 体力を名前に出すボス)。変えた名前は次の更新(ゲームの 1 tick 以内)で、そのモデルを見ている全員に届く。名前を消すと、モデルの上の名前も消える。名前が変わっていない間は何も送り直さない。
  - **名札で名前を付けた MOB は、バニラと同じく、見た時に名前が出る。** 見た時とは、5 ブロック以内で、その MOB のほうを見ている時。名札は名前を付けるだけで「名前を常に表示する」設定は立てないので、離れた場所や別の方向を見ている時は出ない。
  - **「名前を常に表示する」設定が立っている MOB は、離れていても向きに関係なく、常に名前が出る。** 他のプラグインなどでこの設定を後から変えても追随する(変わった時だけ送り直す)。
  - プレイヤーに着せたモデルでは、プレイヤー名(または付けた名前)が常に出る。
  - 名前が付いていない MOB には、何も出ない。
  - モデルの骨の設定(タグ)が PLAYER_TAG の骨は、MOB でも常に名前が出る。
- **プレイヤーの姿のモデル(`players` フォルダのモデル。例: steve)を着せた MOB・プレイヤーは、サーバーを再読み込み(`bettermodel reload`)してもモデルが外れず、同じ皮膚で付け直される。** 皮膚を指定して着せた場合は、その皮膚のまま戻る。皮膚を指定していない場合は、プレイヤー本人ならその人の皮膚、MOB なら既定の皮膚で戻る。皮膚を取れなかった時も外れず、既定の皮膚で戻る。MOB に着せたモデルは、サーバーを再起動しても残る(プレイヤー本人に着せたモデルは、退出すると外れる。これは従来どおり)。

## プレイヤーの使い方

プレイヤーが操作する画面・コマンドは無い。モデルを着た MOB・プレイヤーを見るだけである。

## 統合版でも見えるもの(設計上。実機での確認は「確認待ちの点」を参照)

- モデルの形(立方体・回転した立方体・メッシュ)、テクスチャ、テクスチャの透明、発光するテクスチャ(名前が `_e` で終わる、または発光の指定)、骨の GLOW タグ、光の強さ 15 の立方体。
- 骨の動き(アニメーション)、色付け(ダメージの赤みなど)。
- 乗り物(馬・ボート・トロッコなど)に乗った MOB・プレイヤーのモデルの高さ。BetterModel が乗り位置を各骨のアイテムに載せ、統合版側がそれを座席の高さに使う(Java 版の見た目は変わらない)。乗り位置は、大きさ・子供かどうか・姿勢が変わった時だけ送り直す。
- プレイヤーのモデルの手足(頭・胴・腕・脚・マント)の形と、その人の皮膚、皮膚の外側の層。統合版側は偽のプレイヤーで手足を描くため、BetterModel が手足の形の資料と、その人の皮膚の取得元(PROFILE)をアイテムに載せる。細い腕の皮膚(slim)は別の形で書き出す。
- プレイヤーのモデルの頭の兜(兜の種類)。BetterModel が被せる兜のアイテム id をアイテムに載せ、統合版側が統合版の兜にして被せる。
- 統合版のプレイヤーに MOB・プレイヤーのモデルが現れた瞬間から、色・アイテムなどの初期の状態が同じ通信で届く(現れた後に遅れて変わらない)。

## 統合版で出ない見た目・Java 版と違う見た目

| 内容 | 統合版での見え方 |
|---|---|
| 光の強さ 1〜14 の立方体 | 光らない。周りの明るさで描く(全光か否かの2つしか持てない) |
| 明るさの上書き(block が 15 未満) | 周りの明るさで描く |
| glow の外枠(壁越しに見える縁取り) | 出ない |
| 影 | 出ない |
| 色付け | 近似。色の3成分のうち一番小さい成分は Java 版と一致し、他の成分は Java 版より明るく出る。灰色(暗くするだけ)の色付けは一致する |
| 動くテクスチャ | コマ送りになる。コマの間をなめらかにつなぐ補間は出ない |
| 手足の回転(プレイヤーのモデル) | 向き(左右・上下)と一様な大きさは合う。ひねり(roll)と縦横で違う大きさは出ない。腕を横に上げる動きはできない |
| 胴・腕・脚の防具 | 出ない(兜だけ出る) |
| 兜の染色・装飾(トリム) | 出ない(兜の種類だけ出る) |
| 手足の動きの滑らかさ | 偽のプレイヤーの移動の補間で滑らかになるが、Java 版の補間時間とは一致しない |

(上の表は設計書の 8 章による。ゲームの画面で確かめた結果ではない。確認待ちの点を参照)

## 数値

| 項目 | 設定のキー | 既定値 | 本番の値(確かめた日・サーバー) |
|---|---|---|---|
| 統合版向けの出力を作るか | `config.yml` の `bedrock.enabled` | `true` | 本番に未導入 |
| 統合版向けの出力先のフォルダ | `config.yml` の `bedrock.output-folder` | `bedrock`(プラグインのフォルダの下。絶対パスも書ける) | 本番に未導入 |
| 遠くのプレイヤーへの動きの間引き | `config.yml` の `animation-lod` | `true` | 本番に未導入 |
| 間引きを始める距離(1段目) | `animation-lod-near-distance` | 16 ブロック | 本番に未導入 |
| 1段目の間引き幅 | `animation-lod-near-step` | 2(送る量が 1/2) | 本番に未導入 |
| 間引きをさらに増やす距離(2段目) | `animation-lod-far-distance` | 32 ブロック | 本番に未導入 |
| 2段目の間引き幅 | `animation-lod-far-step` | 4(送る量が 1/4) | 本番に未導入 |
| パックを配るか | `resource-pack-host.enabled` | `true` | 本番に未導入 |
| 配り方 | `resource-pack-host.mode` | `cloudflare`(BetterModel 専用の Cloudflare Worker へアップロードして配る)。`self-host` にするとサーバーが自分で配り、`url` にすると別の場所に置いたパックの URL を案内する | 本番に未導入 |
| Cloudflare のアカウント ID | `resource-pack-host.cloudflare.account-id` | 空(空なら環境変数 `BETTERMODEL_CLOUDFLARE_ACCOUNT_ID`)。足りない時は設定の場所を1回警告して配らない | 本番に未導入 |
| Cloudflare の API トークン | `resource-pack-host.cloudflare.api-token` | 空(空なら環境変数 `BETTERMODEL_CLOUDFLARE_API_TOKEN`。ログには出さない)。権限はアカウントの Workers Scripts Write と、ゾーン kaiware.games の Workers Routes Write | 本番に未導入 |
| 専用 Worker の名前 | `resource-pack-host.cloudflare.worker-name` | `bettermodel-pack`(無ければ最初のアップロードで作る。他のリソースパックの Worker とは別) | 本番に未導入 |
| カスタムドメイン | `resource-pack-host.cloudflare.custom-domain` | `bettermodel.minecraft.kaiware.games`(必須。workers.dev は使わず、Worker の workers.dev と preview の URL はアップロードのたびに無効にする。空にすると何もアップロードせず警告して配らない。ゾーン kaiware.games が API トークンと同じアカウントにある必要がある。`resource.minecraft.kaiware.games`(別のリソースパックを配る Worker)は設定してもアップロードしない。別の Worker や DNS レコードが既にあるホスト名は上書きせず警告して止まる) | 本番に未導入 |
| Worker の compatibility_date | `resource-pack-host.cloudflare.compatibility-date` | `2025-09-01` | 本番に未導入 |
| 配る待ち受けのアドレス | `resource-pack-host.bind-address` | `0.0.0.0` | 本番に未導入 |
| 配る待ち受けのポート | `resource-pack-host.port` | `8163`(ゲームのポートとは別に、外から届くように開ける) | 本番に未導入 |
| プレイヤーへ案内する URL | `resource-pack-host.public-url` | 空(空だとサーバーの IP か 127.0.0.1 から作るが、外のプレイヤーには届かない。本番では `http://サーバーのIPかドメイン:ポート/bettermodel.zip` を書く) | 本番に未導入 |
| url モードのパックの URL | `resource-pack-host.url` | 空 | 本番に未導入 |
| 適用を尋ねる文 | `resource-pack-host.prompt` | 「このサーバーのモデルを表示するためのリソースパックです。適用してください。」 | 本番に未導入 |
| 適用を必須にするか | `resource-pack-host.required` | `false` | 本番に未導入 |
| 配る側の同時処理の上限 | `resource-pack-host.max-connections` | 16 | 本番に未導入 |

## 書き出されるファイル(運営向けの情報)

`plugins/BetterModel/bedrock/`(`bedrock.output-folder`)の下に、リロードのたびに次を書く。

- `index.json` … 目録。Geyser 側の拡張が最初に読む。書き込みの最後に置き換える
- `BetterModelBedrock.mcpack` … 統合版用のパック(モデルの形・テクスチャ・manifest)。内容が変わると版が変わる
- `limbs/*.geo.json` … プレイヤーの体の部位の形
- `pack.uuid` … パックの UUID。最初に1回作り、以後は変えない

`plugins/BetterModel/.cache/skins/` には、プレイヤーモデルが使う皮膚・マントの PNG(`<ハッシュ>.png`)が保存される。皮膚の URL ごとに1ファイルで、プレイヤーが入り直しても、サーバーを再起動しても、同じ皮膚なら再びダウンロードしない。皮膚を変えると URL が変わるので、新しい皮膚は自動で取り直される。30日以上使われていないファイルと、合計 64MB を超えた分(古い順)は、起動時とリロード時に自動で消える。手で消しても、次に必要になった時に取り直すだけで害は無い。

配るパックについて(運営向けの情報):

- **既定(cloudflare)の動き**: パックを作り終えた時(起動時と `bettermodel reload`)に、プラグイン自身が Cloudflare の API を直接呼んで、BetterModel 専用の Worker(Static Assets だけ)へ ZIP をアップロードする。Linux 本番でも、jar と config(または環境変数のアカウント ID・API トークン)だけで動き、node・wrangler・外部コマンドは要らない。置くファイルは `/bettermodel-<SHA-1 の先頭12桁>.zip` の1つだけで、アップロードのたびに Worker の中身が入れ替わる。**他のリソースパックの Worker には触らない**(Static Assets は送ったファイルの一覧で版が丸ごと置き換わるため、同じ Worker に置かない)。アップロードのあと、カスタムドメインからパックを取れるか確かめる。**新しくカスタムドメインを付けた直後は、Cloudflare が証明書を発行し終えるまで接続に失敗する**ので、15秒おきに最大3分ほど自動で再試行する(待っている間もサーバーは止まらず、コンソールに1回だけ知らせる)。接続の失敗・404・5xx は待ち、パックの中身が食い違う等の待っても直らない失敗は待たずに警告する。3分たっても取れなければ警告して配らず、時間を置いて `bettermodel reload` する。
- 前回アップロードした SHA-1 と URL は `plugins/BetterModel/.cache/cloudflare-pack.properties` に保存し、中身が同じなら API を呼ばない。アップロードは裏の仮想スレッドで行い、終わった時点で参加済みのプレイヤーにも適用の確認を送る。25 MiB を超えるパックは送らず警告する。
- アップロードに失敗しても self-host へ自動では切り替えない。警告を出し、新しいパックは配らない(前回の URL と中身が同じ時だけ、前回の URL を使う)。アカウント ID・トークンが無い時は、足りないものと設定の場所を1回だけ警告して配らない。

- self-host の時、配るパスは `/bettermodel.zip` の1つだけ。GET と HEAD だけに答え、それ以外は 405、別のパスは 404。ETag(パックの SHA-1)に答える。
- **ハッシュ(SHA-1)はどのモードでもクライアントへ渡さない**(ハッシュの食い違いによる配布のトラブルを避けるため。設定で切り替える項目も無い)。クライアントは検証せずに受け取るので、参加のたびにダウンロードし直すことがある。SHA-1 はサーバーの中だけで、同じパックを送り直さない判定・ETag・アップロード先のファイル名に使う。url モードでは、中身の変更を検知するために案内する URL の実物をサーバーが取って SHA-1 を計算する(取れない時は URL が変わった時だけ送り直す)。
- 配るパックは固定の ID を持ち、同じ ID で差し替わる。`pack-type` が `zip` なら書き出した ZIP を、`folder` ならメモリ上で ZIP を作って配る。`none` の時は配らない。
- Nexo が入っていて `merge-with-external-resources` が `true` の時は、Nexo がパックを配るので BetterModel は配らない(二重に送らない)。
- 配る用のポートが使えない時は、エラーを1行出して配ることだけをやめる(サーバーは起動する)。サーバーを止めるとポートも解放される。

## プレイヤーに出るメッセージ(原文のまま)

| 場面 | メッセージ |
|---|---|
| 適用の確認(Java 版、入った時) | `resource-pack-host.prompt` の文(既定は上の「数値」の表) |
| パックを配り始めた時(コンソール) | `リソースパックを配ります: <URL>` |
| ポートが使えない時(コンソール) | `リソースパックの配布用ポート <アドレス>:<ポート> を使えません。配布だけを止めます: <理由>` |
| Cloudflare の設定が足りない時(コンソール、1回) | `リソースパックを配れません。config.yml の resource-pack-host.cloudflare に <足りないもの> を設定してください。` |
| Cloudflare へ置けなかった時(コンソール) | `リソースパックを Cloudflare へ置けなかったため、新しいパックは配りません: <理由>` |
| 新しいカスタムドメインの証明書の発行を待つ時(コンソール、1回) | `カスタムドメインの証明書の発行(DNS の反映)を待っています。<URL> から取れるまで、最大3分ほど待ちます。` |
| URL が空のまま外へ届かない時(コンソール、起動後1回) | `resource-pack-host.public-url が空のため <URL> をプレイヤーへ渡します。外のプレイヤーにはこの URL は届きません。` |
| 書き出し成功時(コンソール) | `統合版用の出力を書き出しました: <フォルダ> (骨 <n> 件、手足 <m> 件、版 <版>)` |
| 書き出し失敗時(コンソール) | `統合版用の出力の書き出しに失敗しました。` |
| 衝突でモデルを飛ばした時(コンソール) | `統合版の骨のキー <キー> が衝突したため、次のモデルの統合版の出力を飛ばします: <モデル名>` |

(プレイヤーに表示されるものではなく、コンソールに出る。)

## 権限と、使えるサーバー

プレイヤーが使う権限は無い。BetterModel 自体は本番のどちらのサーバーにも入っていない(2026-11-11 確認)。

## 管理者専用の機能

- `config.yml` の `bedrock` の節と `animation-lod-*` の設定(上の「数値」)。サーバーを再読み込みすると出力を作り直す。

## wiki に書いてはいけないこと

- プラグイン名(BetterModel)と、Geyser・KadoDisplayEntity・Velocity などの内部の構成
- `index.json` の書式、識別子の規則、`config.yml` のキー(運営向けの情報)
- 統合版のパックの中身、geometry、mcpack の作り方

## 確認待ちの点

- 本番サーバーに BetterModel が入っていない(2026-11-11)。入ってから、設定値を本番で確かめる。
- 統合版のクライアントでの見え方は、まだ確かめていない。次は設計書で「推測」としたもの: 回転した立方体の面の向き(`uv_rotation`)、メッシュ(`poly_mesh`)が使えるか・向き・裏返り、発光の描き方、薄い板の裏面、手足の寸法、乗り物に乗った時の座席の高さ(符号と X・Z の向き)。
- 「統合版で出ない見た目」の表は、設計書による。実機で確かめて直す。
- モデルの上の名前が、名前の変更に追随する挙動は、ビルドと単体テスト(判定の部分)までしか確かめていない。ゲームの中で名札・他プラグインでの名前変更が届くことは未確認(開発サーバーの `/bmtest tag name` と `/bmtest tag show` で確かめる予定)。
- 名札で付けた名前が、見た時にだけ出ること、「名前を常に表示する」設定の MOB では常に出ること、その設定の変更に追随することは、ビルドと単体テスト(常時表示の決め方)までしか確かめていない。ゲームの中で確かめる(開発サーバーで名札を付けた MOB と、設定を立てた MOB を見比べる)。
- 「見た時」の視線の角度の幅は、wiki に書く前にソース(`EntityUtil`)で確かめる。
- プレイヤーの姿のモデルが再読み込みで外れず皮膚も戻ること、MOB に着せたモデルが再起動を越えて残ることは、ビルドと単体テスト(保存データの読み書き・古い形式の読み込み・型に合った表を引く判定)までしか確かめていない。ゲームの中で、皮膚を指定して着せた MOB に再読み込みをかけて確かめる。

- リソースパックの配布は、ビルドと単体テスト(配ったバイト列とサーバーが持つ中身の一致・404・405・ETag・ポートが使えない時・統合版の判定・送り直しの判定)までしか確かめていない。ゲームの中で、Java 版のクライアントが入った時に適用の確認が出て、受け入れるとモデルが正しく見えることは未確認。

## 根拠

| 事実 | 場所 |
|---|---|
| `bedrock` 設定の既定値 | `core/src/main/resources/config.yml`(`bedrock:` の節) |
| 統合版用の出力の書き出し(`index.json` を最後に置き換える) | `core/src/main/kotlin/kr/toxicity/model/bedrock/BedrockExporter.kt`(`write`) |
| 光の強さと全光の扱い | `core/src/main/kotlin/kr/toxicity/model/bedrock/BedrockGeometry.kt`(`build`) |
| 動くテクスチャのコマ数 | `core/src/main/kotlin/kr/toxicity/model/bedrock/BedrockGeometry.kt`(`BedrockTexture.of`) |
| モデルの上の名前・常時表示の設定の変更に追随する(変わった時だけ更新) | `api/src/main/java/kr/toxicity/model/api/tracker/EntityTracker.java`(`createNametag` の後の `tick`)、`api/src/main/java/kr/toxicity/model/api/tracker/NameChangeDetector.java` |
| 常に表示するかの決め方(PLAYER_TAG の骨・プレイヤーは常に、それ以外は customNameVisible に従う) | `api/src/main/java/kr/toxicity/model/api/tracker/NametagVisibility.java`(`alwaysVisible`) |
| 名前は印に関係なく渡す(名札の名前も出る)。名前が無ければ出ない | `nms/v26_R3/src/main/kotlin/kr/toxicity/model/bukkit/nms/v26_R3/BaseEntityImpl.kt`(`customName`、`customNameVisible`) |
| 常に表示しない時は、5 ブロック以内で見ている時だけ出る | `api/src/main/java/kr/toxicity/model/api/util/EntityUtil.java`(`isCustomNameVisible`)、`nms/v26_R3/src/main/kotlin/kr/toxicity/model/bukkit/nms/v26_R3/ModelNametagImpl.kt`(`send`) |
| プレイヤーの姿のモデルも保存する(`PLAYER(true)`)。再読み込みで皮膚つきで付け直す | `api/src/main/java/kr/toxicity/model/api/data/renderer/ModelRenderer.java`(`Type`)、`api/src/main/java/kr/toxicity/model/api/tracker/EntityTrackerRegistry.java`(`reload`、`restore`) |
| 保存する皮膚(プロフィールの id と名前)。無い・不明なら既定の皮膚 | `api/src/main/java/kr/toxicity/model/api/tracker/TrackerData.java`(`profile`、`restorableProfile`)、`api/src/main/java/kr/toxicity/model/api/tracker/EntityTracker.java`(`asTrackerData`) |
| 保存したモデルの型(GENERAL / PLAYER)に合った表から付け直す。型が無い古い保存は GENERAL → PLAYER の順で引く | `api/src/main/java/kr/toxicity/model/api/tracker/TrackerData.java`(`type`、`resolve`)、`api/src/main/java/kr/toxicity/model/api/tracker/EntityTrackerRegistry.java`(`load`) |
| `animation-lod-*` の既定値 | `core/bukkit-core/src/main/kotlin/kr/toxicity/model/bukkit/BetterModelConfigImpl.kt`(`animationLod*`) |
| `resource-pack-host` の既定値・不正な値の扱い・案内する URL の作り方・パックの固定 ID・統合版の判定 | `core/src/main/resources/config.yml`(`resource-pack-host:`)、`core/src/main/kotlin/kr/toxicity/model/pack/ResourcePackHostConfig.kt` |
| パックを配る HTTP(パス・405/404・ETag・同時処理の上限・ポートが使えない時) | `core/src/main/kotlin/kr/toxicity/model/pack/PackHttpServer.kt` |
| 参加時に送る・変わった時だけ送り直す・Nexo と二重に送らない・統合版を飛ばす・プレイヤーごとのスケジューラ | `core/bukkit-core/src/main/kotlin/kr/toxicity/model/bukkit/pack/ResourcePackDistributor.kt`、`core/src/main/kotlin/kr/toxicity/model/pack/PackOfferTracker.kt` |
