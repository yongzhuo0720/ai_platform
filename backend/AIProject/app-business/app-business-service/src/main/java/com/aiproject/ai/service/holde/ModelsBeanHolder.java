package com.aiproject.ai.service.holde;

import java.util.List;

import com.aiproject.ai.common.utils.WeekUtils;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.ModelsDownloadsBean;
import com.aiproject.ai.service.mapper.ModelsDownloadsBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.PageRequest;

import java.lang.String;
import java.util.Map;

import com.heneng.hnsp.common.db.utils.SqlFieldTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.aiproject.ai.service.bean.ModelsBean;
import com.aiproject.ai.service.mapper.ModelsBeanMapper;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/05/13 14:53:02
 */
@Slf4j
@Service
public class ModelsBeanHolder {
    /**
     * 数据库Mapper.
     */
    @Autowired
    ModelsBeanMapper<ModelsBean> modelsBeanMapper = null;

    @Autowired
    ModelsDownloadsBeanHolder modelsDownloadsBeanHolder = null;

    @Autowired
    ModelsDownloadsBeanMapper<ModelsDownloadsBean> modelsDownloadsBeanMapper = null;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    /**
     * 模型仓库是「平台级目录」，前端 ModelsView 的增/改/删按钮以 rid==99999 控制。
     * 注意：addDownloads（记录下载次数）是普通用户的正常行为，不在此列，保持开放。
     */
    private Result<Boolean> checkAdmin(String uid) {
        if (uid == null) {
            return Result.failed(false, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护模型仓库");
        }
        return null;
    }

    /**
     * 功能描述: 增加.
     *
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public Result<Boolean> install(String uid, ModelsBean data) {

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
    public Result<Boolean> installInternal(ModelsBean data) {
        if (modelsBeanMapper.install(data) <= 0) {
            return Result.failed(false, "增加失败");
        }
        return Result.succeed(true, "增加成功");
    }

    /**
     * 功能描述: 删除.
     *
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public Result<Boolean> delete(String uid, ModelsBean data) {

        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        if (modelsBeanMapper.delete(data) <= 0) {
            return Result.failed(false, "删除失败");
        }
        return Result.succeed(true, "删除成功");
    }

    /**
     * 功能描述: 修改.
     *
     * @param uid   用户id
     * @param data  数据
     * @param where 条件
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public Result<Boolean> update(String uid, ModelsBean data, ModelsBean where) {

        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        data.setLastuser(uid);
        if (modelsBeanMapper.update(data, where) <= 0) {
            return Result.failed(false, "修改失败");
        }
        return Result.succeed(true, "修改成功");
    }

    /**
     * 功能描述: 查询单个.
     *
     * @param data 数据
     * @return 对象
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public ModelsBean query(ModelsBean data) {

        List<ModelsBean> list = modelsBeanMapper.select(null, data);
        if (list == null || list.size() == 0) {
            return null;
        }
        return list.get(0);
    }

    /**
     * 功能描述: 查询集合.
     *
     * @param data        数据
     * @param pageRequest 分页条件
     * @return List
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public List<ModelsBean> queryList(ModelsBean data, PageRequest pageRequest) {

        return modelsBeanMapper.select(pageRequest, data);
    }

    /**
     * 功能描述: 分页查询.
     *
     * @param data        数据
     * @param pageRequest 分页条件
     * @return Result
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public Result<PageResult<ModelsBean>> queryPage(ModelsBean data, PageRequest pageRequest) {

        return modelsBeanMapper.selectPageLike(pageRequest, data);
    }

    public Result<List<ModelsBean>> queryList(ModelsBean data) {

        return Result.succeed(modelsBeanMapper.select(null, data), "查询成功");
    }

    public Result<Boolean> addDownloads(String uid, ModelsBean data) {
        ModelsDownloadsBean modelsDownloadsBean = new ModelsDownloadsBean();
        modelsDownloadsBean.setMoudelsiid(data.getIid());
        modelsDownloadsBean.setUser(uid);
        Result<Boolean> result = modelsDownloadsBeanHolder.install(modelsDownloadsBean);
        if (result.getDatas()) {
            return Result.succeed(true, "下载成功");
        }
        return Result.failed(false, "下载失败");
    }

    public Result<Integer> queryWeeklyDownloads(ModelsBean data) {
        Map<String, String> map = WeekUtils.getCurrentWeekRange();
        String sql = SqlFieldTools.timeBetween(modelsDownloadsBeanMapper.getTableName(), "createtime", map.get("startDateTime"), map.get("endDateTime"));
        int count = modelsDownloadsBeanMapper.count(sql);
        if (count >= 0) {
            return Result.succeed(count, "查询成功");
        }
        return Result.failed(count, "查询失败");
    }
}
