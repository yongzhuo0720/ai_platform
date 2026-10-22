package com.aiproject.ai.service.holde;

import java.util.List;
import com.heneng.hnsp.common.db.utils.PageRequest;
import lombok.extern.slf4j.Slf4j;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import java.lang.String;
import com.heneng.hnsp.common.croe.model.Result;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.mapper.ModelsConfigBeanMapper;


/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 * @author heneng
 * @date 2026/07/23 14:14:45
 */
@Slf4j
@Service
public  class ModelsConfigBeanHolder  { 
        /**
     * 数据库Mapper.
     */ 
    @Autowired
    ModelsConfigBeanMapper<ModelsConfigBean> modelsConfigBeanMapper = null;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    /**
     * 模型配置属平台级配置（前端仅在 Admin/ModelsConfigView 维护），写操作限超管。
     */
    private Result<Boolean> checkAdmin(String uid) {
        if (uid == null) {
            return Result.failed(false, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护模型配置");
        }
        return null;
    }

            /**
     * 功能描述: 增加.
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/07/23 14:14:45
     **/ 

    public Result<Boolean> install(String uid, ModelsConfigBean data){
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        if (modelsConfigBeanMapper.install(data) <= 0){
            return Result.failed(false,"增加失败");
        }
        return Result.succeed(true,"增加成功"); 
    }

        /**
     * 功能描述: 删除.
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/07/23 14:14:45
     **/ 

    public Result<Boolean> delete(String uid, ModelsConfigBean data){
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        if (modelsConfigBeanMapper.delete(data) <= 0){
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
     * @date 2026/07/23 14:14:45
     **/ 

    public Result<Boolean> update(String uid,ModelsConfigBean data,ModelsConfigBean where){
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        /*
         * 前端编辑时密钥框留空表示「不修改」：查询接口不下发密钥，
         * 若直接以空值覆盖会把已配置的 apiKey 清掉，这里按 where.iid 回读补齐。
         */
        if ((data.getApiKey() == null || data.getApiKey().trim().isEmpty())
                && where != null && where.getIid() != null) {
            ModelsConfigBean existing = queryByIid(where.getIid());
            if (existing != null && existing.getApiKey() != null) {
                data.setApiKey(existing.getApiKey());
            }
        }
        data.setLastuser(uid);
        if (modelsConfigBeanMapper.update(data, where) <= 0){
            return Result.failed(false,"修改失败");
        }
        return Result.succeed(true,"修改成功");
    }

    private ModelsConfigBean queryByIid(Long iid) {
        ModelsConfigBean where = new ModelsConfigBean();
        where.setIid(iid);
        List<ModelsConfigBean> list = modelsConfigBeanMapper.select(null, where);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

        /**
     * 功能描述: 查询单个.
     * @param data 数据
     * @return 对象
     * @author heneng
     * @date 2026/07/23 14:14:45
     **/ 

    public ModelsConfigBean query(ModelsConfigBean data){
        
        List<ModelsConfigBean> list = modelsConfigBeanMapper.select(null,data);
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
     * @date 2026/07/23 14:14:45
     **/ 

    public List<ModelsConfigBean> queryList(ModelsConfigBean data,PageRequest pageRequest){
        
        return modelsConfigBeanMapper.selectLike(pageRequest, data);
    }

        /**
     * 功能描述: 分页查询.
     * @param data 数据
     * @param pageRequest 分页条件
     * @return Result
     * @author heneng
     * @date 2026/07/23 14:14:45
     **/ 

    public Result<PageResult<ModelsConfigBean>> queryPage(ModelsConfigBean data,PageRequest pageRequest){
        
        return modelsConfigBeanMapper.selectPageLike(pageRequest, data); 
    }

 
}
