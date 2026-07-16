---
name: seedance-2-video-generation
description: Seedance 2.0 (Doubao) AI 视频生成，支持文生视频、图生视频（首帧/首尾帧）、多模态创作（图片+音频）。基于最新 v1.3 API，使用 content 数组结构描述多模态素材。当用户需要 AI 生成视频、创建视频内容或管理视频生成任务时触发。
license: MIT
---

## 能力概述

调用 Doubao Seedance 2.0 模型进行 AI 视频生成，支持通过文本提示、参考图片和参考音频等多模态素材生成高质量视频。

| 属性 | 值 |
|------|-----|
| 认证模式 | `platform_managed`（密钥由平台注入） |
| 密钥来源 | `process.env["INTEGRATIONS_API_KEY"]` |
| Auth Header | `Authorization: Bearer <api-key>` |
| 支持平台 | Web、MiniProgram |
| 响应格式 | JSON，直接返回任务对象（无 code/message/data 包装） |

**接口列表：**

| 接口 | 方法 | Endpoint | 说明 |
|------|------|----------|------|
| 创建视频任务 | POST | `https://app-d2y8vp5n1s75-api-nYWN4kMJ1v7L-gateway.appmiaoda.com/doubao/v3/contents/generations/tasks` | 根据多模态素材生成视频 |
| 查询视频任务 | GET | `https://app-d2y8vp5n1s75-api-Q9KWPzO1Eg69-gateway.appmiaoda.com/doubao/v3/contents/generations/tasks/{task_id}` | 查询任务状态和结果 |

**支持的模型：**

| 模型 | 说明 |
|------|------|
| `doubao-seedance-2-0-260128` | 标准版，支持文生视频、图生视频、多模态创作，支持 1080p 分辨率 |
| `doubao-seedance-2-0-fast-260128` | 快速版，生成速度更快，仅支持 480p/720p |

**核心能力：**

- **文生视频**：通过 `content` 数组中的 `text` 类型描述生成全新视频
- **图生视频-首帧**：`image_url` + `role: "first_frame"`，基于首帧图片生成后续内容
- **图生视频-首尾帧**：`image_url` + `role: "first_frame"` + `role: "last_frame"`，生成两帧之间的过渡视频
- **多模态创作**：同时参考图片、音频素材，生成融合后的视频
- **虚拟人像**：使用 `asset://<asset_id>` 格式引用火山官方预置虚拟人像素材

**素材输入方式（前端实现时必须支持）：**

| 方式 | 说明 | 适用场景 |
|------|------|---------|
| **直接上传** | 用户在生成页面直接选择本地文件，前端自动转为 Base64 或先上传到 Supabase Storage | 最常用，无需预注册素材 |
| **素材库选择** | 从已注册的素材表中选择素材 | 重复使用同一素材 |
| **URL 输入** | 用户直接粘贴公网 URL | 使用外部资源 |

> **重要**：前端生成页面必须同时支持「直接上传」「素材库选择」「URL 粘贴」三种方式。直接上传通过 Base64 传递到 Edge Function，Edge Function 会自动转换为 Storage URL。

详细参数说明、代码示例及完整实现见 `references/video-generation-api.md`。

---

## 生成期用法（Agent 直接调用）

视频生成是**异步**流程。调用创建接口获得 `id`（任务 ID）后，需要通过查询接口轮询任务状态，直到 `succeeded` 或 `failed`。获得视频 URL 后**必须立即下载到本地**（CDN 链接有时效性）。

```typescript
const apiKey = process.env["INTEGRATIONS_API_KEY"]!;

interface ContentItem {
  type: "text" | "image_url" | "audio_url";
  text?: string;
  image_url?: { url: string };
  audio_url?: { url: string };
  role?: "reference_image" | "first_frame" | "last_frame" | "reference_audio";
}

interface CreateVideoTaskParams {
  model: string;
  content: ContentItem[];
  generate_audio?: boolean;
  ratio?: string;
  duration?: number;
  watermark?: boolean;
  resolution?: string;
}

interface VideoTaskResponse {
  id: string;
  model: string;
  status: "succeeded" | "processing" | "failed" | "queued";
  content?: { video_url?: string };
  usage?: { completion_tokens: number; total_tokens: number };
  created_at: number;
  updated_at: number;
  seed?: number;
  resolution?: string;
  ratio?: string;
  duration?: number;
  framespersecond?: number;
  service_tier?: string;
  execution_expires_after?: number;
  generate_audio?: boolean;
  draft?: boolean;
}

/** 创建视频生成任务 */
async function createVideoTask(params: CreateVideoTaskParams): Promise<VideoTaskResponse> {
  const response = await fetch("https://app-d2y8vp5n1s75-api-nYWN4kMJ1v7L-gateway.appmiaoda.com/doubao/v3/contents/generations/tasks", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${apiKey}`,
    },
    body: JSON.stringify(params),
  });

  if (!response.ok) throw new Error(`HTTP error: ${response.status}`);
  return response.json();
}

/** 查询视频任务状态 */
async function queryVideoTask(taskId: string): Promise<VideoTaskResponse> {
  const response = await fetch(
    `https://app-d2y8vp5n1s75-api-Q9KWPzO1Eg69-gateway.appmiaoda.com/doubao/v3/contents/generations/tasks/${taskId}`,
    {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${apiKey}`,
      },
    }
  );

  if (!response.ok) throw new Error(`HTTP error: ${response.status}`);
  return response.json();
}

/** 创建任务并轮询等待完成，返回视频 URL */
async function generateVideoAndWait(params: CreateVideoTaskParams): Promise<string> {
  const createResult = await createVideoTask(params);
  const taskId = createResult.id;

  const POLL_INTERVAL_MS = 7000;
  const TIMEOUT_MS = 10 * 60 * 1000;
  const deadline = Date.now() + TIMEOUT_MS;

  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
    const result = await queryVideoTask(taskId);

    if (result.status === "succeeded" && result.content?.video_url) {
      return result.content.video_url;
    }
    if (result.status === "failed") {
      throw new Error(`视频生成失败: ${JSON.stringify(result)}`);
    }
    // queued / processing → 继续轮询
  }

  throw new Error(`任务 ${taskId} 超时（10 分钟未完成）`);
}
```

**生成期文件下载（必须执行）：**

视频生成接口返回的视频 URL 是 CDN 临时链接，在生成期（Agent 直接调用场景）获得 URL 后，**必须立即使用 Bash 工具将文件下载到本地**，以便用户查看结果。

```bash
curl -L -o <本地路径>.mp4 "<视频 URL>"
```

**完整生成期工作流（含下载步骤）：**

1. 根据用户需求准备参数（选择模型、编写 prompt、准备参考素材 URL）
2. 如需使用虚拟人像，先查阅火山虚拟人像库获取 `asset_id`
3. 调用 `generateVideoAndWait(params)` 获取视频 URL
4. 使用 Bash 工具执行 `curl -L -o <本地路径>.mp4 "<视频 URL>"` 将视频下载到本地
5. 告知用户视频已保存到对应路径

> **注意**：上游 CDN 链接有时效性，应在获得 URL 后立即下载，不要延迟。

---

## 生成后用法（应用内通过 Edge Function 调用）

应用内通过 Edge Function 安全调用上游 API，密钥不暴露给前端。

**安全合约：**
- 前端只发送业务参数到 Edge Function，不接触 API Key
- Edge Function 从 `Deno.env.get("INTEGRATIONS_API_KEY")` 读取密钥
- 请求上游时注入 `Authorization: Bearer ${apiKey}`
- `429`（配额超限）和 `402`（余额不足）错误体原样透传给前端
- 返回的视频 URL 需通过 Supabase Storage 转存为持久链接后再返回给客户端

**前端轮询策略规范（必须实现，绝不能只提供手动刷新按钮）：**

视频生成是异步任务，前端提交后**必须自动实时跟踪状态**，绝对不能依赖用户手动刷新页面或点击刷新按钮。必须实现以下机制：

1. **渐进式轮询（核心机制）**：
   - 任务提交后 **立即** 开始轮询，不要等待
   - **前 30 秒**：每 **3 秒** 查询一次（快速响应阶段）
   - **30 秒后**：每 **7 秒** 查询一次（稳定等待阶段）
   - **超时**：10 分钟后停止轮询，标记为超时失败
   - **绝对不能只提供手动刷新按钮**，用户提交后应自动看到状态变化

2. **Supabase Realtime 订阅（必须同时使用）**：
   - 订阅 `video_tasks` 表的 `UPDATE` 事件
   - 当数据库中的任务状态更新时，Realtime 立即推送变更到前端
   - 轮询作为 Realtime 的降级兜底方案
   - **重要：所有展示任务列表的组件（包括 HistoryPanel、任务列表页等）都必须订阅 Realtime，不能只依赖初始加载**

3. **Edge Function 查询后必须更新数据库**：
   - `video-generations-query` Edge Function 返回结果后，前端必须将状态和视频 URL 更新到 `video_tasks` 表
   - **数据库更新必须有错误处理**：使用 `{ error: updateError }` 解构检查更新结果，如果失败返回 500 错误
   - 这样 Realtime 订阅才能及时推送状态变更
   - **特别重要**：`video_url` 字段必须在状态变为 `SUCCESS` 时立即更新，否则用户无法预览和下载视频
   - **致命约束**：`supabase.functions.invoke()` 不支持在函数名中拼接 query string，必须把 `task_id` 放在 `body` 中传递

**Edge Function 实现：**
- `video-generations`：代理创建视频任务接口（含 Base64 图片自动转换）
- `video-generations-query`：代理查询视频任务接口（含视频 URL 转存逻辑）

完整 Edge Function 代码和前端调用代码详见 `references/video-generation-api.md`。

---

## 参数说明

### content 数组结构（核心参数）

**content 数组必须包含至少一个 `type: "text"` 项作为提示词。**

**文本（提示词）**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定值 `"text"` |
| `text` | string | 是 | 提示词内容，描述要生成的视频内容 |

**参考图片**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定值 `"image_url"` |
| `image_url.url` | string | 是 | 图片地址，支持公网 URL 或 `asset://<asset_id>` |
| `role` | string | 是 | `"reference_image"`（参考图）/ `"first_frame"`（首帧）/ `"last_frame"`（尾帧） |

- 限制：1-9 张，格式 jpeg/png/webp/bmp/tiff/gif，宽高比 (0.4, 2.5)，边长 300-6000px

**参考音频**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 固定值 `"audio_url"` |
| `audio_url.url` | string | 是 | 音频地址，支持公网 URL 或 `asset://<asset_id>` |
| `role` | string | 是 | 固定值 `"reference_audio"` |

- 限制：最多 3 段，总时长 ≤ 15 秒，单个 ≤ 15 MB，格式 wav/mp3

### 其他参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | 是 | 模型 ID：`doubao-seedance-2-0-260128`（支持 1080p）/ `doubao-seedance-2-0-fast-260128`（仅 480p/720p） |
| `content` | array | 是 | 多模态内容数组，至少包含一个 `text` 项 |
| `generate_audio` | boolean | 否 | `true` 含同步音频；`false` 无声视频 |
| `ratio` | string | 否 | 视频比例：`16:9`、`4:3`、`1:1`、`3:4`、`9:16`、`21:9`、`adaptive` |
| `duration` | number | 否 | 视频时长（秒），默认 5，`480p`/`720p` 范围 4~15。**当 `resolution` 为 `1080p` 时，`duration` 范围 4~8，超出 8 秒会报错。** |
| `watermark` | boolean | 否 | `true` 含水印；`false` 不含水印 |
| `resolution` | string | 否 | 分辨率：`480p`、`720p`、`1080p`（仅 `doubao-seedance-2-0-260128` 标准版支持 1080p，fast 版最高 720p），默认 `720p` |

---

## 响应格式

### 创建/查询任务响应（统一格式）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 任务 ID（如 `doubao.p1.cgt-2025******-****`） |
| `model` | string | 使用的模型 |
| `status` | string | `succeeded` / `processing` / `failed` / `queued` |
| `content.video_url` | string? | 生成完成的视频 URL（`succeeded` 时存在） |
| `usage.completion_tokens` | number | 完成 token 数 |
| `usage.total_tokens` | number | 总 token 数 |
| `created_at` | number | 创建时间戳 |
| `updated_at` | number | 更新时间戳 |
| `seed` | number | 随机种子 |
| `resolution` | string | 视频分辨率 |
| `ratio` | string | 视频比例 |
| `duration` | number | 视频时长 |
| `framespersecond` | number | 帧率 |
| `generate_audio` | boolean | 是否含音频 |
| `draft` | boolean | 是否为草稿 |

**状态映射（Edge Function 必须转换）：**

| 上游状态 | 前端/数据库状态 |
|----------|----------------|
| `succeeded` | `SUCCESS` |
| `processing` | `PROCESSING` |
| `failed` | `FAILED` |
| `queued` | `PENDING` |

---

## 注意事项

- **密钥安全**：`INTEGRATIONS_API_KEY` 仅可在 Edge Function 服务端读取，严禁暴露到前端代码或客户端环境变量中。
- **content 数组必须包含 text**：`content` 数组必须至少包含一个 `type: "text"` 项，否则 API 返回 400 错误。
- **role 字段必须正确**：首帧用 `"first_frame"`，尾帧用 `"last_frame"`，参考素材用 `"reference_image"`/`"reference_audio"`，错误的 role 会导致 400 错误。
- **虚拟人像**：使用 `asset://<asset_id>` 格式引用火山官方预置虚拟人像，需先查阅虚拟人像库获取 asset ID。文档地址：https://www.volcengine.com/docs/82379/2223965
- **异步工作流与轮询策略**：
  - 视频生成是异步的，前端提交任务后**必须立即开始轮询**
  - **渐进式轮询**：前 30 秒每 3 秒查询一次，之后每 7 秒查询一次，10 分钟超时
  - **必须同时使用 Supabase Realtime**：订阅 `video_tasks` 表的 UPDATE 事件，轮询作为兜底
  - Edge Function 查询返回后，前端必须更新 `video_tasks` 表以触发 Realtime 推送
- **Edge Function 绝不能同步下载视频文件（致命约束）**：
  - 上游视频文件通常几十 MB，同步下载到 Edge Function 会导致超时（Deno Deploy 限制执行时间）
  - **正确做法**：直接使用上游返回的带签名 URL（有效期 24 小时），前端直接播放
  - **如需转存**：使用异步策略（后台任务、队列、或 Supabase Storage 的 upload from URL）
  - **绝不能**在 Edge Function 内 `fetch(videoUrl)` 然后 `upload` 同步阻塞
- **状态值必须完整映射**：上游可能返回的状态包括 `queued` / `pending` / `processing` / `running` / `succeeded` / `success` / `completed` / `failed` / `error` / `cancelled` 等，必须完整映射为数据库约束的 `PENDING` / `PROCESSING` / `SUCCESS` / `FAILED`。特别注意：`running` 必须映射为 `PROCESSING`（不是 `FAILED`）。未知状态应保持 `PROCESSING` 继续轮询，绝不能直接返回 `FAILED`
- **1080p 时长上限**：选择 `1080p` 分辨率时，`duration` 最多 8 秒（范围 4~8），超出 8 秒会报错。Agent 生成参数时如果用户要求的时长超过 8 秒，自动改为 8 秒并告知用户"1080p 视频时长最多 8 秒"。
- **前端必须自动轮询 + 自动展示结果**：
  - 提交任务后前端立即启动轮询（前30秒3秒间隔，之后7秒间隔），同时订阅 Supabase Realtime
  - 状态变为 `SUCCESS` 且拿到 `video_url` 后，页面**自动**显示视频预览（`<video>` 标签）和下载按钮，无需用户手动刷新或点击「查看结果」
  - **所有展示任务状态的组件（生成页、历史面板、任务列表等）都必须实时同步状态，不能只依赖初始加载时的一次查询**
  - **数据库更新必须有错误处理**：Edge Function 更新 `video_tasks` 表时，必须使用 `{ error: updateError }` 解构检查更新结果，如果失败返回 500 错误，确保前端能感知更新失败
  - **致命约束**：`supabase.functions.invoke()` 不支持在函数名中拼接 query string，必须把 `task_id` 放在 `body` 中传递
  - **致命约束（闭包陷阱）**：React state 更新是异步的，轮询 Hook 内部必须用 `ref` 存储最新 `taskId`，不能依赖闭包捕获的 state 值，否则会出现 `taskId=null` 导致轮询直接退出的竞态问题
  - **致命约束（轮询回调依赖 task state）**：轮询的 `onResult` 回调绝不能依赖闭包中的 `task` 变量。错误写法 `if (!task) return;` 会导致回调创建时 task 为 null，后续即使 task 被设置，闭包中的 task 仍然是 null，所有成功结果都被丢弃，页面永远显示"生成中"。正确做法：回调中用函数式更新（`setTask(prev => ...)`）或通过 `taskId` 重新拉取完整数据更新
  - **历史记录页必须有轮询兜底**：历史记录/任务列表页不能只依赖 Realtime，必须对非终态任务（`PENDING`/`PROCESSING`）每 5 秒轮询一次：调用 `queryVideoTask` 刷新上游状态 → 重新加载列表。避免 Realtime 丢事件时页面状态卡死
- **媒体文件时效性**：生成期用法中，Agent 获得视频 URL 后**必须立即下载**到本地；Edge Function 中**绝不能同步下载整个视频文件**（几十 MB 会导致超时），直接使用上游返回的带签名 URL（有效期 24 小时），或采用异步转存策略
- **素材输入方式（前端必须实现）**：
  - **直接上传**：在生成页面直接选择本地文件，转为 Base64 传给 Edge Function
  - **素材库选择**：从已注册的素材表中选择
  - **URL 粘贴**：允许用户直接输入公网 URL
  - 生成页面必须同时提供这三种方式
- **素材限制**：
  - 参考图片：1-9 张，格式 jpeg/png/webp/bmp/tiff/gif，宽高比 (0.4, 2.5)，边长 300-6000px
  - 参考音频：最多 3 段，总时长 ≤ 15 秒，单个 ≤ 15 MB，格式 wav/mp3
- **错误处理**：
  - `429` — 配额已用尽
  - `402` — 余额不足
  - `400` — 请求参数错误（content 格式错误、role 不正确、素材超限等）
  - HTTP 非 200 — 网络或服务器错误
