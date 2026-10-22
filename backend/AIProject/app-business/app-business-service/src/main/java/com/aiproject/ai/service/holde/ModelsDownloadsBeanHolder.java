package com.aiproject.ai.service.holde;

import java.util.List;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.PageRequest;
import java.lang.String;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.aiproject.ai.service.bean.ModelsDownloadsBean;
import com.aiproject.ai.service.mapper.ModelsDownloadsBeanMapper;


/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 * @author heneng
 * @date 2026/05/13 16:35:42
 */
@Slf4j
@Service
public  class ModelsDownloadsBeanHolder  { 
        /**
     * 数据库Mapper.
     */ 
    @Autowired
    ModelsDownloadsBeanMapper<ModelsDownloadsBean> modelsDownloadsBeanMapper = null;

  
            /**
     * 功能描述: 增加.
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 16:35:42
     **/ 

    public Result<Boolean> install(ModelsDownloadsBean data){
        
        if (modelsDownloadsBeanMapper.install(data) <= 0){
            return Result.failed(false,"增加失败");
        }
        return Result.succeed(true,"增加成功"); 
    }

        /**
     * 功能描述: 删除.
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 16:35:42
     **/ 

    public Result<Boolean> delete(ModelsDownloadsBean data){
        
        if (modelsDownloadsBeanMapper.delete(data) <= 0){
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
     * @date 2026/05/13 16:35:42
     **/ 

    public Result<Boolean> update(String uid,ModelsDownloadsBean data,ModelsDownloadsBean where){
        
        data.setLastuser(uid);
        if (modelsDownloadsBeanMapper.update(data, where) <= 0){
            return Result.failed(false,"修改失败");
        }
        return Result.succeed(true,"修改成功"); 
    }

        /**
     * 功能描述: 查询单个.
     * @param data 数据
     * @return 对象
     * @author heneng
     * @date 2026/05/13 16:35:42
     **/ 

    public ModelsDownloadsBean query(ModelsDownloadsBean data){
        
        List<ModelsDownloadsBean> list = modelsDownloadsBeanMapper.select(null,data);
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
     * @date 2026/05/13 16:35:42
     **/ 

    public List<ModelsDownloadsBean> queryList(ModelsDownloadsBean data,PageRequest pageRequest){
        
        return modelsDownloadsBeanMapper.select(pageRequest, data); 
    }

        /**
     * 功能描述: 分页查询.
     * @param data 数据
     * @param pageRequest 分页条件
     * @return Result
     * @author heneng
     * @date 2026/05/13 16:35:42
     **/ 

    public Result<PageResult<ModelsDownloadsBean>> queryPage(ModelsDownloadsBean data,PageRequest pageRequest){
        
        return modelsDownloadsBeanMapper.selectPageLike(pageRequest, data); 
    }

 
}
