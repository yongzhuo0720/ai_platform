package com.aiproject.ai.common.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 下载文件请求体。
 * <p>
 * 前端 TreeNod.vue 发送的是 {@code { filePath: "..." }} 这样的 JSON 对象，
 * 而此前的接口签名是 {@code @RequestBody String filePath}，
 * 两者对不上：后端拿到的是整段 JSON 文本 {@code {"filePath":"..."}}，
 * {@code new File(...)} 自然找不到文件，下载功能实际是坏的。
 * 同时该签名允许调用方用 text/plain 传一段裸的绝对路径，
 * 造成任意文件读取。这里改成显式 DTO，语义与前端一致。
 *
 * @author heneng
 * @date 2026/07/24
 */
@Data
@ApiModel(value = "下载文件请求", description = "数据集/模型目录内的文件下载请求")
public class DownloadFileRequest {

    @ApiModelProperty(value = "文件完整路径")
    private String filePath;
}
