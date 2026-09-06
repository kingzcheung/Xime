-- =====================================================================
-- WebDAV 云备份插件
--
-- 备份包的生成与恢复由宿主（BackupManager）完成，本插件只承载 WebDAV
-- 传输协议：PUT / GET / PROPFIND / DELETE / MKCOL，认证用 Basic Auth
-- （host.crypto.base64），配置经 host.config 存取（密码为宿主加密存储）。
--
-- 备份条目 id = 服务器上的绝对路径（已解码），pull/delete 时原样回传。
-- =====================================================================

local plugin = {}

-- ------------------------------------------------------------------
-- 配置
-- ------------------------------------------------------------------

local function getConfig()
    return {
        url = host.config.get("url") or "",
        username = host.config.get("username") or "",
        password = host.config.get("password") or "",
        remotePath = host.config.get("remote_path") or "/xime_backup",
    }
end

-- 归一化：url 去尾斜杠，remotePath 去首尾斜杠。
-- 返回：
--   base     完整 URL（origin + url 路径前缀 + remotePath），PROPFIND/PUT 用
--   basePath 服务器绝对路径（如坚果云为 /dav/xime_backup），条目 id / 列表过滤用
--   davRoot  origin + url 路径前缀（如 https://dav.jianguoyun.com/dav），MKCOL 起点用
--   headers  认证头
local function resolveBase()
    local cfg = getConfig()
    if cfg.url == "" then return nil, nil, nil, nil, cfg, "请先填写服务器地址" end
    if cfg.username == "" then return nil, nil, nil, nil, cfg, "请先填写账号" end
    local url = cfg.url
    if not url:match("^https?://") then url = "https://" .. url end
    url = url:gsub("/+$", "")
    local origin = url:match("^https?://[^/]+")
    local urlPath = url:sub(#origin + 1)          -- DAV 根的路径前缀（坚果云为 /dav）
    local remote = cfg.remotePath:gsub("^/+", ""):gsub("/+$", "")
    local base = origin .. urlPath .. "/" .. remote
    local basePath = urlPath .. "/" .. remote
    local davRoot = origin .. urlPath
    local headers = {}
    if cfg.password ~= "" then
        local b64 = host.crypto.base64(cfg.username .. ":" .. cfg.password)
        if not b64 then return nil, nil, nil, nil, cfg, "Basic 认证编码失败" end
        headers["Authorization"] = "Basic " .. b64
    end
    return base, basePath, davRoot, headers, cfg, nil
end

-- ------------------------------------------------------------------
-- URL 编解码（Lua 沙箱无 luasocket，手写最小实现）
-- ------------------------------------------------------------------

-- 路径编码：逐字节循环（保留字母数字与 -._~:/，其余转 %XX）。
-- 注意不能用 gsub 模式回调：luaj 的模式匹配器对非 ASCII 字节流存在
-- 匹配单元错乱（实测 配置 → E9%85%8D%E7%BD%AE 中首字节漏编码），
-- 真机 PUT 中文文件名 400 即此原因，必须用 string.byte 逐字节处理。
local function encodePath(p)
    local out = {}
    for i = 1, #p do
        local b = string.byte(p, i)
        if (b >= 48 and b <= 57) or (b >= 65 and b <= 90) or (b >= 97 and b <= 122)
            or b == 45 or b == 46 or b == 95 or b == 126 or b == 47 or b == 58 then
            out[#out + 1] = string.char(b)
        else
            out[#out + 1] = string.format("%%%02X", b)
        end
    end
    return table.concat(out)
end

local function decodeSegment(s)
    return (s:gsub("%%(%x%x)", function(h)
        return string.char(tonumber(h, 16))
    end))
end

-- href / id → 服务器绝对路径（解码后的）
local function hrefToPath(href)
    if not href or href == "" then return nil end
    local p = href:match("^https?://[^/]+(/.*)$") or href
    if p:sub(1, 1) ~= "/" then p = "/" .. p end
    local decoded = {}
    for seg in p:gmatch("[^/]+") do
        decoded[#decoded + 1] = decodeSegment(seg)
    end
    return "/" .. table.concat(decoded, "/")
end

-- ------------------------------------------------------------------
-- 时间解析（沙箱无 os.time，手写 civil → epoch）
-- ------------------------------------------------------------------

local MONTHS = { Jan = 1, Feb = 2, Mar = 3, Apr = 4, May = 5, Jun = 6,
    Jul = 7, Aug = 8, Sep = 9, Oct = 10, Nov = 11, Dec = 12 }

-- Howard Hinnant days_from_civil（公历日期 → 自 1970-01-01 的天数）
local function epochFromParts(y, m, d, hh, mm, ss)
    if not (y and m and d) then return 0 end
    local yy = y
    if m <= 2 then yy = yy - 1 end
    local era = math.floor(yy / 400)
    local yoe = yy - era * 400
    local mp = (m + (m > 2 and -3 or 9))
    local doy = math.floor((153 * mp + 2) / 5) + d - 1
    local doe = yoe * 365 + math.floor(yoe / 4) - math.floor(yoe / 100) + doy
    local days = era * 146097 + doe - 719468
    return days * 86400 + (hh or 0) * 3600 + (mm or 0) * 60 + (ss or 0)
end

-- 优先 creationdate（ISO 8601），其次 getlastmodified（RFC 1123）
local function parseTime(block)
    local y, mo, d, hh, mm, ss = block:match("<[dD]?:?creationdate>%s*(%d%d%d%d)-(%d%d)-(%d%d)T(%d+):(%d+):(%d+)")
    if y then
        return epochFromParts(tonumber(y), tonumber(mo), tonumber(d),
            tonumber(hh), tonumber(mm), tonumber(ss))
    end
    local dd, mon, yy, H, M, S = block:match(
        "<[dD]?:?getlastmodified>%s*%a+,%s*(%d+)%s+(%a+)%s+(%d+)%s+(%d+):(%d+):(%d+)")
    if dd and MONTHS[mon] then
        return epochFromParts(tonumber(yy), MONTHS[mon], tonumber(dd),
            tonumber(H), tonumber(M), tonumber(S))
    end
    return 0
end

-- ------------------------------------------------------------------
-- WebDAV 操作
-- ------------------------------------------------------------------

-- 逐级 MKCOL 创建远端目录（起点为 DAV 根，即 url 的路径前缀；已存在 405 视为成功）
local function ensureCollection(cfg, headers)
    local base, basePath, davRoot = resolveBase()
    if not davRoot then return false end
    local cur = davRoot
    for seg in cfg.remotePath:gsub("^/+", ""):gsub("/+$", ""):gmatch("[^/]+") do
        cur = cur .. "/" .. encodePath(seg)
        local res = host.http.request("MKCOL", cur, headers, nil)
        if res == nil then return false end
        if res.status ~= 201 and res.status ~= 405 and res.status ~= 200 and res.status ~= 301 then
            return false
        end
    end
    return true
end

-- 从失败响应提取可读原因（去 XML 标签、截断），拼进错误消息给设置页展示
local function statusDetail(res)
    if res == nil then return "" end
    local body = res.text or ""
    body = body:gsub("<[^>]*>", " "):gsub("%s+", " ")
    body = body:match("^%s*(.-)%s*$")
    if body == "" then return "" end
    return ": " .. body:sub(1, 160)
end

function plugin.testConnection()
    local base, basePath, davRoot, headers, cfg, err = resolveBase()
    if err then return err end
    headers["Depth"] = "0"
    local res = host.http.request("PROPFIND", encodePath(base), headers, nil)
    if res == nil then return host.http.lastError() or "请求失败" end
    if res.status == 207 or res.status == 200 then return nil end
    if res.status == 401 then return "认证失败（401），请检查账号与密码" end
    if res.status == 404 then
        -- 目录不存在不算失败（首次备份会自动创建）
        if ensureCollection(cfg, headers) then return nil end
        return "远端目录不存在且自动创建失败"
    end
    return "服务器返回 HTTP " .. tostring(res.status) .. statusDetail(res)
end

function plugin.pushBackup(args)
    args = args or {}
    local name = args.name or ""
    local archive = args.archive
    if name == "" then return { ok = false, message = "备份包名为空" } end
    if not archive or archive == "" then return { ok = false, message = "备份包为空" } end

    local base, basePath, davRoot, headers, cfg, err = resolveBase()
    if err then return { ok = false, message = err } end

    headers["Content-Type"] = "application/octet-stream"
    headers["Overwrite"] = "T"
    local url = encodePath(base) .. "/" .. encodePath(name)

    local res = host.http.request("PUT", url, headers, archive)
    if res == nil then return { ok = false, message = host.http.lastError() or "上传请求失败" } end
    if res.status == 409 or res.status == 404 then
        -- 父目录不存在：逐级创建后重试一次
        -- （标准 DAV 报 409 Conflict；Alist/Nextcloud 等报 404）
        if not ensureCollection(cfg, headers) then
            return { ok = false, message = "创建远端目录失败" }
        end
        res = host.http.request("PUT", url, headers, archive)
        if res == nil then return { ok = false, message = host.http.lastError() or "上传请求失败" } end
    end
    if res.status == 200 or res.status == 201 or res.status == 204 then
        return { ok = true, id = basePath .. "/" .. name }
    end
    if res.status == 401 then return { ok = false, message = "认证失败（401），请检查账号与密码" } end
    return { ok = false, message = "上传失败（HTTP " .. tostring(res.status) .. "）" .. statusDetail(res) }
end

function plugin.pullBackup(id)
    if not id or id == "" then return nil end
    local base, basePath, davRoot, headers, cfg, err = resolveBase()
    if err then return nil end
    local origin = cfg.url:match("^https?://[^/]+")
    local res = host.http.request("GET", origin .. encodePath(id), headers, nil)
    if res == nil then return nil end
    if res.status ~= 200 then return nil end
    return res.body
end

-- 大小写不敏感地取子标签文本（命名空间前缀 d:/D:/无 均可；开标签允许携带属性）
local function tagValue(block, tag)
    return block:match("<[dD]?:?" .. tag .. "[^>]*>%s*(.-)%s*</[dD]?:?" .. tag .. ">")
end

function plugin.listBackups()
    local base, basePath, davRoot, headers, cfg, err = resolveBase()
    if err then return nil end
    headers["Depth"] = "1"
    local res = host.http.request("PROPFIND", encodePath(base), headers, nil)
    if res == nil then return nil end
    if res.status ~= 207 and res.status ~= 200 then return nil end

    local xml = res.text or ""
    local basePrefix = basePath .. "/"
    local items = {}
    for block in xml:gmatch("<[dD]?:?response[^>]*>(.-)</[dD]?:?response>") do
        local href = tagValue(block, "href")
        local p = hrefToPath(href)
        if p then
            local isDir = p:sub(-1) == "/" or
                (block:lower():find("<d?:?collection") ~= nil)
            if not isDir and p:sub(1, #basePrefix) == basePrefix then
                local name = p:sub(#basePrefix + 1)
                if name ~= "" and not name:find("/") then
                    items[#items + 1] = {
                        id = p,
                        name = name,
                        createdAt = parseTime(block),
                        size = tonumber(tagValue(block, "getcontentlength")) or -1,
                    }
                end
            end
        end
    end
    table.sort(items, function(a, b) return a.createdAt > b.createdAt end)
    return items
end

function plugin.deleteBackup(id)
    if not id or id == "" then return false end
    local base, basePath, davRoot, headers, cfg, err = resolveBase()
    if err then return false end
    local origin = cfg.url:match("^https?://[^/]+")
    local res = host.http.request("DELETE", origin .. encodePath(id), headers, nil)
    if res == nil then return false end
    -- 404：远端已不存在，视为删除成功
    return res.status == 204 or res.status == 200 or res.status == 404
end

-- ------------------------------------------------------------------
-- 配置表单（UiNode 契约）
-- ------------------------------------------------------------------

function plugin.getSettingsSchema()
    return {
        {
            type = "text", key = "url", label = "服务器地址", required = true,
            placeholder = "https://dav.jianguoyun.com/dav/",
            helpText = "WebDAV 根地址；坚果云为 https://dav.jianguoyun.com/dav/"
        },
        { type = "text", key = "username", label = "账号", required = true },
        {
            type = "secret", key = "password", label = "密码 / 应用密码", required = true,
            helpText = "坚果云请到网页端「安全选项」生成应用密码"
        },
        {
            type = "text", key = "remote_path", label = "备份目录",
            defaultValue = "/xime_backup",
            helpText = "远端目录，不存在时自动创建"
        },
        { type = "button", key = "testConnection", label = "测试连接" },
    }
end

-- 仅供宿主 JVM 回归测试使用（LuaEncodePathTest）
plugin._encodePath = encodePath

return plugin
