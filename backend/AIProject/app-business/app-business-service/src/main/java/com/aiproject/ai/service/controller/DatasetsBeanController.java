package com.aiproject.ai.service.controller;

import com.aiproject.ai.common.utils.FileTreeUtil;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.croe.util.FileUploadDown;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.lang.String;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.RequestBody;
import lombok.extern.slf4j.Slf4j;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import com.heneng.hnsp.common.inter.ControllerLog;
import blxt.qjava.utils.check.CheckUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import io.swagger.annotations.Api;
import com.aiproject.ai.service.bean.DatasetsBean;
import com.aiproject.ai.common.dto.DownloadFileRequest;
import com.aiproject.ai.common.dto.FileItemDTO;
import com.aiproject.ai.service.holde.DatasetsBeanHolder;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/05/13 14:53:02
 */
@RestController
@Slf4j
@RequestMapping("/api/datasetsBean")
@Api(value = "数据集仓库", tags = "数据集仓库")
public class DatasetsBeanController {
    /**
     * 数据操作执行器.
     */
    @Autowired
    DatasetsBeanHolder datasetsBeanHolder = null;

    /**
     * 功能描述: 创建.
     *
     * @param uid  用户id
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/
    @ApiOperation(value = "创建", httpMethod = "POST", notes = "创建")
    @PostMapping("/create")
    @ControllerLog(value = "创建数据集仓库", user = "uid", target = "")
    public Result<Boolean> create(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody DatasetsBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return datasetsBeanHolder.install(uid, data);
    }

    /**
     * 功能描述: 删除.
     *
     * @param uid  用户id
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/
    @ApiOperation(value = "删除", httpMethod = "POST", notes = "删除")
    @PostMapping("/delete")
    @ControllerLog(value = "删除数据集仓库", user = "uid", target = "")
    public Result<Boolean> delete(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody DatasetsBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return datasetsBeanHolder.delete(uid, data);
    }

    /**
     * 功能描述: 更新.
     *
     * @param uid   用户id
     * @param data  数据
     * @param where 条件
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/
    @ApiOperation(value = "更新", httpMethod = "POST", notes = "更新")
    @PostMapping("/update")
    @ControllerLog(value = "更新数据集仓库", user = "uid", target = "")
    public Result<Boolean> update(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody DatasetsBean data, @ApiParam(required = true, value = "条件") @RequestBody DatasetsBean where) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(where, "条件不能空", CodeEnum.NULL.getCode(), null);
        return datasetsBeanHolder.update(uid, data, where);
    }

    /**
     * 功能描述: 查询.
     *
     * @param uid         用户id
     * @param data        数据
     * @return Result
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/
    @ApiOperation(value = "获取数据集文件列表", httpMethod = "POST", notes = "获取数据集目录下的文件/文件夹列表（递归包含所有嵌套内容）")
    @PostMapping("/getDownloadUrl")
    @ControllerLog(value = "获取数据集文件列表", user = "uid", target = "")
    public Result<List<FileItemDTO>> getDownloadUrl(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody DatasetsBean data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        DatasetsBean dataset = datasetsBeanHolder.query(data);
        if (dataset == null || dataset.getDownloadUrl() == null || dataset.getDownloadUrl().isEmpty()) {
            return Result.failed(null, "下载链接不存在");
        }
        String folderPath = dataset.getDownloadUrl();
        File folder = new File(folderPath);
        if (!folder.exists()) {
            return Result.failed(null, "文件夹路径不存在");
        }
        if (!folder.isDirectory()) {
            return Result.failed(null, "下载路径不是文件夹");
        }
        List<FileItemDTO> fileList = FileTreeUtil.listFiles(folderPath);
        return Result.succeed(fileList, "获取成功");
    }

    @ApiOperation(value = "下载文件", httpMethod = "POST", notes = "下载数据集中的文件（文件夹不可下载）")
    @PostMapping("/downloadFile")
    @ControllerLog(value = "下载数据集文件", user = "uid", target = "")
    public void downloadFile(@LoginUserId String uid,
                             @ApiParam(required = true, value = "文件完整路径") @RequestBody DownloadFileRequest request,
                             HttpServletResponse response) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(request, "参数不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(request.getFilePath(), "文件路径不能空", CodeEnum.NULL.getCode(), null);

        /*
         * 越权修复：
         * 原实现直接 new File(filePath) 并回传内容，只校验了「非空」。
         * 由于接口签名是 @RequestBody String，调用方只要用 text/plain 传一段裸路径，
         * 就能下载服务器上的任意文件（例如 application.yml 里的数据库口令、/etc/passwd）。
         * 这里改为：路径必须落在某个「已登记数据集」的 downloadUrl 根目录之下，否则 403。
         */
        File file = resolveAllowedFile(request.getFilePath());
        if (file == null) {
            log.warn("拒绝越界的下载请求, uid={}, path={}", uid, request.getFilePath());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (!file.exists()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (file.isDirectory()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        try (FileInputStream fis = new FileInputStream(file);
             OutputStream os = response.getOutputStream()) {
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition",
                    "attachment; filename=" + URLEncoder.encode(file.getName(), "UTF-8"));
            response.setHeader("Content-Length", String.valueOf(file.length()));

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();
        } catch (Exception e) {
            log.error("文件下载失败: {}", e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 把请求路径解析为「位于某个已登记数据集根目录之下」的规范路径。
     * <p>
     * 先用 getCanonicalFile() 消掉 {@code ..}、软链接等，再做前缀比较，
     * 因此 {@code <数据集目录>/../../application.yml} 这类穿越会被拒绝。
     *
     * @return 合法文件；越界或解析失败返回 null
     */
    private File resolveAllowedFile(String rawPath) {
        try {
            File target = new File(rawPath).getCanonicalFile();
            Result<List<DatasetsBean>> all = datasetsBeanHolder.queryList(new DatasetsBean());
            List<DatasetsBean> datasets = all == null ? null : all.getDatas();
            if (datasets == null || datasets.isEmpty()) {
                return null;
            }
            for (DatasetsBean dataset : datasets) {
                String root = dataset.getDownloadUrl();
                if (root == null || root.trim().isEmpty()) {
                    continue;
                }
                File rootDir = new File(root).getCanonicalFile();
                String rootPath = rootDir.getPath();
                if (target.getPath().equals(rootPath)
                        || target.getPath().startsWith(rootPath + File.separator)) {
                    return target;
                }
            }
        } catch (Exception e) {
            log.warn("解析下载路径失败: {}", rawPath, e);
        }
        return null;
    }

    @ApiOperation(value = "查询", httpMethod = "POST", notes = "查询")
    @PostMapping("/query")
    @ControllerLog(value = "查询数据集仓库", user = "uid", target = "")
    public Result<List<DatasetsBean>> query(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody DatasetsBean data ) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return datasetsBeanHolder.queryList(data);
    }

}
