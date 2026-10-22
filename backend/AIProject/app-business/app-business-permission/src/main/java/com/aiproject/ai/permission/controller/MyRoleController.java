package com.aiproject.ai.permission.controller;

import blxt.qjava.utils.check.CheckUtils;
import com.aiproject.ai.permission.bean.MyRoleBean;
import com.aiproject.ai.permission.holde.MyRoleBeanHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.croe.web.controller.ProtectedController;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.inter.ControllerLog;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

@Api(value = "自定义后台用户角色配置", tags = "自定义后台用户角色配置", produces = "redoc")
@RestController
@RequestMapping("/myRole")
public class MyRoleController extends ProtectedController {

    @Autowired
    MyRoleBeanHolder roleService;

    @ApiOperation(value = "新建角色", httpMethod = "POST", notes = "新建角色")
    @ControllerLog(value = "新建角色", user = "uid", target = "")
    @PostMapping("/create")
    public Result<Boolean> create(@LoginUserId @ApiParam(value = "请求者uid, 需要在head中添加x-access-token,填写登录获取的token") String uid,
                                  @ApiParam(required = true, value = "角色") @RequestBody MyRoleBean bean) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_TOKEN_PAST.getCode(), null);
        CheckUtils.objectCheckNull(bean, "数据不能空", CodeEnum.NULL.getCode(), null);

        return roleService.create(uid, Arrays.asList(bean));
    }

    @ApiOperation(value = "删除角色", httpMethod = "POST", notes = "删除角色")
    @PostMapping("/remove")
    @ControllerLog(value = "删除角色", user = "uid", target = "")
    public Result<Boolean> remove(@LoginUserId @ApiParam(value = "请求者uid, 需要在head中添加x-access-token,填写登录获取的token") String uid,
                                  @ApiParam(required = true, value = "角色") @RequestBody MyRoleBean bean) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_TOKEN_PAST.getCode(), null);
        CheckUtils.objectCheckNull(bean, "数据不能空", CodeEnum.NULL.getCode(), null);

        // 注意：uid 必须传下去，Holder 里要靠它做超管鉴权。
        // 此前这里是 roleService.remove(bean)，uid 被丢掉了，导致删除角色无任何权限校验。
        return roleService.remove(uid, bean);
    }

    @ApiOperation(value = "更新角色", httpMethod = "POST", notes = "更新角色")
    @PostMapping("/update")
    @ControllerLog(value = "更新角色", user = "uid", target = "")
    public Result<Boolean> update(@LoginUserId @ApiParam(value = "请求者uid, 需要在head中添加x-access-token,填写登录获取的token") String uid,
                                  @ApiParam(required = true, value = "新数据") @RequestBody MyRoleBean data,
                                  @ApiParam(required = true, value = "条件") @RequestBody MyRoleBean where) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_TOKEN_PAST.getCode(), null);
        CheckUtils.objectCheckNull(data, "新数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(where, "条件不能空", CodeEnum.NULL.getCode(), null);

        return roleService.update(uid, data, where);
    }

    @ApiOperation(value = "查询角色", httpMethod = "POST", notes = "查询角色")
    @PostMapping("/getRids")
    @ControllerLog(value = "查询角色", user = "uid", target = "")
    public Result<PageResult<MyRoleBean>> getRids(@LoginUserId @ApiParam(value = "请求者uid, 需要在head中添加x-access-token,填写登录获取的token") String uid,
                                                @ApiParam(required = true, value = "用户") @RequestBody MyRoleBean bean,
                                                @ApiParam(required = true, value = "分页请求") @RequestBody PageRequest pageRequest) {

        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_TOKEN_PAST.getCode(), null);
        CheckUtils.objectCheckNull(bean, "数据不能空", CodeEnum.NULL.getCode(), null);

        Result<PageResult<MyRoleBean>> result= roleService.query(bean, pageRequest);
        result.setResp_msg("查询成功");
        return  result;
    }
}
