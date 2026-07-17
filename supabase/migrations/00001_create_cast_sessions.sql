
-- 投屏接收端在线会话表
-- 每台运行 App 的设备 upsert 一条记录来宣告自己在线
create table cast_sessions (
  id          uuid primary key default gen_random_uuid(),
  device_id   text not null unique,   -- 设备唯一标识（客户端生成 UUID）
  device_name text not null default '投屏助手',
  ip          text not null,          -- 局域网 IP
  port        integer not null default 7788,
  last_seen   timestamptz not null default now(),
  created_at  timestamptz not null default now()
);

-- 开启 RLS
alter table cast_sessions enable row level security;

-- anon 可以读取所有在线设备（发送端需要）
create policy "anon_select_cast_sessions"
  on cast_sessions for select
  to anon
  using (true);

-- anon 可以 insert（App 首次上线）
create policy "anon_insert_cast_sessions"
  on cast_sessions for insert
  to anon
  with check (true);

-- anon 可以 update 自己的记录（心跳续期）
create policy "anon_update_cast_sessions"
  on cast_sessions for update
  to anon
  using (true)
  with check (true);

-- anon 可以 delete 自己的记录（App 下线清理）
create policy "anon_delete_cast_sessions"
  on cast_sessions for delete
  to anon
  using (true);

-- 投屏指令表：发送端写入，接收端轮询读取
create table cast_commands (
  id          bigserial primary key,
  device_id   text not null references cast_sessions(device_id) on delete cascade,
  type        text not null,     -- play | playlist | subtitle | pause | resume | seek | stop
  payload     jsonb,
  created_at  timestamptz not null default now()
);

-- 自动清理 30 秒前的旧指令（防止积压）
create index cast_commands_device_created on cast_commands(device_id, created_at desc);

alter table cast_commands enable row level security;

create policy "anon_select_cast_commands"
  on cast_commands for select
  to anon
  using (true);

create policy "anon_insert_cast_commands"
  on cast_commands for insert
  to anon
  with check (true);

create policy "anon_delete_cast_commands"
  on cast_commands for delete
  to anon
  using (true);
