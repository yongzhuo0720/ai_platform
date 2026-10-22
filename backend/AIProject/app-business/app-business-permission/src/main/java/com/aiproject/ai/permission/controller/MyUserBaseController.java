
package com.aiproject.ai.permission.controller;

import blxt.qjava.utils.check.CheckUtils;
import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.bean.UnitBean;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.heneng.hnsp.business.user.mapper.UserbaseMapper;
import com.heneng.hnsp.business.user.server.impl.UserBaseHolderImpl;
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

@Api(
        value = "自定义用户基础信息管理器",
        tags = {"自定义用户基础信息管理器"},
        produces = "redoc"
)
@RestController
@RequestMapping({"/myuser"})
public class MyUserBaseController extends ProtectedController {
    @Autowired
    MyUserBaseDbHolder userBaseHolderImpl;



    @ApiOperation(
            value = "创建基础用户",
            httpMethod = "POST",
            notes = "创建基础用户"
    )
    @ControllerLog(value = "创建用户", user = "uid", target = "")
    @PostMapping({"/create"})
    public Result<String> create(@LoginUserId String uid, @ApiParam(required = true, value = "用户基础信息") @RequestBody MyUserBaseDb bean) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_TOKEN_PAST.getCode(), (Object) null);
        CheckUtils.objectCheckNull(bean, "注册信息不能为空", CodeEnum.NULL.getCode(), (Object) null);
        return this.userBaseHolderImpl.create(uid, new MyUserBaseDb[]{bean});
    }

    @ApiOperation(
            value = "更新用户",
            httpMethod = "POST",
            notes = "单个更新用户, uid必填"
    )
    @ControllerLog(value = "更新用户", user = "uid", target = "")
    @PostMapping({"/update"})
    public Result<String> update(@LoginUserId String uid, @ApiParam(required = true, value = "新数据") @RequestBody MyUserBaseDb bean,
                                 @ApiParam(required = true, value = "条件") @RequestBody MyUserBaseDb where) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_TOKEN_PAST.getCode(), (Object) null);
        CheckUtils.objectCheckNull(bean, "数据不能为空", CodeEnum.NULL.getCode(), (Object) null);
        CheckUtils.objectCheckNull(where, "条件不能为空", CodeEnum.NULL.getCode(), (Object) null);
        return CheckUtils.isEmpty(where.getUid()) ? Result.failed("更新失败，uid为空") : this.userBaseHolderImpl.update(uid, bean, where);
    }

    @ApiOperation(
            value = "查询用户",
            httpMethod = "POST",
            notes = "批量查询用户-支持分页"
    )
    @ControllerLog(value = "查询用户", user = "uid", target = "")
    @PostMapping({"/query"})
    public Result<PageResult<MyUserBaseDb>> query(@LoginUserId String uid, @ApiParam(required = true, value = "查询信息") @RequestBody MyUserBaseDb bean,
                                                  @ApiParam(required = true, value = "分页请求") @RequestBody PageRequest pageRequest) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_TOKEN_PAST.getCode(), (Object) null);
        CheckUtils.objectCheckNull(bean, "数据不能为空", CodeEnum.NULL.getCode(), (Object) null);

        // uid 必须传下去：Holder 要靠它判断调用者是不是超管，决定是否脱敏返回
        Result<PageResult<MyUserBaseDb>> result=  userBaseHolderImpl.querys(uid, bean,pageRequest);
        result.setResp_msg("查询成功");
        return  result;
    }

    @ApiOperation(
            value = "删除用户",
            httpMethod = "POST",
            notes = "删除用户"
    )
    @ControllerLog(value = "删除用户", user = "uid", target = "")
    @PostMapping({"/delete"})
    public Result<String> delete(@LoginUserId String uid, @ApiParam(required = true, value = "查询信息") @RequestBody MyUserBaseDb bean) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_TOKEN_PAST.getCode(), (Object) null);
        CheckUtils.objectCheckNull(bean, "数据不能为空", CodeEnum.NULL.getCode(), (Object) null);
        CheckUtils.objectCheckNull(bean.getUid(), "数据不能为空", CodeEnum.NULL.getCode(), (Object) null);
        // 管理员校验与「不能删自己」的判断都在 Holder 内完成，保持鉴权逻辑集中
        return this.userBaseHolderImpl.delete(uid, bean);
    }

}
