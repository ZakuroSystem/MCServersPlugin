# MCServers Connector Plugin

MCServers.jp の掲載サーバー向け Java plugin です。

導入前に [データ送信規約](DATA_POLICY.md) を確認してください。公式サイトからのダウンロード時には、この内容への同意が必要です。

- Bukkit / Spigot / Paper 系向け
- Java 8 bytecode でビルド
- Web 側 Plugin API と HMAC 署名で連携
- 難読化せず、監査しやすい形で公開

## できること

- 掲載申請後の所有権確認
- heartbeat 送信
- プレイヤー数 / 最大人数 / バージョン送信
- 投票報酬の取得
- サーバー側コマンド実行後の ack

## ビルド

```powershell
gradle build
```

生成物:

```text
build/libs/mcservers-connector-1.0.1.jar
```

## 設定

`plugins/MCServersConnector/config.yml`

### 通常運用

```yaml
base-url: "https://mcservers.jp"
server-id: 1
server-secret: "管理画面で発行したキー"
heartbeat-interval-seconds: 60
vote-poll-interval-seconds: 30
ownership-verify-interval-seconds: 60
claim-batch-size: 20
connect-timeout-seconds: 10
request-timeout-seconds: 15
```

### 所有権確認前

```yaml
server-id: 0
server-secret: "CHANGE_ME"
ownership-token: "掲載申請後に表示されたトークン"
```

承認後は plugin が `server-id` と `server-secret` を受け取り、自動保存します。

## コマンド

```text
/mcservers status
/mcservers reload
```

権限:

```text
mcservers.admin
```

## 連携 API

```text
POST /api/plugin/v1/ownership/verify
POST /api/plugin/v1/heartbeat
POST /api/plugin/v1/rewards/fetch
POST /api/plugin/v1/rewards/ack
```

保護方式:

- `X-MCServers-Server-Id`
- `X-MCServers-Timestamp`
- `X-MCServers-Nonce`
- `X-MCServers-Signature`
- HMAC-SHA256

## 公開方針

この plugin は難読化しません。コード監査しやすさと、サーバー管理者が挙動を確認できることを優先します。

秘匿するのはソースコードではなく、各サーバーに払い出す以下の運用情報です。

- `server-secret`
- `ownership-token`
- 稼働中サーバーの実際の設定値

## 実装メモ

今回の公開整理で以下を強化しています。

- 非同期タスクから Bukkit API を直接触らず、必要な状態取得は同期スナップショット化
- connect timeout / read timeout を分離
- `ownership-token` が残っていても、既存 credentials がある場合は確認ポーリングを止める
- HTTP 応答読み取りと接続解放を明示化
- 正規表現の再コンパイルを避ける軽量化

## ライセンス

このpluginは [GNU General Public License v3.0](LICENSE) で公開しています。
