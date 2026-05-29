package com.escosis.offlinewebview

import java.util.UUID

/**
 * 代表一个保存的实例
 * @param id 唯一标识（UUID字符串）
 * @param name 用户输入的实例名称
 * @param createdAt 创建时间戳（毫秒）
 * @param type 类型："copy"（复制全部文件）或 "reference"（仅保存路径）
 * @param storageDir 实例存储目录的绝对路径（对于copy和zip模式有效，对于reference模式也为实际目录路径，但可能为空？reference模式不需要存储文件，但为了方便，可以设置为空字符串）
 * @param sourceUri 仅reference模式有效：原始SAF文件夹的Uri字符串
 * @param savedUrl 保存时当前页面的相对URL（例如 "sub/page.html" 或空字符串）
 * @param isZipMode 是否为ZIP解压模式保存的实例（便于加载时区分）
 */
data class Instance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val type: String, // "copy", "reference", "zip_move"
    val storageDir: String,
    val sourceUri: String? = null,
    val savedUrl: String = "",
    val isZipMode: Boolean = false
)