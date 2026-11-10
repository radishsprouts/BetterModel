# BetterModel wiki 用資料

- プラグイン名: BetterModel（Folia 対応フォーク radishsprouts/BetterModel の v3 系）
- 最終更新日: 2026-11-11
- 資料が合っているコミット: c14e5b28
- この資料を使っている wiki のページ: なし（2026-11-11 時点で、本番の一般サーバー・資源サーバーの `plugins\` に BetterModel は入っていない。入るまでは wiki に載せない）

> wiki(角鯖の公式サイトのプレイヤー向け Wiki)を書く時に、ソースを全部読み直さずに済ませるための資料。README・仕様書ではない。プレイヤーから見える事実だけを書く。

## どんな機能か

- Blockbench で作ったモデルを、ゲームの中の MOB・プレイヤーなどに着せて、動かす。
- **統合版(Bedrock)のプレイヤーにも、Java 版と同じモデルが見える。** 統合版のプレイヤーは Geyser 経由で入る。BetterModel が統合版用のモデルとテクスチャを自分のフォルダへ書き出し、Geyser 側の拡張(KadoDisplayEntity)がそれを読んで統合版のクライアントに見せる。統合版のプレイヤーが自分で何かを入れる必要は無い。
- 遠くのプレイヤーへ送る動きの量を、距離に応じて減らす(下の「数値」)。

## プレイヤーの使い方

プレイヤーが操作する画面・コマンドは無い。モデルを着た MOB・プレイヤーを見るだけである。

## 統合版でも見えるもの(設計上。実機での確認は「確認待ちの点」を参照)

- モデルの形(立方体・回転した立方体・メッシュ)、テクスチャ、テクスチャの透明、発光するテクスチャ(名前が `_e` で終わる、または発光の指定)、骨の GLOW タグ、光の強さ 15 の立方体。
- 骨の動き(アニメーション)、色付け(ダメージの赤みなど)。

## 統合版で出ない見た目・Java 版と違う見た目

| 内容 | 統合版での見え方 |
|---|---|
| 光の強さ 1〜14 の立方体 | 光らない。周りの明るさで描く(全光か否かの2つしか持てない) |
| 明るさの上書き(block が 15 未満) | 周りの明るさで描く |
| glow の外枠(壁越しに見える縁取り) | 出ない |
| 影 | 出ない |
| 色付け | 近似。色の3成分のうち一番小さい成分は Java 版と一致し、他の成分は Java 版より明るく出る。灰色(暗くするだけ)の色付けは一致する |
| 動くテクスチャ | コマ送りになる。コマの間をなめらかにつなぐ補間は出ない |

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

## 書き出されるファイル(運営向けの情報)

`plugins/BetterModel/bedrock/`(`bedrock.output-folder`)の下に、リロードのたびに次を書く。

- `index.json` … 目録。Geyser 側の拡張が最初に読む。書き込みの最後に置き換える
- `BetterModelBedrock.mcpack` … 統合版用のパック(モデルの形・テクスチャ・manifest)。内容が変わると版が変わる
- `limbs/*.geo.json` … プレイヤーの体の部位の形
- `pack.uuid` … パックの UUID。最初に1回作り、以後は変えない

## プレイヤーに出るメッセージ(原文のまま)

| 場面 | メッセージ |
|---|---|
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
- 統合版のクライアントでの見え方は、まだ確かめていない。次は設計書で「推測」としたもの: 回転した立方体の面の向き(`uv_rotation`)、メッシュ(`poly_mesh`)が使えるか・向き・裏返り、発光の描き方、薄い板の裏面、手足の寸法。
- 「統合版で出ない見た目」の表は、設計書による。実機で確かめて直す。

## 根拠

| 事実 | 場所 |
|---|---|
| `bedrock` 設定の既定値 | `core/src/main/resources/config.yml`(`bedrock:` の節) |
| 統合版用の出力の書き出し(`index.json` を最後に置き換える) | `core/src/main/kotlin/kr/toxicity/model/bedrock/BedrockExporter.kt`(`write`) |
| 光の強さと全光の扱い | `core/src/main/kotlin/kr/toxicity/model/bedrock/BedrockGeometry.kt`(`build`) |
| 動くテクスチャのコマ数 | `core/src/main/kotlin/kr/toxicity/model/bedrock/BedrockGeometry.kt`(`BedrockTexture.of`) |
| `animation-lod-*` の既定値 | `core/bukkit-core/src/main/kotlin/kr/toxicity/model/bukkit/BetterModelConfigImpl.kt`(`animationLod*`) |
