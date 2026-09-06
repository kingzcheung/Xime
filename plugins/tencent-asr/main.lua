-- 腾讯云实时语音识别 V2（WebSocket）Lua 脚本插件
--
-- 职责划分：
--   Lua   = 全部功能逻辑（签名鉴权、连接时机、握手状态机、结果解析、结束通知）
--   宿主  = 仅提供通用原语：
--     host.ws         WebSocket 白名单（connect/sendText/sendBinary/close/onMessage）
--     host.asr.emit*  结果回传桥
--     host.crypto     hmacSha1 / base64 / epochSeconds（腾讯签名三件套）
--     host.json / host.config / host.uuid
--
-- 协议（参考官方文档《实时语音识别 V2（WebSocket）》）：
--   URL    = wss://asr.cloud.tencent.com/asr/v2/<appid>?<参数>
--   签名   = 除 signature 外所有参数按 key 字典序拼接
--            "asr.cloud.tencent.com/asr/v2/<appid>?k1=v1&k2=v2&..."（值为原文，不做 URL 编码）
--            取 HMAC-SHA1(SecretKey, 签名原文) 再 Base64，URL 编码后追加为 signature
--   握手   = 连接建立后服务端先回 {"code":0} 文本帧（code 非 0 表示鉴权等失败并断开）
--   音频   = 二进制帧直接发 PCM（16k/mono/16bit），按宿主录音节奏即 1:1 实时率
--   结束   = 发送文本帧 {"type":"end"}，服务端回 final=1 后断开
--   结果   = 文本帧 JSON：sentences（sentence_type 0=不确定 1=确定，speaker_id 说话人）、final

local plugin = {}

local WS_HOST_PATH = "asr.cloud.tencent.com/asr/v2/"

local KEY_APP_ID = "appId"
local KEY_SECRET_ID = "secretId"
local KEY_SECRET_KEY = "secretKey"
local KEY_ENGINE = "engineModelType"
local KEY_HOTWORD_LIST = "hotwordList"
local DEFAULT_ENGINE = "16k_zh_en_2.0"

local voiceId = ""
local audioReady = false
local prebuffer = {}

function plugin.getIcon()
    return { assetName = "icon.png" }
end

function plugin.isConfigured()
    for _, key in ipairs({ KEY_APP_ID, KEY_SECRET_ID, KEY_SECRET_KEY }) do
        local value = host.config.get(key)
        if value == nil or value == "" then return false end
    end
    return true
end

function plugin.getSettingsSchema()
    return {
        {
            key = KEY_APP_ID,
            label = "AppID",
            type = "text",
            placeholder = "腾讯云账号 AppID（纯数字）",
            helpText = "腾讯云控制台 → 语音识别 → API 密钥管理",
        },
        {
            key = KEY_SECRET_ID,
            label = "SecretId",
            type = "secret",
            placeholder = "输入 SecretId",
        },
        {
            key = KEY_SECRET_KEY,
            label = "SecretKey",
            type = "secret",
            placeholder = "输入 SecretKey",
        },
        {
            key = KEY_ENGINE,
            label = "引擎模型",
            type = "text",
            defaultValue = DEFAULT_ENGINE,
            placeholder = DEFAULT_ENGINE,
            helpText = "16k_zh_en_2.0 中英粤大模型；16k_zh_en_speaker_2.0 支持说话人分离",
        },
        {
            key = KEY_HOTWORD_LIST,
            label = "临时热词表",
            type = "text",
            required = false,
            placeholder = "热词|权重,多个用英文逗号分隔",
            helpText = "可选。如：腾讯云|10,语音识别|5；单个热词最多 10 个汉字，权重 1-11 或 100",
        },
    }
end

function plugin.initialize()
    return true
end

-- ================= 编码与签名 =================

-- RFC 3986 unreserved 之外的字节按 UTF-8 百分号编码（byte 循环，避开 gsub 对
-- 非 ASCII 字节流的匹配单位错位问题，与 webdav-backup 插件同因）
local function percent_encode(s)
    local out = {}
    for i = 1, #s do
        local b = string.byte(s, i)
        if (b >= 48 and b <= 57) or (b >= 65 and b <= 90) or (b >= 97 and b <= 122)
            or b == 45 or b == 46 or b == 95 or b == 126 then
            out[#out + 1] = string.char(b)
        else
            out[#out + 1] = string.format("%%%02X", b)
        end
    end
    return table.concat(out)
end

-- params 按 key 字典序拼成 "k=v&k=v"；encode=true 时值做百分号编码（签名原文用 false）
local function build_query(params, encode_values)
    local keys = {}
    for key in pairs(params) do keys[#keys + 1] = key end
    table.sort(keys)
    local parts = {}
    for _, key in ipairs(keys) do
        local value = tostring(params[key])
        if encode_values then value = percent_encode(value) end
        parts[#parts + 1] = key .. "=" .. value
    end
    return table.concat(parts, "&")
end

-- 随机正整数（最长 10 位）：取 uuid 前 8 个十六进制位取模，沙箱内无 os.math 随机源
local function rand_nonce()
    local hex = (host.uuid():gsub("-", ""))
    local n = tonumber(string.sub(hex, 1, 8), 16) or 0
    return math.floor(n % 1000000000) + 1
end

-- ================= 启动 =================

function plugin.start()
    if not plugin.isConfigured() then
        host.asr.emitError("未配置 AppID / SecretId / SecretKey，请在插件设置中填写")
        return false
    end
    local appId = host.config.get(KEY_APP_ID)
    local secretId = host.config.get(KEY_SECRET_ID)
    local secretKey = host.config.get(KEY_SECRET_KEY)
    voiceId = host.uuid()
    audioReady = false
    prebuffer = {}

    local ts = host.crypto.epochSeconds()
    local params = {
        secretid = secretId,
        timestamp = ts,
        expired = ts + 86400,          -- 有效期 1 天（须 >timestamp 且 <90 天）
        nonce = rand_nonce(),
        engine_model_type = host.config.get(KEY_ENGINE) or DEFAULT_ENGINE,
        voice_id = voiceId,
        voice_format = 1,              -- pcm（宿主录音 16k/mono/16bit）
        needvad = 1,                   -- 长音频 60s 强制切分，开启 VAD 提升分句效果
    }
    local hotword = host.config.get(KEY_HOTWORD_LIST) or ""
    if hotword ~= "" then params.hotword_list = hotword end

    -- 签名原文用原始值；URL 中值做百分号编码（服务端按解码后参数重建原文校验）
    local sign_str = WS_HOST_PATH .. appId .. "?" .. build_query(params, false)
    local sig = host.crypto.base64(host.crypto.hmacSha1(secretKey, sign_str))
    if sig == nil or sig == "" then
        host.asr.emitError("签名计算失败（host.crypto 不可用）")
        return false
    end
    local url = "wss://" .. WS_HOST_PATH .. appId .. "?"
        .. build_query(params, true) .. "&signature=" .. percent_encode(sig)

    local ok = host.ws.connect(url, {}, {
        onOpen = function() plugin.onWsOpen() end,
        onMessage = function(text) plugin.onWsMessage(text) end,
        onBinary = function(frame) plugin.onWsBinary(frame) end,
        onError = function(msg) plugin.onWsError(msg) end,
        onClose = function() plugin.onWsClose() end,
    })
    if not ok then
        local reason = host.ws.lastError()
        if reason ~= nil and reason ~= "" then
            host.asr.emitError(reason)
        end
        return false
    end
    return true
end

-- ================= WebSocket 事件（状态机） =================

-- 腾讯不收 full request 帧：连接建立后等握手文本帧，onOpen 无需发送任何数据
function plugin.onWsOpen()
end

function plugin.onWsMessage(text)
    local obj = host.json.decode(text)
    if obj == nil then
        host.asr.emitError("无法解析服务端消息: " .. text)
        return
    end
    local code = obj.code or 0
    if code ~= 0 then
        host.asr.emitError("ASR 错误 " .. tostring(code) .. ": " .. (obj.message or ""))
        host.ws.close()
        return
    end
    -- 握手成功（{"code":0}）后才允许发音频；补发握手期间缓冲的开头音频
    if not audioReady then
        audioReady = true
        for _, pcm in ipairs(prebuffer) do
            host.ws.sendBinary(pcm)
        end
        prebuffer = {}
    end

    local sentences = obj.sentences
    if type(sentences) == "table" then
        if sentences.sentence ~= nil then
            plugin.handleSentence(sentences)
        else
            for _, sentence in ipairs(sentences) do
                plugin.handleSentence(sentence)
            end
        end
    end
    if obj.final == 1 then
        host.ws.close()
    end
end

-- sentence_type：0=不确定（partial 上屏预览），1=确定（final 提交）
function plugin.handleSentence(sentence)
    local text = sentence.sentence or ""
    if text == "" then return end
    if (sentence.sentence_type or 0) == 1 then
        host.asr.emitFinal(text)
    else
        host.asr.emitPartial(text)
    end
end

function plugin.onWsBinary(frame)
    -- 本接口结果均为文本帧，二进制帧忽略
end

function plugin.onWsError(msg)
    host.asr.emitError(msg)
end

function plugin.onWsClose()
    voiceId = ""
    audioReady = false
    prebuffer = {}
end

-- ================= 音频数据（主 App 每帧提交，Lua 决策） =================

function plugin.processAudioChunk(pcm)
    if audioReady then
        host.ws.sendBinary(pcm)
    else
        table.insert(prebuffer, pcm)
        if #prebuffer > 300 then table.remove(prebuffer, 1) end
    end
end

function plugin.stop()
    if voiceId == "" then return end
    host.ws.sendText('{"type":"end"}')
end

function plugin.cancel()
    host.ws.close()
    voiceId = ""
    audioReady = false
    prebuffer = {}
end

return plugin
