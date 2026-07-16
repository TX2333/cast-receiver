---
name: pitch-pilot-data-api
description: 足球数据分析API接口文档。提供比赛列表、技术统计、事件流、传球网络、射门图、球员排行榜、实时趋势与胜率预测、中场/全场复盘等数据服务。面向数据消费方的只读JSON接口。
license: MIT
---

# Pitch Pilot 数据 API

面向数据消费方的只读 JSON 接口。基于完整处理后的比赛模型提供数据服务。

## 基本信息

| 项 | 说明 |
|---|---|
| 协议 | HTTP / HTTPS |
| 方法 | 全部为 `GET` |
| 认证模式 | `platform_managed`（密钥由平台注入） |
| 密钥来源 | `process.env["INTEGRATIONS_API_KEY"]` |
| Auth Header | `X-Gateway-Authorization: Bearer <key>` |
| 编码 | UTF-8，`Content-Type: application/json` |

## 通用约定

| 项 | 约定 |
|---|---|
| `schema_version` | 每个返回都带，当前 `"1.0"` |
| `match_id` | 稳定的比赛 ID（Opta 比赛 id） |
| `player_id` | 整数，单场内稳定 |
| `team_id` | 字符串，稳定 |
| **坐标** | `[0,100]` 攻击方向坐标系，统一从左向右进攻 |
| **`span` 时间段** | `full`（默认）· `1H` · `2H` · `ET1` · `ET2` · `MM:SS-MM:SS` |

### 错误响应

```json
{ "error": "错误描述", "code": "not_found" }
```

| HTTP 状态 | `code` | 含义 |
|---|---|---|
| `404` | `not_found` | `match_id` 不存在 |
| `400` | `bad_request` | 参数非法 |

## 接口清单

| # | 接口 | 用途 | 网关 API ID |
|---|---|---|---|
| 1 | `GET /matches` | 比赛列表 | `api-pLVz4JBJbvKL` |
| 2 | `GET /matches/{id}` | 整场：技术统计 + 事件流 | `api-V9gD4zNz1rPL` |
| 3 | `GET /matches/{id}/passing-network` | 传球网络 | `api-AalZ6z5z2XML` |
| 4 | `GET /matches/{id}/turnovers` | 丢失球/抢断热点 | `api-2Xl7b6p2b0aL` |
| 5 | `GET /matches/{id}/shots` | 射门图（含 xG） | `api-2Xl7b6p2b0aL` |
| 6 | `GET /matches/{id}/rankings` | 球员排行榜 | `api-2Xl7b6p2b0aL` |
| 7 | `GET /matches/{id}/trends` | 实时趋势 + 胜率预测 | `api-2Xl7b6p2b0aL` |
| 8 | `GET /matches/{id}/summary` | 中场/全场复盘 | `api-2Xl7b6p2b0aL` |

## 1. 比赛列表

```
GET /matches
```

返回所有可查询分析数据的比赛。

**返回字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `source` | string | 数据来源：`livehub_match` 或 `manifest_files` |
| `count` | int | 比赛数量 |
| `matches[].match_id` | string | 比赛 ID |
| `matches[].date` | string | 日期 `YYYY-MM-DD` |
| `matches[].kickoff_local` | string | 开球本地时间 |
| `matches[].kickoff_utc` | string | 开球 UTC 时间 |
| `matches[].status` | int | `0` 未开赛 · `2` 已结束 |
| `matches[].competition` | string | 赛事名称 |
| `matches[].home / away` | object | `{id, name, logo}` |
| `matches[].score` | array | 比分 `[主, 客]` |
| `matches[].has_analysis` | bool | 是否可调分析接口 |

## 2. 整场详情

```
GET /matches/{id}?events=true|false
```

**参数**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `events` | bool | `true` | `false` 时只返回 box_score |

**返回字段**

| 字段 | 说明 |
|---|---|
| `status` / `phase` | 比赛状态/阶段 |
| `score` | `[主队, 客队]` |
| `teams[]` | `{id, name, side}` |
| `players[]` | 名册 `{id, name, team_id}` |
| `home_attacks_ltr_1h` | 上半场主队是否从左向右进攻 |
| `box_score.home/away` | 技术统计 |
| `events[]` | 处理后事件流 |

**`box_score` 子字段：** `score` · `shots` · `shots_on_target` · `possession_pct` · `pass_accuracy_pct` · `passes_attempted` · `passes_completed` · `fouls` · `yellow_cards` · `red_cards`

**`events[]` 每项字段**

| 字段 | 说明 |
|---|---|
| `id` | 事件 ID |
| `period` | 1 上半场 · 2 下半场 · 3/4 加时 |
| `minute` | 比赛钟 `MM:SS` |
| `type` | 事件类型：`Pass` / `Goal` 等 |
| `side` / `team_id` | `home`/`away` / 球队 ID |
| `player_id` | 执行球员 |
| `outcome` | `1` 成功 / `0` 失败 |
| `x, y` | 起点坐标 `[0,100]` |
| `end_x, end_y` | 终点坐标 |
| `received_by` | 接球球员（仅传球） |
| `chain_id` | 控球回合 ID |
| `score_after` | 该事件后比分 |

## 3. 传球网络

```
GET /matches/{id}/passing-network?span=full
```

**返回字段**

| 字段 | 说明 |
|---|---|
| `nodes[]` | `{player_id, name, x, y}` 球员平均位置 |
| `edges[]` | `{passer, receiver, passes}` 传球次数 |
| `passes_total` | 总传球数 |

## 4. 丢失球/抢断热点

```
GET /matches/{id}/turnovers?span=full
```

**返回字段**

| 字段 | 说明 |
|---|---|
| `x, y` | 位置 `[0,100]` |
| `kind` | `recovery` · `interception` · `tackle` |

## 5. 射门图（含 xG）

```
GET /matches/{id}/shots?span=full
```

**返回字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `shots` | `array` | 射门列表（**注意：顶层字段为 `shots`，不是直接返回数组**） |
| `shots[].player_id` | int | 射门球员 |
| `shots[].name` | string | 球员名称 |
| `shots[].minute` | string | 时间 `MM:SS` |
| `shots[].x, y` | float | 射门位置 `[0,100]` |
| `shots[].xg` | float | 预期进球 `[0,1]` |
| `shots[].outcome` | string | `goal` · `off_target` · `woodwork` · `saved_or_blocked` |
| `shots[].is_goal` | bool | 是否进球 |
| `shots[].own_goal` | bool | 是否乌龙 |

**⚠️ 前端消费注意**：API 返回的是 `{ shots: [...] }` 对象，不是直接数组。必须取 `response.shots` 再传给组件，禁止直接 `setShots(shotsData)`。
```typescript
// ✅ 正确：解构取 shots 数组
const { shots } = await fetchShots(matchId);
setShots(shots || []);

// ❌ 错误：直接传入会导致 .filter is not a function
// setShots(shotsData);  // 如果 shotsData 是 { shots: [...] } 对象
```

## 6. 球员排行榜

```
GET /matches/{id}/rankings?span=full&metric=threat
```

**可选 `metric`**

| 值 | 说明 |
|---|---|
| `threat` | 威胁值（核心球员综合评分） |
| `xg` | 预期进球 |
| `goals` | 进球 |
| `assists` | 助攻 |
| `passes_acc` | 成功传球 |
| `recoveries` | 抢回 |
| `interceptions` | 拦截 |
| `tackles_won` | 成功抢断 |
| `take_ons_won` | 成功过人 |
| `lost_poss` | 丢失球权 |

**返回字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `rankings` | `array?` | 排行榜列表（**优先字段**） |
| `players` | `array?` | 球员列表（**备选字段**，API 可能返回此字段替代 `rankings`） |
| `rankings[].player_id` / `players[].player_id` | int | 球员 ID |
| `rankings[].name` / `players[].name` | string | 球员名称 |
| `rankings[].value` / `players[].value` | float | 指标值 |

**⚠️ 前端消费注意**：API 可能返回 `rankings` 或 `players` 字段，必须做双字段兼容，禁止直接 `data.rankings.map()`。
```typescript
// ✅ 正确：双字段兼容，始终保证是数组
const rankings = data?.rankings || data?.players || [];
setRankings(rankings);

// ❌ 错误：假设一定有 rankings 字段
// data.rankings.map(...)  // 如果 API 返回 players 会崩溃
```

## 7. 实时趋势 + 胜率预测

```
GET /matches/{id}/trends
```

**`series[]` 每项**

| 字段 | 说明 |
|---|---|
| `minute` | 比赛分钟 |
| `possession` | `{home, away}` 控球率 % |
| `ball_territory` | 场地倾斜 `[0,1]` |
| `lane_dominance` | 左中右路占比 |
| `passnet_surface` | 传球网络覆盖球场百分比 |

**`prediction[]` 每项**

| 字段 | 说明 |
|---|---|
| `minute` | 比赛分钟 |
| `win_probability` | `{home, draw, away}` 胜/平/负概率 |
| `next_goal` | `{home, away}` 下一球归属概率 |
| `predicted_final_score` | 预测最终比分 |
| `current_score` | 当时比分 |

## 8. 中场/全场复盘

```
GET /matches/{id}/summary?kind=halftime|fulltime
```

**返回字段**

| 字段 | 说明 |
|---|---|
| `summary` | `halftime` / `fulltime` |
| `minute, score` | 复盘时刻、当时比分 |
| `team_stats.home/away` | 控球率、路权、传球覆盖、累计统计 |
| `shots / turnovers / passing_network` | 空间视图 |
| `rankings` | 排行榜 |
| `prediction` | 预测快照 |

## 术语表

| 术语 | 含义 |
|---|---|
| **threat 威胁值** | 价值模型的"行动价值"，衡量创造威胁的能力 |
| **xG 预期进球** | 一次射门成为进球的概率 `[0,1]` |
| **possession 控球率** | 以传球占比近似 |
| **ball territory 场地倾斜** | 比赛重心偏向哪半场，0.5 均衡 |
| **lane dominance 路权** | 进攻在左/中/右路的占比 |
| **passnet surface 覆盖面积** | 传球球员平均位置凸包占球场的百分比 |
| **chain_id 控球回合** | 一队连续控球的事件归为同一回合 |

## 调用建议

1. **缓存**：同一 `(match_id, endpoint, span)` 字节级一致，可长期缓存
2. **名字解析**：通过 `GET /matches/{id}` 的 `players[]` / `teams[]` 名册映射
3. **坐标渲染**：按 `home_attacks_ltr_1h` 处理两队两个半场的真实朝向
4. **版本兼容**：新增字段不视为破坏性变更，未知字段忽略

## 调用示例

### 生成期用法（Agent 直接调用网关）

Agent 直接调用上游网关 API，使用 `api-{ID}@app-d2y8vp5n1s75-api-pLVz4JBJbvKL-gateway.appmiaoda.com` 格式和 `X-Gateway-Authorization` 请求头。

**cURL**
```bash
curl -H "X-Gateway-Authorization: Bearer $INTEGRATIONS_API_KEY" \
  "https://app-d2y8vp5n1s75-api-pLVz4JBJbvKL-gateway.appmiaoda.com/py-api/matches/2026-03-20_Bournemouth_2-2_Manchester-United/shots?span=full"
```

**Python**
```python
import requests
import os

BASE = "https://app-d2y8vp5n1s75-api-pLVz4JBJbvKL-gateway.appmiaoda.com/py-api"
api_key = os.environ["INTEGRATIONS_API_KEY"]
mid = "2026-03-20_Bournemouth_2-2_Manchester-United"
shots = requests.get(
    f"{BASE}/matches/{mid}/shots",
    params={"span": "full"},
    headers={"X-Gateway-Authorization": f"Bearer {api_key}"}
).json()
```

**JavaScript**
```javascript
const apiKey = process.env["INTEGRATIONS_API_KEY"];
const res = await fetch(
  `https://app-d2y8vp5n1s75-api-pLVz4JBJbvKL-gateway.appmiaoda.com/py-api/matches/${mid}/rankings?span=full&metric=threat`,
  { headers: { "X-Gateway-Authorization": `Bearer ${apiKey}` } }
);
const data = await res.json();
```

### 生成后用法（应用内通过 Edge Function 调用）

**前端严禁直接调用网关**，必须通过 Supabase Edge Function 代理，由服务端注入密钥。

**前端调用方式（必须使用 `supabase.functions.invoke`）：**
```typescript
// ✅ 正确：使用 Supabase SDK 调用 Edge Function
const { data, error } = await supabase.functions.invoke("pitch-pilot-proxy", {
  headers: { "x-path": "/matches/2026-03-20_Bournemouth_2-2_Manchester-United/shots?span=full" }
});

// ❌ 错误：相对路径在沙箱环境无代理转发，status 返回 0
// const res = await fetch("/api/pitch-pilot-proxy/matches/...");  // 不要这样写
```

**Edge Function 合约：**
- Edge Function 名称为 `pitch-pilot-proxy`
- 前端通过 `x-path` 请求头传递目标路径（如 `/matches/{id}/shots?span=full`）
- Edge Function 读取 `Deno.env.get("INTEGRATIONS_API_KEY")` 注入 `X-Gateway-Authorization`
- Edge Function 根据 `x-path` 路由到对应的网关 API ID，调用上游后返回 JSON
- CORS 必须允许 `x-path` 请求头