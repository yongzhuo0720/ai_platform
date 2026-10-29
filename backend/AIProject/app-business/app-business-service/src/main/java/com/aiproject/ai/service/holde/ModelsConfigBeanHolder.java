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

    /**
     * 面向「外部调用方（浏览器）」的查询：返回前统一脱敏（2026-10-29 新增）。
     *
     * <p><b>为什么是新增方法，而不是直接改 queryList。</b>
     * EmbeddingClient、ModelGatewayResolver、RerankResolver 这三个网关内部组件
     * 依赖 {@code queryList} 拿到<b>真实</b> apiKey 去调向量服务 / 直连模型 / 精排服务。
     * 一旦在 queryList 里脱敏，向量化、直连对话、Rerank 会全部失效——
     * 属于「为了安全把功能搞挂」的典型错误。所以内部调用保持原样，
     * 只给对外的入口脱敏。</p>
     *
     * <p><b>另外为什么要把脱敏下沉到 Holder。</b>
     * 原来脱敏写在 Controller 的私有方法里（ModelsConfigBeanController.maskApiKeys）。
     * 那意味着「安不安全」取决于每个调用点记不记得调它——
     * 将来任何人新增一个接口直接复用 Holder 就会把密钥漏出去。
     * 放到 Holder 里，调用方想漏也漏不掉。</p>
     *
     * <p>脱敏规则：apiKey 一律不下发（管理员也一样，编辑时用「留空表示不修改」的语义）；
     * baseUrl 属于内网拓扑信息，仅管理员可见。</p>
     */
    public List<ModelsConfigBean> queryListForCaller(String uid, ModelsConfigBean data, PageRequest pageRequest) {
        List<ModelsConfigBean> list = queryList(data, pageRequest);
        maskSecrets(list, isAdminCaller(uid));
        return list;
    }

    /**
     * 同 {@link #queryListForCaller(String, ModelsConfigBean, PageRequest)}，分页版本。
     */
    public Result<PageResult<ModelsConfigBean>> queryPageForCaller(String uid, ModelsConfigBean data, PageRequest pageRequest) {
        Result<PageResult<ModelsConfigBean>> result = queryPage(data, pageRequest);
        if (result != null && result.getDatas() != null) {
            maskSecrets(result.getDatas().getContent(), isAdminCaller(uid));
        }
        return result;
    }

    private boolean isAdminCaller(String uid) {
        return uid != null && !uid.trim().isEmpty() && myUserBaseDbHolder.isAdmin(uid);
    }

    private void maskSecrets(List<ModelsConfigBean> list, boolean admin) {
        if (list == null) {
            return;
        }
        for (ModelsConfigBean item : list) {
            if (item == null) {
                continue;
            }
            if (item.getApiKey() != null && !item.getApiKey().isEmpty()) {
                item.setApiKey("");
            }
            if (!admin && item.getBaseUrl() != null && !item.getBaseUrl().isEmpty()) {
                item.setBaseUrl("");
            }
        }
    }

}
