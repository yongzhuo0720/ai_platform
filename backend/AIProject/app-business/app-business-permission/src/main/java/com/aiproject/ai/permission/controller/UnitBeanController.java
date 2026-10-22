package com.aiproject.ai.permission.controller;

import blxt.qjava.utils.check.CheckUtils;

import com.aiproject.ai.permission.bean.UnitBean;
import com.aiproject.ai.permission.holde.UnitBeanHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.inter.ControllerLog;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * [请在这里添加注释]
 *
 * @author openJL
 * @date 2023/02/22 15:24:56
 * 此代码由[HSNP]快速开发框架自动生成
 */
@Slf4j()
@RequestMapping("/api/unitBean")
@RestController()
@Api(value = "部门管理", tags = "部门管理")
public class UnitBeanController {
    /**
     * 数据操作执行器
     */
    @Autowired()
    UnitBeanHolder unitBeanHolder = null;

    /**
     * 创建
     */
    @PostMapping("/create")
    @ApiOperation(value = "创建", httpMethod = "POST", notes = "创建")
    @ControllerLog(value = "创建部门", user = "uid", target = "")
    public Result<Boolean> create(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody UnitBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return unitBeanHolder.install(uid, data);
    }

    /**
     * 删除
     */
    @PostMapping("/delete")
    @ApiOperation(value = "删除", httpMethod = "POST", notes = "删除")
    @ControllerLog(value = "删除部门", user = "uid", target = "")
    public Result<Boolean> delete(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody UnitBean data) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return unitBeanHolder.delete(data);
    }

    /**
     * 更新
     */
    @PostMapping("/update")
    @ApiOperation(value = "更新", httpMethod = "POST", notes = "更新")
    @ControllerLog(value = "更新部门", user = "uid", target = "")
    public Result<Boolean> update(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody UnitBean data, @ApiParam(required = true, value = "条件") @RequestBody UnitBean when) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(when, "条件不能空", CodeEnum.NULL.getCode(), null);
        return unitBeanHolder.update(uid, data, when);
    }

    /**
     * 查询
     */
    @PostMapping("/query")
    @ApiOperation(value = "查询", httpMethod = "POST", notes = "查询")
    @ControllerLog(value = "查询", user = "uid", target = "")
    public Result<PageResult<UnitBean>> query(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody UnitBean data, @ApiParam(value = "分页") @RequestBody PageRequest pageRequest) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return unitBeanHolder.queryPage(data, pageRequest);
    }

    /**
     * 查询树结构
     */
    @ApiOperation(value = "查询树结构（可根据多个iid查子集）", httpMethod = "POST", notes = "查询树结构（可根据多个iid查子集）")
    @PostMapping("/queryTree")
    @ControllerLog(value = "查询部门树结构", user = "uid", target = "")
    public Result<List<UnitBean>> queryTree(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody UnitBean data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return unitBeanHolder.queryChildTree(uid, data);
    }
}
