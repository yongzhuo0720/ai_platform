package com.aiproject.ai.permission.holde;

import blxt.qjava.utils.check.CheckUtils;
import com.aiproject.ai.common.utils.ListToTreeUtil;
import com.aiproject.ai.permission.bean.UnitBean;
import com.aiproject.ai.permission.mapper.UnitBeanMapper;
import com.heneng.hnsp.business.user.bean.UserBaseDb;
import com.heneng.hnsp.business.user.server.UserBaseHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * [请在这里添加注释]
 *
 * @author openJL
 * @date 2023/02/22 15:24:56
 * 此代码由[HSNP]快速开发框架自动生成
 */
@Slf4j()
@Service()
public class UnitBeanHolder {
    /**
     * 数据库Mapper
     */
    @Autowired()
    UnitBeanMapper<UnitBean> unitBeanMapper = null;

    @Autowired
    UserBaseHolder userBaseHolder;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    /**
     * 部门（unit）是 RBAC 的支柱：用户表的 gid 指向 unit.unitsno，
     * 资源可见性过滤（ResourceVisibility）与各部门数据范围都依赖它。
     * 一旦被任意已登录用户篡改，就等于可以撬动全平台资源可见性，因此写操作必须限管理员。
     *
     * <p>2026-10-29 补充：此前 install / delete / update 三个写方法完全没有鉴权，
     * 任意已登录用户都能新建、删除、篡改平台部门树。这里补齐校验。
     * 返回 null 表示校验通过，非 null 表示被拒绝（与 ModelsConfigBeanHolder 一致）。</p>
     */
    private Result<Boolean> checkAdmin(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护部门");
        }
        return null;
    }

    /**
     * 增加
     */

    public Result<Boolean> install(String uid, UnitBean data) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        String sno = createUnitSno(data.getParentsno());
        data.setUnitsno(sno);
        data.setLastuser(uid);
        if (unitBeanMapper.install(data) <= 0) {
            return Result.failed(false, "增加失败");
        }
        return Result.succeed(true, "增加成功");
    }

    /**
     * 生成部门编号 unitsno。
     *
     * <p>修复前的三个问题：</p>
     * <ol>
     *   <li>{@code parentsno} 为 null 时直接 NPE —— 创建顶级部门很可能不传这个字段；</li>
     *   <li>{@code sno.substring(sno.length() - 4)} 在长度不足 4 时抛 StringIndexOutOfBounds；</li>
     *   <li>{@code Long.valueOf(sno)} 遇到非数字内容直接抛 NumberFormatException；</li>
     *   <li>MAX + 自增没有唯一约束保护，并发创建同级部门会生成重复 unitsno。</li>
     * </ol>
     *
     * <p>这里的兜底策略是「取不到可用最大值就从 1111 重新开始」而不是抛异常——
     * 部门编号是展示用字段，不应让一个格式问题阻断建部门。
     * 并发重复的问题需要在 unitsno 上加唯一约束才能真正解决，
     * 那属于 schema 变更，见 docs 中的说明。</p>
     */
    private String createUnitSno(String parentsno) {
        String parent = parentsno == null ? "" : parentsno.trim();
        boolean topLevel = parent.isEmpty() || "0".equals(parent);

        String sno = unitBeanMapper.queryMaxSno(parent);
        String unit_sno;
        if (CheckUtils.isNotEmpty(sno) && sno.length() >= 4 && isNumeric(sno)) {
            String tail = sno.substring(sno.length() - 4);
            long next = Long.parseLong(tail) + 1;
            unit_sno = new DecimalFormat("0000").format(next);
        } else {
            unit_sno = "1111";
        }
        if (!topLevel) {
            unit_sno = parent + unit_sno;
        }
        return unit_sno;
    }

    /** 纯数字校验：部门编号只允许数字，避免 Long.valueOf 抛异常 */
    private static boolean isNumeric(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    /**
     * 删除
     */

    public Result<Boolean> delete(String uid, UnitBean data) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        //查询是否由下级部门
        boolean reulst = queryChild(data);
        if (!reulst) {
            return Result.failed(false, "请先删除下级部门");
        }
        if (unitBeanMapper.delete(data) <= 0) {
            return Result.failed(false, "删除失败");
        }
        return Result.succeed(true, "删除成功");
    }

    private boolean queryChild(UnitBean data) {
        data = this.query(data);
        if (data == null) {
            return true;
        }
        UnitBean bean = new UnitBean();
        bean.setParentsno(data.getUnitsno());
        bean = this.query(bean);
        if (bean == null) {
            return true;
        }
        return false;
    }

    /**
     * 修改
     */

    public Result<Boolean> update(String uid, UnitBean data, UnitBean where) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }

        data.setLastuser(uid);
        if (unitBeanMapper.update(data, where) <= 0) {
            return Result.failed(false, "修改失败");
        }
        return Result.succeed(true, "修改成功");
    }

    /**
     * 查询单个
     */

    public UnitBean query(UnitBean data) {

        List<UnitBean> list = unitBeanMapper.select(null, data);
        if (list == null || list.size() == 0) {
            return null;
        }
        return list.get(0);
    }

    /**
     * 查询集合
     */

    public List<UnitBean> queryList(UnitBean data, PageRequest pageRequest) {

        return unitBeanMapper.select(pageRequest, data);
    }

    /**
     * 分页查询
     */

    public Result<PageResult<UnitBean>> queryPage(UnitBean data, PageRequest pageRequest) {

        return unitBeanMapper.selectPage(pageRequest, data);
    }

    public Result<List<UnitBean>> queryTree(String uid, UnitBean data) {

        List<UnitBean> list = unitBeanMapper.selectLike(null, data);
        List<UnitBean> retList = new ListToTreeUtil<UnitBean>()
                .code(UnitBean::getUnitsno)
                .parent(UnitBean::getParentsno)
                .children(unitBean -> {
                    if (unitBean.getChildList() == null) {
                        unitBean.setChildList(new ArrayList<>());
                    }
                    return unitBean.getChildList();
                })
                .tree(list);
        return Result.succeed(retList, "操作成功");
    }

    /**
     * 功能描述: 查询树结构（可根据多个iid查子集）
     *
     * @param uid
     * @param data
     * @return
     * @author zzh
     * @date 2024/10/24 下午2:15
     */
    public Result<List<UnitBean>> queryChildTree(String uid, UnitBean data) {

        UserBaseDb userBaseDb = new UserBaseDb();
        userBaseDb.setUid(uid);
        UserBaseDb user = userBaseHolder.query(userBaseDb);

        if (user != null && user.getRid() != null && user.getRid().equals(99999L)) {
            // 超管
            return queryTree(uid, data);
        }

        if (user == null || user.getGid() == null || user.getGid().isEmpty()) {
            return Result.failed(null, "当前用户未关联部门");
        }
        UnitBean unitBean1 = new UnitBean();
        if (CheckUtils.isNotEmpty(data.getEnable())) {
            unitBean1.setEnable(data.getEnable());
        }
        unitBean1.setUnitname(data.getUnitname());
        List<UnitBean> list = unitBeanMapper.selectLike(null, unitBean1);

        List<UnitBean> tree = new LinkedList<>();
        UnitBean bean = new UnitBean();
        bean.setUnitsno( user.getGid());
        UnitBean query = query(bean);
        List<UnitBean> retList = new ListToTreeUtil<UnitBean>()
                .code(UnitBean::getUnitsno)
                .parent(UnitBean::getParentsno)
                .children(unitBean -> {
                    if (unitBean.getChildList() == null) {
                        unitBean.setChildList(new ArrayList<>());
                    }
                    return unitBean.getChildList();
                })
                .tree(list, Long.valueOf(user.getGid()));
        query.setChildList(retList);
        tree.add(query);
        return Result.succeed(tree, "操作成功");
    }

    public List<Long> queryChildsIids(Long iid) {
        List<Long> iids = new ArrayList<>();
        iids.add(iid);

        //查询所有单位信息
        List<UnitBean> list = this.queryList(new UnitBean(), null);
        //构造树结构
        List<UnitBean> retList = new ListToTreeUtil<UnitBean>()
                .code(UnitBean::getUnitsno)
                .parent(UnitBean::getParentsno)
                .children(unitBean -> {
                    if (unitBean.getChildList() == null) {
                        unitBean.setChildList(new ArrayList<>());
                    }
                    return unitBean.getChildList();
                })
                .tree(list, iid);
        queryChildIds(retList, iids);
        return iids;
    }

    /**
     * 查询单个
     */

    /**
     * 功能描述: 根据uid和字段名称,构造sql片段
     *
     * @param fieldName 字段名称
     * @param uid       uid
     * @return sql片段 如：unit in(1,2)
     * @author jiaoyu
     * @date 2023/3/31 14:11
     **/
    public String queryUnitidsByUid(String fieldName, String uid) {
        String whereSql = null;
        UserBaseDb userBaseDb = new UserBaseDb();
        userBaseDb.setUid(uid);
        userBaseDb = userBaseHolder.query(userBaseDb);
        if (null != userBaseDb && CheckUtils.isNotEmpty(userBaseDb.getGid())) {
            Long unitid = Long.valueOf(userBaseDb.getGid());
            //查询所有单位信息
            List<UnitBean> list = this.queryList(new UnitBean(), null);
            //构造树结构
            List<UnitBean> retList = new ListToTreeUtil<UnitBean>()
                    .code(UnitBean::getUnitsno)
                    .parent(UnitBean::getParentsno)
                    .children(unitBean -> {
                        if (unitBean.getChildList() == null) {
                            unitBean.setChildList(new ArrayList<>());
                        }
                        return unitBean.getChildList();
                    })
                    .tree(list, unitid);
            //递归 查询单位下级,添加到unitiids集合
            List<Long> unitiids = new ArrayList<>();
            queryChildIds(retList, unitiids);

            whereSql = fieldName + " in(" + unitid;
            for (int i = 0; i < unitiids.size(); i++) {
                whereSql += "," + unitiids.get(i);
            }
            whereSql += ")";
            log.info("whereSql:{}", whereSql);
        }
        return whereSql;
    }

    /**
     * 功能描述:递归查询
     *
     * @param list 集合
     * @param iids iid集合
     * @author jiaoyu
     * @date 2023/3/31 11:28
     **/
    private void queryChildIds(List<UnitBean> list, List<Long> iids) {
        if (null != list) {
            for (UnitBean unitBean : list) {
                iids.add(unitBean.getIid());
                queryChildIds(unitBean.getChildList(), iids);
            }
        }
    }
}
