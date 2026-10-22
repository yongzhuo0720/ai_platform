package com.aiproject.ai.service.holde;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.PageRequest;
import java.lang.String;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.aiproject.ai.service.bean.DatasetsBean;
import com.aiproject.ai.common.dto.FileItemDTO;
import com.aiproject.ai.service.mapper.DatasetsBeanMapper;


/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 * @author heneng
 * @date 2026/05/13 14:53:02
 */
@Slf4j
@Service
public  class DatasetsBeanHolder  { 
        /**
     * 数据库Mapper.
     */ 
    @Autowired
    DatasetsBeanMapper<DatasetsBean> datasetsBeanMapper = null;

    /**
     * 用于写操作的超管判定。
     */
    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    /**
     * 数据集仓库是「平台级目录」，前端 DatasetView 的增删改按钮全部以 rid==99999 控制，
     * 说明产品定义里只有超管能维护。后端此前没有任何校验，
     * 任意登录用户直接 POST /api/datasetsBean/delete 就能删掉整张目录 —— 典型的「仅前端鉴权」。
     */
    private Result<Boolean> checkAdmin(String uid) {
        if (uid == null) {
            return Result.failed(false, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护数据集仓库");
        }
        return null;
    }

            /**
     * 功能描述: 增加.
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/ 

    public Result<Boolean> install(String uid, DatasetsBean data){
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        return installInternal(data);
    }

    /**
     * 内部插入：不做管理员校验。
     * 仅供启动期数据注册器（ServiceDataRegister）等内部代码调用，不得暴露到 HTTP 接口。
     */
    public Result<Boolean> installInternal(DatasetsBean data){
        if (datasetsBeanMapper.install(data) <= 0){
            return Result.failed(false,"增加失败");
        }
        return Result.succeed(true,"增加成功");
    }

        /**
     * 功能描述: 删除.
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/ 

    public Result<Boolean> delete(String uid, DatasetsBean data){

        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        if (datasetsBeanMapper.delete(data) <= 0){
            return Result.failed(false,"删除失败");
        }
        return Result.succeed(true,"删除成功"); 
    }

        /**
     * 功能描述: 修改.
     * @param uid 用户id
     * @param data 数据
     * @param where 条件
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/ 

    public Result<Boolean> update(String uid,DatasetsBean data,DatasetsBean where){

        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        data.setLastuser(uid);
        if (datasetsBeanMapper.update(data, where) <= 0){
            return Result.failed(false,"修改失败");
        }
        return Result.succeed(true,"修改成功"); 
    }

        /**
     * 功能描述: 查询单个.
     * @param data 数据
     * @return 对象
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/ 

    public DatasetsBean query(DatasetsBean data){
        
        List<DatasetsBean> list = datasetsBeanMapper.select(null,data);
        if(list == null || list.size() == 0){
            return null;
        }
        return list.get(0); 
    }

        /**
     * 功能描述: 查询集合.
     * @param data 数据
     * @param pageRequest 分页条件
     * @return List
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/ 

    public List<DatasetsBean> queryList(DatasetsBean data,PageRequest pageRequest){
        
        return datasetsBeanMapper.select(pageRequest, data); 
    }

        /**
     * 功能描述: 分页查询.
     * @param data 数据
     * @param pageRequest 分页条件
     * @return Result
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/ 

    public Result<PageResult<DatasetsBean>> queryPage(DatasetsBean data,PageRequest pageRequest){
        
        return datasetsBeanMapper.selectPageLike(pageRequest, data); 
    }
    public Result<List<DatasetsBean>> queryList(DatasetsBean data ){

        return Result.succeed(datasetsBeanMapper.select(null, data),"查询成功");
    }



}
