package com.aiproject.ai.service.controller;

import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;

import java.lang.String;
import java.util.List;

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
import com.aiproject.ai.service.bean.CapabilitiesBean;
import com.aiproject.ai.service.holde.CapabilitiesBeanHolder;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/05/13 14:53:02
 */
@RestController
@Slf4j
@RequestMapping("/api/capabilitiesBean")
@Api(value = "能力广场", tags = "能力广场")
public class CapabilitiesBeanController {
    /**
     * 数据操作执行器.
     */
    @Autowired
    CapabilitiesBeanHolder capabilitiesBeanHolder = null;

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
    @ControllerLog(value = "创建能力广场", user = "uid", target = "")
    public Result<Boolean> create(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody CapabilitiesBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return capabilitiesBeanHolder.install(uid, data);
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
    @ControllerLog(value = "删除能力广场", user = "uid", target = "")
    public Result<Boolean> delete(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody CapabilitiesBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return capabilitiesBeanHolder.delete(uid, data);
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
    @ControllerLog(value = "更新能力广场", user = "uid", target = "")
    public Result<Boolean> update(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody CapabilitiesBean data, @ApiParam(required = true, value = "条件") @RequestBody CapabilitiesBean where) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(where, "条件不能空", CodeEnum.NULL.getCode(), null);
        return capabilitiesBeanHolder.update(uid, data, where);
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
    @ApiOperation(value = "查询", httpMethod = "POST", notes = "查询")
    @PostMapping("/query")
    @ControllerLog(value = "查询能力广场", user = "uid", target = "")
    public Result<List<CapabilitiesBean>> query(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody CapabilitiesBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return capabilitiesBeanHolder.queryList(uid,data);
    }

}
