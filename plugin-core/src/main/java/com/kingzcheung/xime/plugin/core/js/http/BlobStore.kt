package com.kingzcheung.xime.plugin.core.js.http

import java.io.File

/**
 * 宿主大文件句柄仓库（流式上传/下载用，**协议无关**）。
 *
 * 存在意义：插件沙箱不接触宿主文件系统，大文件（备份包、镜像、导出物）又不能作为字节
 * 跨 JS 桥（会整体进内存并放大数倍）。折中方案是"不透明句柄"——宿主登记文件得到随机 id，
 * 插件只持有 id，`host.http.upload` / `host.http.download` 由宿主按块读写：
 *
 * - id 为随机不可猜串，且**绑定 ownerPluginId**：其它插件（或伪造 id）解析不到文件；
 * - 生命周期由宿主掌控：`register` 登记 → 插件在一次调用内可多次引用（PUT 失败重试）
 *   → `release` 注销（仓库自有文件一并删除）；
 * - 插件永远拿不到路径，无法把句柄当通用文件读取原语使用。
 *
 * 实现位于 app 层（需要 cacheDir）；plugin-core 只依赖本接口，测试可注入内存/临时目录实现。
 */
interface BlobStore {

    /**
     * 申请仓库自有临时文件（用于流式下载落盘）。
     *
     * @return 目标文件（尚未登记为 blob；需 [register] 后插件才可引用）
     */
    fun createTempFile(ownerPluginId: String): File

    /**
     * 登记宿主文件为可被 [ownerPluginId] 引用的 blob。
     *
     * @param owned true = 文件所有权移交仓库，[release] 时删除；false = 调用方仍持有
     *   （如备份包由 BackupManager 落盘并自行清理），[release] 仅注销
     * @return 不透明 blob id
     */
    fun register(ownerPluginId: String, file: File, owned: Boolean = false): String

    /**
     * 解析 blob id → 文件。
     *
     * @return 文件不存在 / 已释放 / [pluginId] 非登记方时返回 null
     */
    fun resolve(pluginId: String, id: String): File?

    /** 释放 blob（幂等）：注销登记；[register] 时 owned=true 的文件一并删除。 */
    fun release(id: String)
}
