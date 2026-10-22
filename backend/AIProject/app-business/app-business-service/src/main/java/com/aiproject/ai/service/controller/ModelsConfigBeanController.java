package com.aiproject.ai.service.controller;

import com.heneng.hnsp.common.model.CodeEnum;
import com.heneng.hnsp.common.inter.ControllerLog;
import org.springframework.web.bind.annotation.RequestMapping;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import org.springframework.web.bind.annotation.RestController;
import blxt.qjava.utils.check.CheckUtils;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import com.heneng.hnsp.common.inter.LoginUserId;
import org.springframework.web.bind.annotation.RequestBody;
import io.swagger.annotations.Api;
import org.springframework.web.bind.annotation.PostMapping;
import lombok.extern.slf4j.Slf4j;

import java.lang.String;
import java.util.List;

import com.heneng.hnsp.common.croe.model.Result;
import io.swagger.annotations.ApiParam;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/07/23 14:14:45
 */
@Api(value = "模型配置", tags = "模型配置")
@RequestMapping("/api/modelsConfigBean")
@Slf4j
@RestController
public class ModelsConfigBeanController {
    /**
     * 数据操作执行器.
     */
    @Autowired
    ModelsConfigBeanHolder modelsConfigBeanHolder = null;

    /**
     * 功能描述: 创建.
     *
     * @param uid  用户id
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/07/23 14:14:45
     **/
    @ControllerLog(value = "创建", user = "uid", target = "")
    @PostMapping("/create")
    @ApiOperation(value = "创建", httpMethod = "POST", notes = "创建")
    public Result<Boolean> create(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsConfigBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return modelsConfigBeanHolder.install(uid, data);
    }

    /**
     * 功能描述: 删除.
     *
     * @param uid  用户id
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/07/23 14:14:45
     **/
    @ControllerLog(value = "删除", user = "uid", target = "")
    @PostMapping("/delete")
    @ApiOperation(value = "删除", httpMethod = "POST", notes = "删除")
    public Result<Boolean> delete(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsConfigBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return modelsConfigBeanHolder.delete(uid, data);
    }

    /**
     * 功能描述: 更新.
     *
     * @param uid   用户id
     * @param data  数据
     * @param where 条件
     * @return Boolean
     * @author heneng
     * @date 2026/07/23 14:14:45
     **/
    @ControllerLog(value = "更新", user = "uid", target = "")
    @PostMapping("/update")
    @ApiOperation(value = "更新", httpMethod = "POST", notes = "更新")
    public Result<Boolean> update(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsConfigBean data, @ApiParam(required = true, value = "条件") @RequestBody ModelsConfigBean where) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(where, "条件不能空", CodeEnum.NULL.getCode(), null);
        return modelsConfigBeanHolder.update(uid, data, where);
    }

    /**
     * 功能描述: 分页查询.
     *
     * @param uid         用户id
     * @param data        数据
     * @param pageRequest 分页条件
     * @return Result
     * @author heneng
     * @date 2026/07/23 14:14:45
     **/
    @ControllerLog(value = "分页查询", user = "uid", target = "")
    @PostMapping("/queryPage")
    @ApiOperation(value = "分页查询", httpMethod = "POST", notes = "分页查询")
    public Result<PageResult<ModelsConfigBean>> queryPage(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsConfigBean data,
                                                          @ApiParam(value = "分页") @RequestBody PageRequest pageRequest) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        Result<PageResult<ModelsConfigBean>> result = modelsConfigBeanHolder.queryPage(data, pageRequest);
        maskApiKeys(result == null || result.getDatas() == null ? null : result.getDatas().getContent());
        return result;
    }

    /**
     * 功能描述: 查询.
     *
     * @param uid  用户id
     * @param data 数据
     * @return Result
     * @author heneng
     * @date 2026/07/23 14:14:45
     **/
    @ControllerLog(value = "查询", user = "uid", target = "")
    @PostMapping("/query")
    @ApiOperation(value = "查询", httpMethod = "POST", notes = "查询")
    public Result<List<ModelsConfigBean>> query(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody ModelsConfigBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        List<ModelsConfigBean> datas = modelsConfigBeanHolder.queryList(data, null);
        maskApiKeys(datas);
        return Result.succeed(datas);
    }

    /**
     * 服务端密钥不下发浏览器：直连模型配置的 apiKey 只在后端使用，
     * 查询结果一律置空，前端以「是否已配置」展示。
     */
    private void maskApiKeys(List<ModelsConfigBean> datas) {
        if (datas == null) {
            return;
        }
        for (ModelsConfigBean item : datas) {
            if (item != null && item.getApiKey() != null && !item.getApiKey().isEmpty()) {
                item.setApiKey("");
            }
        }
    }

}
