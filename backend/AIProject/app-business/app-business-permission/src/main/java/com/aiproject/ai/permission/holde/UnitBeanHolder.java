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

    /**
     * 增加
     */

    public Result<Boolean> install(String uid, UnitBean data) {
        String sno = createUnitSno(data.getParentsno());
        data.setUnitsno(sno);
        data.setLastuser(uid);
        if (unitBeanMapper.install(data) <= 0) {
            return Result.failed(false, "增加失败");
        }
        return Result.succeed(true, "增加成功");
    }

    private String createUnitSno(String parentsno) {
        String unit_sno = "";
        String sno = unitBeanMapper.queryMaxSno(parentsno);
        DecimalFormat f = new DecimalFormat("0000");
        if (CheckUtils.isNotEmpty(sno)) {
            sno = sno.substring(sno.length() - 4);
            Long sno_long = Long.valueOf(sno) + 1;
            unit_sno = f.format(sno_long);
        } else {
            unit_sno = "1111";
        }
        if (!parentsno.equals("0")) {
            //非顶级单位
            unit_sno = parentsno + unit_sno;
        }
        return unit_sno;
    }

    /**
     * 删除
     */

    public Result<Boolean> delete(UnitBean data) {
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

    public UnitBean matching(String text, UnitBean data) {
        //translate函数说明，去除字符串A和B，B中包含A的字符会从A剔除，新产生A假设与原A一样，说明B中与A没有交际，反之判断有
        String where = "(length(remark) - length(translate(remark,'" + text + "',''))) > 3";
        List<UnitBean> list = unitBeanMapper.select(null, data, where);
        if (list == null || list.size() == 0) {
            return null;
        }
        return list.get(0);
    }

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
