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

    /**
     * 功能描述: 增加.
     *
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/11 11:59:59
     **/

    public Result<Boolean> install(LableBean data) {
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

    public Result<Boolean> delete(LableBean data) {

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
