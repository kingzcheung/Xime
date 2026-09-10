-- 输入统计插件（Lua 脚本插件，演示 capabilities.events 下行事件 + passive 展示面板）
--
-- 职责划分：
--   Lua   = 事件累计（text_committed 增量持久化）+ 速度/日/周/月聚合 + 称号分级 + 面板节点树
--   宿主  = 事件投递（conflated 快照）+ InfoPanel 渲染（display: passive）+ action 回调
--
-- 事件语义（snake_case）：
--   text_committed: { committed_text, session_total_chars, session_total_commits, is_paste }
--     - session_* 为宿主进程生命周期累计；conflated 丢中间事件不影响统计（差值增量）
--     - 宿主重启后 session 归零：delta 为负时视为新会话起点
--     - 插件重载后：last_seen 持久化，首次差值按 0，避免重复累计
--     - is_paste = 粘贴性质上屏（剪贴板点选/编辑面板提交），不计入打字量
--   input_changed:  { input_text }  当前编码快照（高频，仅内存不落盘）
--
-- 沙箱约束：无 os/io——日期与速度的时间源为 host.crypto.utcTime（UTC），
-- 日期差值用儒略日纯算术实现，速度用 60 秒滑动窗口（内存态，不落盘）。

local plugin = {}

local KEY_TOTAL_CHARS = "total_chars"
local KEY_TOTAL_COMMITS = "total_commits"
local KEY_DAILY = "daily"
local KEY_LAST_SEEN = "last_seen_chars"

-- 称号分级（按累计字数）：降序匹配第一个满足项；badge 为面板展示徽章
local TITLES = {
  { min = 500000, name = "传奇笔仙", badge = "👑" },
  { min = 100000, name = "一代宗师", badge = "💎" },
  { min = 20000,  name = "键上飞侠", badge = "🥇" },
  { min = 5000,   name = "熟练写手", badge = "🥈" },
  { min = 1000,   name = "入门学徒", badge = "🥉" },
  { min = 0,      name = "新手",     badge = "🌱" },
}

-- 明细保留天数（防止 host.config 无限膨胀）
local DAILY_KEEP_DAYS = 400

-- ===== 状态 =====
local totalChars = tonumber(host.config.get(KEY_TOTAL_CHARS)) or 0
local totalCommits = tonumber(host.config.get(KEY_TOTAL_COMMITS)) or 0
-- 首次使用（无记录）时从当前快照起算，不回溯历史（差值按 0）
local lastSeenChars = tonumber(host.config.get(KEY_LAST_SEEN))
local daily = host.json.decode(host.config.get(KEY_DAILY) or "") or {}
local currentInput = ""
-- 打字速度滑动窗口：{ {ts = 绝对秒, chars = 增量}, ... }（仅内存）
local speedWindow = {}

-- ===== 时间工具（沙箱无 os，纯算术） =====

-- "YYYYMMDD" → 儒略日天数（Fliegel & Van Flandern 公式），用于日期差值
local function ymdToDays(y, m, d)
  local a = math.floor((14 - m) / 12)
  local y2 = y + 4800 - a
  local m2 = m + 12 * a - 3
  return d + math.floor((153 * m2 + 2) / 5) + 365 * y2
    + math.floor(y2 / 4) - math.floor(y2 / 100) + math.floor(y2 / 400) - 32045
end

local function parseYmd(key)
  local y, m, d = tonumber(key:sub(1, 4)), tonumber(key:sub(5, 6)), tonumber(key:sub(7, 8))
  if y == nil or m == nil or d == nil then return nil end
  return y, m, d
end

local function todayStr()
  return host.crypto.utcTime("YYYYMMDD")
end

-- "YYYYMMDDTHHMMSSZ" → 自洽的绝对秒数（仅用于差值比较）
local function nowSec()
  local s = host.crypto.utcTime("YYYYMMDDTHHMMSSZ")
  local y, m, d = parseYmd(s)
  if y == nil then return 0 end
  local hh = tonumber(s:sub(10, 11)) or 0
  local mi = tonumber(s:sub(12, 13)) or 0
  local ss = tonumber(s:sub(14, 15)) or 0
  return ymdToDays(y, m, d) * 86400 + hh * 3600 + mi * 60 + ss
end

-- ===== 聚合 =====

-- 近 daysBack 天（含今日）的字数合计
local function sumRecentDays(daysBack)
  local total = 0
  local t = todayStr()
  local by, bm, bd = parseYmd(t)
  if by == nil then return 0 end
  local base = ymdToDays(by, bm, bd)
  for key, v in pairs(daily) do
    local y, m, d = parseYmd(key)
    if y then
      local diff = base - ymdToDays(y, m, d)
      if diff >= 0 and diff < daysBack then total = total + v end
    end
  end
  return total
end

-- 自然月（"YYYYMM"）字数合计
local function sumMonth(monthKey)
  local total = 0
  for key, v in pairs(daily) do
    if key:sub(1, 6) == monthKey then total = total + v end
  end
  return total
end

-- 裁剪 daily 明细：只保留最近 DAILY_KEEP_DAYS 天
local function pruneDaily()
  local t = todayStr()
  local by, bm, bd = parseYmd(t)
  if by == nil then return end
  local base = ymdToDays(by, bm, bd)
  for key in pairs(daily) do
    local y, m, d = parseYmd(key)
    if y == nil or (base - ymdToDays(y, m, d)) >= DAILY_KEEP_DAYS then
      daily[key] = nil
    end
  end
end

-- ===== 称号 =====

local function resolveTitle()
  for _, t in ipairs(TITLES) do
    if totalChars >= t.min then return t.name, t.min, t.badge end
  end
  local last = TITLES[#TITLES]
  return last.name, last.min, last.badge
end

-- 下一级称号提示；已满级返回 nil
local function nextTitleHint()
  for i = #TITLES, 1, -1 do
    if totalChars < TITLES[i].min then
      return string.format("✨ 距「%s」还差 %d 字", TITLES[i].name, TITLES[i].min - totalChars)
    end
  end
  return nil
end

-- ===== 速度 =====

local function recordSpeed(tsSec, delta)
  if delta <= 0 then return end
  table.insert(speedWindow, { ts = tsSec, chars = delta })
  -- 清理窗口外数据（保留 2 倍窗口余量，避免边界抖动）
  local cutoff = tsSec - 120
  local kept = {}
  for _, e in ipairs(speedWindow) do
    if e.ts >= cutoff then table.insert(kept, e) end
  end
  speedWindow = kept
end

-- 最近 60 秒上屏字数 ≈ 字/分钟
local function currentKpm(now)
  local total = 0
  for _, e in ipairs(speedWindow) do
    if now - e.ts <= 60 then total = total + e.chars end
  end
  return total
end

-- ===== 持久化 =====

local function persist()
  pruneDaily()
  host.config.set(KEY_TOTAL_CHARS, tostring(totalChars))
  host.config.set(KEY_TOTAL_COMMITS, tostring(totalCommits))
  host.config.set(KEY_LAST_SEEN, tostring(lastSeenChars))
  host.config.set(KEY_DAILY, host.json.encode(daily))
end

-- ===== 事件 =====

function plugin.onPluginEvent(eventType, payload)
  if eventType == "text_committed" and payload ~= nil then
    local sessionChars = payload.session_total_chars or 0
    local delta = sessionChars - (lastSeenChars or sessionChars)
    lastSeenChars = sessionChars
    if delta < 0 then
      -- 宿主重启（session 归零）：新会话起点，本快照即增量
      delta = sessionChars
    end
    -- is_paste（键盘剪贴板点选/编辑面板提交）：事件照收以推进差值基准，
    -- 但粘贴不是打字，不计字数/提交次数/速度
    if not payload.is_paste then
      if delta > 0 then
        totalChars = totalChars + delta
        local d = todayStr()
        daily[d] = (daily[d] or 0) + delta
        recordSpeed(nowSec(), delta)
      end
      totalCommits = totalCommits + 1
    end
    persist()
  elseif eventType == "input_changed" and payload ~= nil then
    -- 高频事件：只更新内存态，不写盘
    currentInput = payload.input_text or ""
  end
end

-- ===== 面板（display: passive，声明式 ui 节点树） =====

function plugin.getPanelState(inputText)
  local title, _, badge = resolveTitle()
  local hint = nextTitleHint()
  local now = nowSec()
  local ui = {
    { type = "section", label = "🏆 称号" },
    { type = "metric",  label = "当前称号", value = (badge or "") .. " " .. title },
  }
  if hint ~= nil then
    table.insert(ui, { type = "text", value = hint, style = "caption" })
  else
    table.insert(ui, { type = "text", value = "👑 已是最高称号", style = "caption" })
  end

  table.insert(ui, { type = "section", label = "⚡ 速度" })
  table.insert(ui, { type = "metric", label = "💨 最近 1 分钟", value = tostring(currentKpm(now)), unit = "字/分" })

  local t = todayStr()
  table.insert(ui, { type = "section", label = "📅 今日" })
  table.insert(ui, { type = "metric", label = "✍️ 输入字数", value = tostring(daily[t] or 0), unit = "字" })
  table.insert(ui, { type = "metric", label = "🔢 提交次数", value = tostring(totalCommits) })

  table.insert(ui, { type = "section", label = "🗓 近 7 天" })
  table.insert(ui, { type = "metric", label = "✍️ 输入字数", value = tostring(sumRecentDays(7)), unit = "字" })

  table.insert(ui, { type = "section", label = "📆 本月" })
  table.insert(ui, { type = "metric", label = "✍️ 输入字数", value = tostring(sumMonth(t:sub(1, 6))), unit = "字" })

  if currentInput ~= "" then
    table.insert(ui, { type = "text", value = "⌨️ 正在输入: " .. currentInput, style = "caption" })
  end
  table.insert(ui, { type = "divider" })
  table.insert(ui, { type = "button", label = "🗑️ 清零统计", key = "reset" })

  return { inputText = inputText or "", items = {}, ui = ui, loading = false }
end

function plugin.onPanelItemClick(itemId)
  -- passive 面板点击节点不上屏
end

function plugin.onPanelAction(actionId)
  if actionId == "reset" then
    totalChars = 0
    totalCommits = 0
    lastSeenChars = nil
    daily = {}
    speedWindow = {}
    persist()
  end
end

return plugin
