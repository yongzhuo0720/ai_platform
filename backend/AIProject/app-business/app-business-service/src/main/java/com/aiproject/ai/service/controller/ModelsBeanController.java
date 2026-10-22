package com.aiproject.ai.service.controller;

import com.aiproject.ai.common.dto.FileItemDTO;
import com.aiproject.ai.common.utils.FileTreeUtil;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;

import java.io.File;
import java.lang.String;
import java.util.List;

import io.swagger.annotations.ApiParam;
import io.swagger.models.auth.In;
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
import com.aiproject.ai.service.bean.ModelsBean;
import com.aiproject.ai.service.holde.ModelsBeanHolder;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/05/13 14:53:02
 */
@RestController
@Slf4j
@RequestMapping("/api/modelsBean")
@Api(value = "模型仓库", tags = "模型仓库")
public class ModelsBeanController {
    /**
     * 数据操作执行器.
     */
    @Autowired
    ModelsBeanHolder modelsBeanHolder = null;

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
    @ControllerLog(value = "创建模型仓库", user = "uid", target = "")
    public Result<Boolean> create(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return modelsBeanHolder.install(uid, data);
    }

    @ApiOperation(value = "下载模型仓库次数增加", httpMethod = "POST", notes = "下载模型仓库次数增加")
    @PostMapping("/addDownloads")
    @ControllerLog(value = "下载模型仓库", user = "uid", target = "")
    public Result<Boolean> addDownloads(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return modelsBeanHolder.addDownloads(uid, data);
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
    @ControllerLog(value = "删除模型仓库", user = "uid", target = "")
    public Result<Boolean> delete(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return modelsBeanHolder.delete(uid, data);
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
    @ControllerLog(value = "更新模型仓库", user = "uid", target = "")
    public Result<Boolean> update(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsBean data, @ApiParam(required = true, value = "条件") @RequestBody ModelsBean where) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(where, "条件不能空", CodeEnum.NULL.getCode(), null);
        return modelsBeanHolder.update(uid, data, where);
    }

    /**
     * 功能描述: 查询.
     *
     * @param uid  用户id
     * @param data 数据
     * @return Result
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/
    @ApiOperation(value = "查询", httpMethod = "POST", notes = "查询")
    @PostMapping("/query")
    @ControllerLog(value = "查询模型仓库", user = "uid", target = "")
    public Result<List<ModelsBean>> query(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return modelsBeanHolder.queryList(data);
    }

    /**
     * 功能描述: 模型库周下载量查询.
     *
     * @param uid  用户id
     * @param data 数据
     * @return Result
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/
    @ApiOperation(value = "获取模型下载链接", httpMethod = "POST", notes = "获取模型下载链接用于iframe预览")
    @PostMapping("/getDownloadUrl")
    @ControllerLog(value = "获取模型下载链接", user = "uid", target = "")
    public Result<List<FileItemDTO>> getDownloadUrl(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsBean data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        ModelsBean model = modelsBeanHolder.query(data);
        if (model == null || model.getDownloadUrl() == null || model.getDownloadUrl().isEmpty()) {
            return Result.failed(null, "下载链接不存在");
        }
        String folderPath = model.getDownloadUrl();
        File folder = new File(folderPath);
        if (!folder.exists()) {
            return Result.failed(null, "文件夹路径不存在");
        }
        if (!folder.isDirectory()) {
            return Result.failed(null, "下载路径不是文件夹");
        }
        List<FileItemDTO> fileList = FileTreeUtil.listFiles(folderPath);
        modelsBeanHolder.addDownloads(uid, data);
        return Result.succeed(fileList, "获取成功");

    }

    @ApiOperation(value = "周下载量查询", httpMethod = "POST", notes = "周下载量查询")
    @PostMapping("/queryWeeklyDownloads")
    @ControllerLog(value = "模型库周下载量查询", user = "uid", target = "")
    public Result<Integer> queryWeeklyDownloads(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return modelsBeanHolder.queryWeeklyDownloads(data);
    }
}
