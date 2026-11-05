package com.aiproject.ai.permission.holde;

import com.aiproject.ai.permission.bean.LableChildBean;
import com.aiproject.ai.permission.mapper.LableChildBeanMapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.heneng.hnsp.common.db.utils.PageResult;

import java.lang.String;

import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.croe.model.Result;
import com.aiproject.ai.permission.bean.LableBean;
import com.aiproject.ai.permission.mapper.LableBeanMapper;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/05/11 11:59:59
 */
@Slf4j
@Service
public class LableBeanHolder {
    /**
     * 数据库Mapper.
     */
    @Autowired
    LableBeanMapper<LableBean> lableBeanMapper = null;

    @Autowired
    LableChildBeanMapper<LableChildBean> lableChildBeanMapper = null;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder = null;

    /**
     * 标签组是全平台共享的基础数据（各业务资源都通过它打标签），写操作限管理员。
     *
     * <p>2026-10-29 补充：此前 install / delete / update 三个写方法完全没有鉴权，
     * 任意已登录用户都能增删改全平台标签组。这里按项目既有约定补齐——
     * 返回 null 表示校验通过，返回非 null 表示被拒绝（与 ModelsConfigBeanHolder 一致）。</p>
     *
     * <p>2026-11-04 补充：鉴权之外还要校验参数。Mapper 是按非空字段拼 where 的，
     * 空条件 delete 会删掉所有标签组并级联清空所有绑定，空条件 update 会改全表。</p>
     */
    private Result<Boolean> checkAdmin(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护标签组");
        }
        return null;
    }

    /** 标签组的业务主键是 iid，删除必须精确到行。 */
    private Result<Boolean> checkLabelId(LableBean data) {
        if (data == null || data.getIid() == null || data.getIid() <= 0) {
            return Result.failed(false, "缺少标签组主键");
        }
        return null;
    }

    private Result<Boolean> checkLabelNameAndType(LableBean data) {
        if (data == null || isEmpty(data.getName()) || isEmpty(data.getType())) {
            return Result.failed(false, "标签名称和类型不能为空");
        }
        return null;
    }

    private Result<Boolean> checkUpdateWhere(LableBean where) {
        if (where == null) {
            return Result.failed(false, "缺少更新条件");
        }
        if (where.getIid() == null || where.getIid() <= 0) {
            return Result.failed(false, "缺少标签组主键");
        }
        return null;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 功能描述: 增加.
     *
     * @param uid  用户id
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/11 11:59:59
     **/

    public Result<Boolean> install(String uid, LableBean data) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        Result<Boolean> params = checkLabelNameAndType(data);
        if (params != null) {
            return params;
        }
        //查询名称是否存在
        LableBean bean = new LableBean();
        bean.setType(data.getType());
        bean.setName(data.getName());
        bean = this.query(bean);
        if (null != bean) {
            return Result.failed(false, "增加失败，标签名称已存在");
        }
        if (lableBeanMapper.install(data) <= 0) {
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
     * @date 2026/05/11 11:59:59
     **/

    public Result<Boolean> delete(String uid, LableBean data) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        Result<Boolean> params = checkLabelId(data);
        if (params != null) {
            return params;
        }

        if (lableBeanMapper.delete(data) <= 0) {
            return Result.failed(false, "删除失败");
        }
        //删除子项
        LableChildBean lableChildBean = new LableChildBean();
        lableChildBean.setLableiid(data.getIid());
        lableChildBeanMapper.delete(lableChildBean);
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
     * @date 2026/05/11 11:59:59
     **/

    public Result<Boolean> update(String uid, LableBean data, LableBean where) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        if (data == null) {
            return Result.failed(false, "缺少更新数据");
        }
        if (data.getName() != null && isEmpty(data.getName())) {
            return Result.failed(false, "标签名称不能为空");
        }
        Result<Boolean> whereCheck = checkUpdateWhere(where);
        if (whereCheck != null) {
            return whereCheck;
        }

        data.setLastuser(uid);
        if (lableBeanMapper.update(data, where) <= 0) {
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
     * @date 2026/05/11 11:59:59
     **/

    public LableBean query(LableBean data) {

        List<LableBean> list = lableBeanMapper.select(null, data);
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
     * @date 2026/05/11 11:59:59
     **/

    public List<LableBean> queryList(LableBean data, PageRequest pageRequest) {
        if (null == pageRequest) {
            pageRequest = new PageRequest();
            pageRequest.setOrder("iid");
        }
        pageRequest.setDesc("desc");
        return lableBeanMapper.select(pageRequest, data);
    }

    /**
     * 功能描述: 分页查询.
     *
     * @param data        数据
     * @param pageRequest 分页条件
     * @return Result
     * @author heneng
     * @date 2026/05/11 11:59:59
     **/

    public Result<PageResult<LableBean>> queryPage(LableBean data, PageRequest pageRequest) {

        Result<PageResult<LableBean>> result = lableBeanMapper.selectPageLike(pageRequest, data);
        result.setResp_msg("查询成功");
        return result;
    }

}
