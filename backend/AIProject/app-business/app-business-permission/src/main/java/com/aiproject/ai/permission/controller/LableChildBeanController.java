package com.aiproject.ai.permission.controller;

import blxt.qjava.utils.check.CheckUtils;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import io.swagger.annotations.ApiOperation;
import com.heneng.hnsp.common.db.utils.PageResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.lang.String;
import java.util.List;

import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.croe.model.Result;
import io.swagger.annotations.ApiParam;
import com.heneng.hnsp.common.model.CodeEnum;
import org.springframework.web.bind.annotation.RestController;
import com.heneng.hnsp.common.inter.ControllerLog;
import com.heneng.hnsp.common.inter.LoginUserId;
import org.springframework.web.bind.annotation.PostMapping;
import io.swagger.annotations.Api;
import com.aiproject.ai.permission.bean.LableChildBean;
import com.aiproject.ai.permission.holde.LableChildBeanHolder;


/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 * @author heneng
 * @date 2026/05/11 14:03:59
 */
@RestController
@Slf4j
@RequestMapping("/api/lableChildBean")
@Api(value = "标签子项", tags="标签子项")
public  class LableChildBeanController  { 
        /**
     * 数据操作执行器.
     */ 
    @Autowired
    LableChildBeanHolder lableChildBeanHolder = null;

  
            /**
     * 功能描述: 创建.
     * @param uid 用户id
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/11 14:03:59
     **/ 
    @ControllerLog(value = "创建标签子项", user = "uid", target="")
    @PostMapping("/create")
    @ApiOperation(value = "创建", httpMethod = "POST", notes = "创建")
    public Result<Boolean> create(@LoginUserId String uid,@ApiParam(required = true, value = "数据") @RequestBody LableChildBean data){
        
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return lableChildBeanHolder.install(uid, data);
    }

        /**
     * 功能描述: 删除.
     * @param uid 用户id
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/11 14:03:59
     **/ 
    @ControllerLog(value = "删除标签子项", user = "uid", target="")
    @PostMapping("/delete")
    @ApiOperation(value = "删除", httpMethod = "POST", notes = "删除")
    public Result<Boolean> delete(@LoginUserId String uid,@ApiParam(required = true, value = "数据") @RequestBody LableChildBean data){
        
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return lableChildBeanHolder.delete(uid, data);
    }

        /**
     * 功能描述: 更新.
     * @param uid 用户id
     * @param data 数据
     * @param where 条件
     * @return Boolean
     * @author heneng
     * @date 2026/05/11 14:03:59
     **/ 
    @ControllerLog(value = "更新标签子项", user = "uid", target="")
    @PostMapping("/update")
    @ApiOperation(value = "更新", httpMethod = "POST", notes = "更新")
    public Result<Boolean> update(@LoginUserId String uid,@ApiParam(required = true, value = "数据") @RequestBody LableChildBean data,@ApiParam(required = true, value = "条件") @RequestBody LableChildBean where){
        
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(where, "条件不能空", CodeEnum.NULL.getCode(), null);
        return lableChildBeanHolder.update(uid, data, where); 
    }

        /**
     * 功能描述: 查询标签子项.
     * @param uid 用户id
     * @param data 数据

     * @return Result
     * @author heneng
     * @date 2026/05/11 14:03:59
     **/ 
    @ControllerLog(value = "查询标签子项", user = "uid", target="")
    @PostMapping("/query")
    @ApiOperation(value = "查询标签子项", httpMethod = "POST", notes = "查询标签子项")
    public Result<List<LableChildBean>> query(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody LableChildBean data ){
        
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return lableChildBeanHolder.querys(data);
    }

 
}
