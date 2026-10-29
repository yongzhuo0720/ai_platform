package com.aiproject.ai.permission.holde;

import com.aiproject.ai.permission.bean.LableBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.aiproject.ai.permission.spi.LableBindingResourceGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.heneng.hnsp.common.db.utils.PageResult;

import java.lang.String;
import java.util.Map;

import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.croe.model.Result;
import com.aiproject.ai.permission.bean.LableChildBean;
import com.aiproject.ai.permission.mapper.LableChildBeanMapper;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/05/11 14:03:59
 */
@Slf4j
@Service
public class LableChildBeanHolder {
    /**
     * 数据库Mapper.
     */
    @Autowired
    LableChildBeanMapper<LableChildBean> lableChildBeanMapper = null;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder = null;

    /** 资源归属校验实现（由业务模块提供，如 knowledge_base 的所有者判定） */
    @Autowired(required = false)
    LableBindingResourceGuard resourceGuard = null;

    /**
     * 本表是「资源 ←→ 标签组」的绑定表，不是标签管理数据：
     * hash = 被引用资源主键，type = 模块（applications / models / knowledge_base ...），
     * lableiid = 标签组 iid。
     * <p>
     * 写操作按资源归属收紧：超级管理员可维护全部绑定；普通用户只能维护
     * 自己名下知识库的标签（知识库详情页/广场依赖此能力），其余类型
     * 前端本就只对超管开放，后端同样按管理员限制。
     * 标签组本身的增删改（LableBeanHolder）才是管理员专属。
     */
    private Result<Boolean> checkManageBinding(String uid, LableChildBean data) {
        Result<Boolean> login = checkLogin(uid);
        if (login != null) {
            return login;
        }
        if (myUserBaseDbHolder.isAdmin(uid)) {
            return null;
        }
        if (data == null || isEmpty(data.getType()) || isEmpty(data.getHash())) {
            return Result.failed(false, "缺少资源类型或主键");
        }
        if (resourceGuard != null && data.getType().equals(resourceGuard.type())
                && resourceGuard.canManage(uid, data.getHash())) {
            return null;
        }
        return Result.failed(false, "仅资源所有者或管理员可维护标签绑定");
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Result<Boolean> checkLogin(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        return null;
    }

    /**
     * 绑定关系的整行修改没有合法前端调用方，保持管理员校验作为兜底。
     * 返回 null 表示校验通过，非 null 表示被拒绝（与 ModelsConfigBeanHolder 一致）。
     */
    private Result<Boolean> checkAdmin(String uid) {
        Result<Boolean> login = checkLogin(uid);
        if (login != null) {
            return login;
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护标签绑定");
        }
        return null;
    }

    /**
     * 功能描述: 增加.
     *
     * @param uid  用户id
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/11 14:03:59
     **/

    public Result<Boolean> install(String uid, LableChildBean data) {
        Result<Boolean> check = checkManageBinding(uid, data);
        if (check != null) {
            return check;
        }

        if (lableChildBeanMapper.install(data) <= 0) {
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
     * @date 2026/05/11 14:03:59
     **/

    public Result<Boolean> delete(String uid, LableChildBean data) {
        Result<Boolean> check = checkManageBinding(uid, data);
        if (check != null) {
            return check;
        }

        if (lableChildBeanMapper.delete(data) <= 0) {
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
     * @date 2026/05/11 14:03:59
     **/

    public Result<Boolean> update(String uid, LableChildBean data, LableChildBean where) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }

        data.setLastuser(uid);
        if (lableChildBeanMapper.update(data, where) <= 0) {
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
     * @date 2026/05/11 14:03:59
     **/

    public LableChildBean query(LableChildBean data) {

        List<LableChildBean> list = lableChildBeanMapper.select(null, data);
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
     * @date 2026/05/11 14:03:59
     **/

    public List<LableChildBean> queryList(LableChildBean data, PageRequest pageRequest) {

        return lableChildBeanMapper.select(pageRequest, data);
    }

    /**
     * 功能描述: 分页查询.
     *
     * @param data        数据
     * @param pageRequest 分页条件
     * @return Result
     * @author heneng
     * @date 2026/05/11 14:03:59
     **/

    public Result<PageResult<LableChildBean>> queryPage(LableChildBean data, PageRequest pageRequest) {

        return lableChildBeanMapper.selectPageLike(pageRequest, data);
    }

    public Result<List<LableChildBean>> querys(LableChildBean data) {
        List<LableChildBean> list = this.queryList(data, null);
        List<LableChildBean> resultList =     reorganizeByTraditional(list);
        return Result.succeed(resultList,"查询成功");
    }

    /**
     * 方式一：使用传统 Map 方式（兼容性好，适合旧版本Java）
     */
    public static List<LableChildBean> reorganizeByTraditional(List<LableChildBean> originalList) {
        if (originalList == null || originalList.isEmpty()) {
            return new ArrayList<>();
        }

        // 使用组合键 (type + "#" + hash) 作为分组依据
        Map<String, LableChildBean> groupedMap = new HashMap<>();

        for (LableChildBean item : originalList) {
            String key = item.getType() + "#" + item.getHash();

            if (groupedMap.containsKey(key)) {
                // 已存在相同分组，将当前项的标签信息添加到现有分组的 lableBeans 中
                LableChildBean existing = groupedMap.get(key);

                // 创建新的 LableBean 并添加到列表中
                LableBean lableBean = new LableBean();
                lableBean.setIid(item.getLableiid());
                lableBean.setName(item.getName());

                if (existing.getLableBeans() == null) {
                    existing.setLableBeans(new ArrayList<>());
                }
                existing.getLableBeans().add(lableBean);
            } else {
                // 新分组：创建新对象，并将当前标签信息放入 lableBeans
                LableChildBean newGroup = new LableChildBean();
                newGroup.setType(item.getType());
                newGroup.setHash(item.getHash());
                // 注意：新分组的 name 字段可能为空或不需要，因为具体标签名称在 lableBeans 中
                newGroup.setName(null); // 或保留第一个name

                // 初始化 lableBeans 并添加当前标签
                List<LableBean> lableBeans = new ArrayList<>();
                LableBean lableBean = new LableBean();
                lableBean.setIid(item.getLableiid());
                lableBean.setName(item.getName());
                lableBeans.add(lableBean);
                newGroup.setLableBeans(lableBeans);

                groupedMap.put(key, newGroup);
            }
        }

        return new ArrayList<>(groupedMap.values());
    }
}
