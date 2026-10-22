package com.aiproject.ai.common.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件/文件夹信息DTO，用于数据集目录浏览（树形结构）
 *
 * @author heneng
 * @date 2026/07/24
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(value = "文件项", description = "文件/文件夹信息（树形结构）")
public class FileItemDTO {

    @ApiModelProperty(value = "文件/文件夹名称")
    private String name;

    @ApiModelProperty(value = "类型：file(文件) / directory(文件夹)")
    private String type;

    @ApiModelProperty(value = "完整路径")
    private String path;

    @ApiModelProperty(value = "文件大小（字节），仅文件有值")
    private Long size;

    @ApiModelProperty(value = "子节点（仅文件夹有）")
    private List<FileItemDTO> children = new ArrayList<>();
}
