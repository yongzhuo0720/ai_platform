package com.aiproject.ai.common.utils;

import com.aiproject.ai.common.dto.FileItemDTO;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * @author jiaoyu
 * @ClassName FileTreeUtil
 * @description: TODO
 * @date 2026年07月24日
 * @version: 1.0
 */
public class FileTreeUtil {
    /**
     * 功能描述: 扫描文件夹，返回树形结构的文件和子文件夹列表.
     *
     * @param folderPath 文件夹路径
     * @return 文件/文件夹树形列表
     * @author heneng
     * @date 2026/07/24
     **/
    public static List<FileItemDTO> listFiles(String folderPath) {
        List<FileItemDTO> result = new ArrayList<>();
        File dir = new File(folderPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return result;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            FileItemDTO item = new FileItemDTO();
            item.setName(file.getName());
            item.setType(file.isDirectory() ? "directory" : "file");
            item.setPath(file.getAbsolutePath());
            if (file.isFile()) {
                item.setSize(file.length());
            }
            if (file.isDirectory()) {
                item.setChildren(buildTree(file));
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 递归构建文件夹子节点树形结构.
     *
     * @param dir 文件夹
     * @return 子节点列表
     */
    private  static  List<FileItemDTO> buildTree(File dir) {
        List<FileItemDTO> children = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return children;
        }
        for (File file : files) {
            FileItemDTO child = new FileItemDTO();
            child.setName(file.getName());
            child.setType(file.isDirectory() ? "directory" : "file");
            child.setPath(file.getAbsolutePath());
            if (file.isFile()) {
                child.setSize(file.length());
            }
            if (file.isDirectory()) {
                child.setChildren(buildTree(file));
            }
            children.add(child);
        }
        return children;
    }
}
